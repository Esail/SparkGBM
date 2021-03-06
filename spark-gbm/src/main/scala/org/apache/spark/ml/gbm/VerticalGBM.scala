package org.apache.spark.ml.gbm

import org.apache.spark.Partitioner

import scala.collection.mutable
import scala.reflect.ClassTag
import scala.util.Random

import org.apache.spark.internal.Logging
import org.apache.spark.ml.gbm.linalg._
import org.apache.spark.ml.gbm.rdd._
import org.apache.spark.ml.gbm.util._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._


object VerticalGBM extends Logging {

  /**
    * implementation of GBM, train a GBMModel, with given types
    *
    * @param trainBlocks  training blocks containing (weight+Label, binVec)
    * @param testBlocks   validation blocks containing (weight+Label, binVec)
    * @param boostConf    boosting configuration
    * @param discretizer  discretizer to convert raw features into bins
    * @param initialModel inital model
    * @return the model
    */
  def boost[C, B, H](trainBlocks: (RDD[(CompactArray[H], ArrayBlock[H])], RDD[KVMatrix[C, B]]),
                     testBlocks: Option[(RDD[(CompactArray[H], ArrayBlock[H])], RDD[KVMatrix[C, B]])],
                     boostConf: BoostConfig,
                     discretizer: Discretizer,
                     initialModel: Option[GBMModel])
                    (implicit cc: ClassTag[C], inc: Integral[C], nec: NumericExt[C],
                     cb: ClassTag[B], inb: Integral[B], neb: NumericExt[B],
                     ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H]): GBMModel = {

    val sc = trainBlocks._1.sparkContext
    val parallelism = boostConf.getRealParallelism(boostConf.getReduceParallelism, sc.defaultParallelism)
    boostConf.splitColumns(parallelism)

    val maxCols = boostConf.getVerticalColumnIds[C]().map(_.length).max

    Utils.getTypeByRange(maxCols * boostConf.getBlockSize) match {
      case "Byte" =>
        logInfo(s"DataType of ColumnId in Vertical Format: Byte")
        boostImpl[C, B, H, Byte](trainBlocks, testBlocks, boostConf, discretizer, initialModel)

      case "Short" =>
        logInfo(s"DataType of ColumnId in Vertical Format: Short")
        boostImpl[C, B, H, Short](trainBlocks, testBlocks, boostConf, discretizer, initialModel)

      case "Int" =>
        logInfo(s"DataType of ColumnId in Vertical Format: Int")
        boostImpl[C, B, H, Int](trainBlocks, testBlocks, boostConf, discretizer, initialModel)
    }
  }


  def boostImpl[C, B, H, G](trainBlocks: (RDD[(CompactArray[H], ArrayBlock[H])], RDD[KVMatrix[C, B]]),
                            testBlocks: Option[(RDD[(CompactArray[H], ArrayBlock[H])], RDD[KVMatrix[C, B]])],
                            boostConf: BoostConfig,
                            discretizer: Discretizer,
                            initialModel: Option[GBMModel])
                           (implicit cc: ClassTag[C], inc: Integral[C], nec: NumericExt[C],
                            cb: ClassTag[B], inb: Integral[B], neb: NumericExt[B],
                            ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H],
                            cg: ClassTag[G], ing: Integral[G], neg: NumericExt[G]): GBMModel = {

    val spark = SparkSession.builder().getOrCreate()
    val sc = spark.sparkContext

    val rawBase = boostConf.computeRawBaseScore

    // train blocks
    val (trainWeightLabelBlocks, trainBinVecBlocks) = trainBlocks
    GBM.touchBlocksAndUpdateSizeInfo[H](trainWeightLabelBlocks, boostConf)

    // test blocks
    testBlocks.foreach { case (testWeightLabelBlocks, _) => GBM.touchBlocks(testWeightLabelBlocks, boostConf) }


    // vertical train sub-binvec blocks
    val vSubBinVecBlocks = divideBinVecBlocks[C, B, G](trainBinVecBlocks, boostConf)
    vSubBinVecBlocks.setName("Train BinVector Blocks (Vertical)")
    vSubBinVecBlocks.persist(boostConf.getStorageLevel)


    // init tree buffer
    val weightsBuff = mutable.ArrayBuffer.empty[H]
    val treesBuff = mutable.ArrayBuffer.empty[TreeModel]
    initialModel.foreach { model =>
      weightsBuff.appendAll(neh.fromDouble(model.weights))
      treesBuff.appendAll(model.trees)
    }


    // raw scores and checkpointer
    var trainRawBlocks = GBM.initializeRawBlocks[C, B, H](trainBinVecBlocks,
      treesBuff.toArray, weightsBuff.toArray, boostConf)
      .setName("Train Raw Blocks (Iteration 0)")
    val trainRawBlocksCheckpointer = new Checkpointer[ArrayBlock[H]](sc,
      boostConf.getCheckpointInterval, boostConf.getStorageLevel)
    if (treesBuff.nonEmpty) {
      trainRawBlocksCheckpointer.update(trainRawBlocks)
    }


    // raw scores and checkpointer for test data
    val testRawBlocksCheckpointer = testBlocks.map(_ => new Checkpointer[ArrayBlock[H]](sc,
      boostConf.getCheckpointInterval, boostConf.getStorageLevel))
    var testRawBlocks = testBlocks.map { case (_, testBinVecBlocks) =>
      val newTestRawBlocks = GBM.initializeRawBlocks[C, B, H](testBinVecBlocks,
        treesBuff.toArray, weightsBuff.toArray, boostConf)
        .setName("Test Raw Blocks (Iteration 0)")
      if (treesBuff.nonEmpty) {
        testRawBlocksCheckpointer.get.update(newTestRawBlocks)
      }
      newTestRawBlocks
    }


    // metrics history recoder
    val trainMetricsHistory = mutable.ArrayBuffer.empty[Map[String, Double]]
    val testMetricsHistory = mutable.ArrayBuffer.empty[Map[String, Double]]


    // random number generator for drop out
    val dartRng = new Random(boostConf.getSeed)
    val dropped = mutable.Set.empty[Int]

    var iter = 0
    var finished = false

    while (!finished && iter < boostConf.getMaxIter) {
      val numTrees = treesBuff.length
      val logPrefix = s"Iteration $iter:"

      // drop out
      if (boostConf.getBoostType == GBM.Dart) {
        GBM.dropTrees(dropped, boostConf, numTrees, dartRng)
        if (dropped.nonEmpty) {
          logInfo(s"$logPrefix ${dropped.size} trees dropped")
        } else {
          logInfo(s"$logPrefix skip drop")
        }
      }


      // build trees
      logInfo(s"$logPrefix start")
      val start = System.nanoTime
      val trees = buildTrees[C, B, H, G](trainWeightLabelBlocks, trainBinVecBlocks, vSubBinVecBlocks, trainRawBlocks,
        weightsBuff.toArray, boostConf, iter, dropped.toSet)
      logInfo(s"$logPrefix finished, duration: ${(System.nanoTime - start) / 1e9} sec")

      if (trees.forall(_.isEmpty)) {
        // fail to build a new tree
        logInfo(s"$logPrefix no more tree built, GBM training finished")
        finished = true

      } else {
        // update base model buffer
        GBM.updateTreeBuffer(weightsBuff, treesBuff, trees, dropped.toSet, boostConf)

        // whether to keep the weights of previous trees
        val keepWeights = boostConf.getBoostType != GBM.Dart || dropped.isEmpty

        // update train data predictions
        trainRawBlocks = GBM.updateRawBlocks[C, B, H](trainBinVecBlocks, trainRawBlocks,
          trees, weightsBuff.toArray, boostConf, keepWeights)
          .setName(s"Train Raw Blocks (Iteration ${iter + 1})")
        trainRawBlocksCheckpointer.update(trainRawBlocks)


        if (boostConf.getEvalFunc.isEmpty) {
          // materialize predictions
          trainRawBlocks.count()
        }

        // evaluate on train data
        if (boostConf.getEvalFunc.nonEmpty) {
          val trainMetrics = GBM.evaluate[H, C, B](trainWeightLabelBlocks, trainRawBlocks, boostConf)
          trainMetricsHistory.append(trainMetrics)
          logInfo(s"$logPrefix train metrics ${trainMetrics.mkString("(", ", ", ")")}")
        }


        testBlocks.foreach { case (testWeightLabelBlocks, testBinVecBlocks) =>
          // update test data predictions
          val newTestRawBlocks = GBM.updateRawBlocks[C, B, H](testBinVecBlocks, testRawBlocks.get,
            trees, weightsBuff.toArray, boostConf, keepWeights)
            .setName(s"Test Raw Blocks (Iteration ${iter + 1})")

          testRawBlocks = Some(newTestRawBlocks)
          testRawBlocksCheckpointer.get.update(testRawBlocks.get)

          // evaluate on test data
          val testMetrics = GBM.evaluate[H, C, B](testWeightLabelBlocks, testRawBlocks.get, boostConf)
          testMetricsHistory.append(testMetrics)
          logInfo(s"$logPrefix test metrics ${testMetrics.mkString("(", ", ", ")")}")
        }


        // callback
        if (boostConf.getCallbackFunc.nonEmpty) {
          // using cloning to avoid model modification
          val snapshot = new GBMModel(boostConf.getObjFunc, discretizer.clone(),
            rawBase.clone(), treesBuff.toArray.clone(), neh.toDouble(weightsBuff.toArray).clone())

          // callback can update boosting configuration
          boostConf.getCallbackFunc.foreach { callback =>
            if (callback.compute(spark, boostConf, snapshot, iter + 1,
              trainMetricsHistory.toArray.clone(), testMetricsHistory.toArray.clone())) {
              finished = true
              logInfo(s"$logPrefix callback ${callback.name} stop training")
            }
          }
        }
      }

      logInfo(s"$logPrefix finished, ${treesBuff.length} trees now")
      iter += 1
    }

    if (iter >= boostConf.getMaxIter) {
      logInfo(s"maxIter=${boostConf.getMaxIter} reached, GBM training finished")
    }

    vSubBinVecBlocks.unpersist(false)
    trainRawBlocksCheckpointer.clear()
    testRawBlocksCheckpointer.foreach(_.clear())

    new GBMModel(boostConf.getObjFunc, discretizer, rawBase,
      treesBuff.toArray, neh.toDouble(weightsBuff.toArray))
  }


  def divideBinVecBlocks[C, B, G](trainBinVecBlocks: RDD[KVMatrix[C, B]],
                                  boostConf: BoostConfig)
                                 (implicit cc: ClassTag[C], inc: Integral[C], nec: NumericExt[C],
                                  cb: ClassTag[B], inb: Integral[B], neb: NumericExt[B],
                                  cg: ClassTag[G], ing: Integral[G], neg: NumericExt[G]): RDD[KVVector[G, B]] = {

    val colIdsPerVPart = boostConf.getVerticalColumnIds[C]()

    val numVParts = boostConf.getNumVerticalPartitions
    require(numVParts == colIdsPerVPart.length)

    trainBinVecBlocks.mapPartitionsWithIndex { case (partId, iter) =>

      var cnt = -1L

      iter.flatMap { binVecBlock =>
        cnt += 1

        val binVecs = binVecBlock.iterator.toSeq

        colIdsPerVPart.iterator.zipWithIndex.map { case (colIds, vPartId) =>

          val values = binVecs.iterator.flatMap { binVec =>
            val sliced = binVec.slice(nec.toInt(colIds))
            sliced.iterator.map(_._2)
          }.toArray

          val subBinVecBlock = KVVector.dense[G, B](values).compress
          require(subBinVecBlock.size == colIds.length * binVecs.length)

          ((partId, cnt, vPartId), subBinVecBlock)
        }
      }

    }.repartitionAndSortWithinPartitions(new Partitioner {

      override def numPartitions: Int = numVParts

      override def getPartition(key: Any): Int = key match {
        case (_, _, vPartId: Int) => vPartId
      }

    }).map(_._2)
  }


  def buildTrees[C, B, H, G](weightLabelBlocks: RDD[(CompactArray[H], ArrayBlock[H])],
                             binVecBlocks: RDD[KVMatrix[C, B]],
                             vSubBinVecBlocks: RDD[KVVector[G, B]],
                             rawBlocks: RDD[ArrayBlock[H]],
                             weights: Array[H],
                             boostConf: BoostConfig,
                             iteration: Int,
                             dropped: Set[Int])
                            (implicit cc: ClassTag[C], inc: Integral[C], nec: NumericExt[C],
                             cb: ClassTag[B], inb: Integral[B], neb: NumericExt[B],
                             ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H],
                             cg: ClassTag[G], ing: Integral[G], neg: NumericExt[G]): Array[TreeModel] = {

    val numTrees = boostConf.getBaseModelParallelism * boostConf.getRawSize
    logInfo(s"Iteration $iteration: Starting to create next $numTrees trees")

    val treeIdType = Utils.getTypeByRange(numTrees)
    logInfo(s"DataType of TreeId: $treeIdType")

    val nodeIdType = Utils.getTypeByRange(1 << boostConf.getMaxDepth)
    logInfo(s"DataType of NodeId: $nodeIdType")

    (treeIdType, nodeIdType) match {
      case ("Byte", "Byte") =>
        buildTreesImpl[Byte, Byte, C, B, H, G](weightLabelBlocks, binVecBlocks, vSubBinVecBlocks, rawBlocks, weights, boostConf, iteration, dropped)

      case ("Byte", "Short") =>
        buildTreesImpl[Byte, Short, C, B, H, G](weightLabelBlocks, binVecBlocks, vSubBinVecBlocks, rawBlocks, weights, boostConf, iteration, dropped)

      case ("Byte", "Int") =>
        buildTreesImpl[Byte, Int, C, B, H, G](weightLabelBlocks, binVecBlocks, vSubBinVecBlocks, rawBlocks, weights, boostConf, iteration, dropped)

      case ("Short", "Byte") =>
        buildTreesImpl[Short, Byte, C, B, H, G](weightLabelBlocks, binVecBlocks, vSubBinVecBlocks, rawBlocks, weights, boostConf, iteration, dropped)

      case ("Short", "Short") =>
        buildTreesImpl[Short, Short, C, B, H, G](weightLabelBlocks, binVecBlocks, vSubBinVecBlocks, rawBlocks, weights, boostConf, iteration, dropped)

      case ("Short", "Int") =>
        buildTreesImpl[Short, Int, C, B, H, G](weightLabelBlocks, binVecBlocks, vSubBinVecBlocks, rawBlocks, weights, boostConf, iteration, dropped)

      case ("Int", "Byte") =>
        buildTreesImpl[Int, Byte, C, B, H, G](weightLabelBlocks, binVecBlocks, vSubBinVecBlocks, rawBlocks, weights, boostConf, iteration, dropped)

      case ("Int", "Short") =>
        buildTreesImpl[Int, Short, C, B, H, G](weightLabelBlocks, binVecBlocks, vSubBinVecBlocks, rawBlocks, weights, boostConf, iteration, dropped)

      case ("Int", "Int") =>
        buildTreesImpl[Int, Int, C, B, H, G](weightLabelBlocks, binVecBlocks, vSubBinVecBlocks, rawBlocks, weights, boostConf, iteration, dropped)
    }
  }


  /**
    * build new trees
    *
    * @param weightLabelBlocks blockified instances containing (weight, labels)
    * @param rawBlocks         previous raw predictions
    * @param weights           weights of trees
    * @param boostConf         boosting configuration
    * @param iteration         current iteration
    * @param dropped           indices of trees which are selected to drop during building of current tree
    * @return new trees
    */
  def buildTreesImpl[T, N, C, B, H, G](weightLabelBlocks: RDD[(CompactArray[H], ArrayBlock[H])],
                                       binVecBlocks: RDD[KVMatrix[C, B]],
                                       vSubBinVecBlocks: RDD[KVVector[G, B]],
                                       rawBlocks: RDD[ArrayBlock[H]],
                                       weights: Array[H],
                                       boostConf: BoostConfig,
                                       iteration: Int,
                                       dropped: Set[Int])
                                      (implicit ct: ClassTag[T], int: Integral[T], net: NumericExt[T],
                                       cn: ClassTag[N], inn: Integral[N], nen: NumericExt[N],
                                       cc: ClassTag[C], inc: Integral[C], nec: NumericExt[C],
                                       cb: ClassTag[B], inb: Integral[B], neb: NumericExt[B],
                                       ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H],
                                       cg: ClassTag[G], ing: Integral[G], neg: NumericExt[G]): Array[TreeModel] = {
    import nuh._

    val numBaseModels = boostConf.getBaseModelParallelism

    val rawBase = neh.fromDouble(boostConf.computeRawBaseScore)
    val rawSize = boostConf.getRawSize

    val computeRaw = boostConf.getBoostType match {
      case GBM.GBTree =>
        rawSeq: Array[H] => rawSeq

      case GBM.Goss =>
        rawSeq: Array[H] => rawSeq

      case GBM.Dart if dropped.isEmpty =>
        rawSeq: Array[H] => rawSeq.take(rawSize)

      case GBM.Dart if dropped.nonEmpty =>
        rawSeq: Array[H] =>
          val raw = rawBase.clone
          Iterator.range(rawSize, rawSeq.length)
            .filterNot(i => dropped.contains(i - rawSize))
            .foreach { i => raw(i % rawSize) += rawSeq(i) * weights(i - rawSize) }
          raw
    }

    val computeGrad = (weight: H, label: Array[H], rawSeq: Array[H]) => {
      val raw = neh.toDouble(computeRaw(rawSeq))
      val score = boostConf.getObjFunc.transform(raw)
      val (grad, hess) = boostConf.getObjFunc.compute(neh.toDouble(label), score)
      require(grad.length == rawSize && hess.length == rawSize)

      val array = Array.ofDim[H](rawSize << 1)
      var i = 0
      while (i < rawSize) {
        val j = i << 1
        array(j) = neh.fromDouble(grad(i)) * weight
        array(j + 1) = neh.fromDouble(hess(i)) * weight
        i += 1
      }
      array
    }

    val computeGradBlock = (weightBlock: CompactArray[H], labelBlock: ArrayBlock[H], rawBlock: ArrayBlock[H]) => {
      require(weightBlock.size == rawBlock.size)
      require(labelBlock.size == rawBlock.size)

      val iter = weightBlock.iterator
        .zip(labelBlock.iterator)
        .zip(rawBlock.iterator)
        .map { case ((weight, label), rawSeq) => computeGrad(weight, label, rawSeq) }

      val gradBlock = ArrayBlock.build[H](iter)
      require(gradBlock.size == rawBlock.size)
      gradBlock
    }


    val recoder = new ResourceRecoder

    val baseConfig = BaseConfig.create(boostConf, iteration, numBaseModels, boostConf.getSeed + iteration)

    // To alleviate memory footprint in caching layer, different schemas of intermediate dataset are designed.
    // Each `prepareTreeInput**` method will internally cache necessary datasets in a compact fashion.
    // These cached datasets are holden in `recoder`, and will be freed after training.
    val (data, vdata) = (boostConf.getSubSampleType, boostConf.getSubSampleRate == 1) match {
      case (_, true) =>
        adaptTreeInputsForNonSampling[T, N, C, B, H, G](weightLabelBlocks, binVecBlocks, vSubBinVecBlocks, rawBlocks, boostConf, iteration, computeGradBlock, recoder)

      case (GBM.Block, _) =>
        adaptTreeInputsForBlockSampling[T, N, C, B, H, G](weightLabelBlocks, binVecBlocks, vSubBinVecBlocks, rawBlocks, boostConf, iteration, computeGradBlock, recoder)

      case (GBM.Instance, _) =>
        adaptTreeInputsForInstanceSampling[T, N, C, B, H, G](weightLabelBlocks, binVecBlocks, vSubBinVecBlocks, rawBlocks, boostConf, iteration, computeGrad, recoder)
    }

    val trees = Tree.trainVertical[T, N, C, B, H](data, vdata, boostConf, baseConfig)

    recoder.clear()

    trees
  }


  def adaptTreeInputsForNonSampling[T, N, C, B, H, G](weightLabelBlocks: RDD[(CompactArray[H], ArrayBlock[H])],
                                                      binVecBlocks: RDD[KVMatrix[C, B]],
                                                      vSubBinVecBlocks: RDD[KVVector[G, B]],
                                                      rawBlocks: RDD[ArrayBlock[H]],
                                                      boostConf: BoostConfig,
                                                      iteration: Int,
                                                      computeGradBlock: (CompactArray[H], ArrayBlock[H], ArrayBlock[H]) => ArrayBlock[H],
                                                      recoder: ResourceRecoder)
                                                     (implicit ct: ClassTag[T], int: Integral[T], net: NumericExt[T],
                                                      cn: ClassTag[N], inn: Integral[N], nen: NumericExt[N],
                                                      cc: ClassTag[C], inc: Integral[C], nec: NumericExt[C],
                                                      cb: ClassTag[B], inb: Integral[B], neb: NumericExt[B],
                                                      ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H],
                                                      cg: ClassTag[G], ing: Integral[G], neg: NumericExt[G]): (RDD[(KVVector[C, B], Array[T], Array[H])], RDD[(KVVector[C, B], Array[T], Array[H])]) = {
    import RDDFunctions._

    val numVParts = boostConf.getNumVerticalPartitions
    val rawSize = boostConf.getRawSize
    val numBaseModels = boostConf.getBaseModelParallelism
    val numTrees = numBaseModels * rawSize
    val vColIds = boostConf.getVerticalColumnIds[C]
    val numCols = boostConf.getNumCols

    val gradBlocks = weightLabelBlocks.zip(rawBlocks)
      .map { case ((weightBlock, labelBlock), rawBlock) => computeGradBlock(weightBlock, labelBlock, rawBlock) }

    gradBlocks.setName(s"GradientBlocks (iteration $iteration)")
    gradBlocks.persist(boostConf.getStorageLevel)
    recoder.append(gradBlocks)

    val data = binVecBlocks.zip(gradBlocks).mapPartitions { iter =>
      val treeIds = Array.tabulate(numTrees)(int.fromInt)

      iter.flatMap { case (binVecBlock, gradBlock) =>
        require(binVecBlock.size == gradBlock.size,
          s"size of bin vectors: ${binVecBlock.size} != size of gradients : ${gradBlock.size}")

        binVecBlock.iterator.zip(gradBlock.iterator)
          .map { case (binVec, grad) => (binVec, treeIds, grad) }
      }
    }.setName(s"Gradients with TreeIds (iteration $iteration)")


    val agGradBlocks = gradBlocks.allgather(numVParts)
    agGradBlocks.setName(s"GradientBlocks (iteration $iteration) (AllGathered)")
    agGradBlocks.persist(boostConf.getStorageLevel)
    recoder.append(agGradBlocks)


    val vdata = vSubBinVecBlocks.zip(agGradBlocks).mapPartitionsWithIndex { case (vPartId, iter) =>
      val localVColIds = vColIds(vPartId)
      val treeIds = Array.tabulate(numTrees)(int.fromInt)

      iter.flatMap { case (subBinVecBlock, gradBlock) =>
        require(subBinVecBlock.size == gradBlock.size * localVColIds.length)

        subBinVecBlock.iterator.map(_._2)
          .grouped(localVColIds.length)
          .zip(gradBlock.iterator)
          .map { case (values, grad) =>
            val subBinVec = KVVector.sparse[C, B](numCols, localVColIds, values.toArray)
            (subBinVec, treeIds, grad)
          }
      }
    }.setName(s"Gradients with TreeIds (iteration $iteration) (Vertical)")

    (data, vdata)
  }


  def adaptTreeInputsForBlockSampling[T, N, C, B, H, G](weightLabelBlocks: RDD[(CompactArray[H], ArrayBlock[H])],
                                                        binVecBlocks: RDD[KVMatrix[C, B]],
                                                        vSubBinVecBlocks: RDD[KVVector[G, B]],
                                                        rawBlocks: RDD[ArrayBlock[H]],
                                                        boostConf: BoostConfig,
                                                        iteration: Int,
                                                        computeGradBlock: (CompactArray[H], ArrayBlock[H], ArrayBlock[H]) => ArrayBlock[H],
                                                        recoder: ResourceRecoder)
                                                       (implicit ct: ClassTag[T], int: Integral[T], net: NumericExt[T],
                                                        cn: ClassTag[N], inn: Integral[N], nen: NumericExt[N],
                                                        cc: ClassTag[C], inc: Integral[C], nec: NumericExt[C],
                                                        cb: ClassTag[B], inb: Integral[B], neb: NumericExt[B],
                                                        ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H],
                                                        cg: ClassTag[G], ing: Integral[G], neg: NumericExt[G]): (RDD[(KVVector[C, B], Array[T], Array[H])], RDD[(KVVector[C, B], Array[T], Array[H])]) = {
    import RDDFunctions._

    val numVParts = boostConf.getNumVerticalPartitions
    val rawSize = boostConf.getRawSize
    val numBaseModels = boostConf.getBaseModelParallelism
    val numTrees = numBaseModels * rawSize
    val blockIdOffsets = boostConf.getBlockIdOffsetPerPartition
    val vColIds = boostConf.getVerticalColumnIds[C]
    val numCols = boostConf.getNumCols

    val blockSelector = Selector.create(boostConf.getSubSampleRate,
      boostConf.getNumBlocks, numBaseModels, boostConf.getRawSize, boostConf.getSeed + iteration)
    logInfo(s"Iteration $iteration, blockSelector $blockSelector")


    val gradBlocks = weightLabelBlocks.zip(rawBlocks).mapPartitionsWithIndex { case (partId, iter) =>
      var blockId = blockIdOffsets(partId) - 1
      val emptyValue = (ArrayBlock.empty[H], net.emptyArray)

      iter.map { case ((weightBlock, labelBlock), rawBlock) =>
        blockId += 1

        val treeIds = Array.range(0, numTrees)
          .filter { i => blockSelector.contains(i, blockId) }.map(int.fromInt)

        if (treeIds.nonEmpty) {
          val gradBlock = computeGradBlock(weightBlock, labelBlock, rawBlock)
          (gradBlock, treeIds)
        } else {
          emptyValue
        }
      }
    }

    gradBlocks.setName(s"GradientBlocks with TreeIds (iteration $iteration)")
    gradBlocks.persist(boostConf.getStorageLevel)
    recoder.append(gradBlocks)

    val data = binVecBlocks.zip(gradBlocks).flatMap { case (binVecBlock, (gradBlock, treeIds)) =>
      if (treeIds.nonEmpty) {
        require(binVecBlock.size == gradBlock.size)
        binVecBlock.iterator.zip(gradBlock.iterator)
          .map { case (binVec, grad) => (binVec, treeIds, grad) }

      } else {
        require(gradBlock.isEmpty)
        Iterator.empty
      }
    }.setName(s"Gradients with TreeIds (iteration $iteration) (Block-Based Sampled)")


    val agGradBlocks = gradBlocks.map(_._1).filter(_.nonEmpty).allgather(numVParts)
    agGradBlocks.setName(s"GradientBlocks (iteration $iteration) (AllGathered)")
    agGradBlocks.persist(boostConf.getStorageLevel)
    recoder.append(agGradBlocks)


    val sampled = vSubBinVecBlocks.mapPartitions { iter =>
      var blockId = -1L

      iter.flatMap { subBinVecBlock =>
        blockId += 1

        val treeIds = Array.range(0, numTrees)
          .filter { i => blockSelector.contains(i, blockId) }.map(int.fromInt)

        if (treeIds.nonEmpty) {
          Iterator.single((subBinVecBlock, treeIds))
        } else {
          Iterator.empty
        }
      }
    }


    val vdata = sampled.zip(agGradBlocks).mapPartitionsWithIndex { case (vPartId, iter) =>
      val localVColIds = vColIds(vPartId)

      iter.flatMap { case ((subBinVecBlock, treeIds), gradBlock) =>
        require(subBinVecBlock.size == gradBlock.size * localVColIds.length)

        subBinVecBlock.iterator.map(_._2)
          .grouped(localVColIds.length)
          .zip(gradBlock.iterator)
          .map { case (values, grad) =>
            val subBinVec = KVVector.sparse[C, B](numCols, localVColIds, values.toArray)
            (subBinVec, treeIds, grad)
          }
      }
    }.setName(s"Gradients with TreeIds (iteration $iteration) (Block-Based Sampled) (Vertical)")

    (data, vdata)
  }


  def adaptTreeInputsForInstanceSampling[T, N, C, B, H, G](weightLabelBlocks: RDD[(CompactArray[H], ArrayBlock[H])],
                                                           binVecBlocks: RDD[KVMatrix[C, B]],
                                                           vSubBinVecBlocks: RDD[KVVector[G, B]],
                                                           rawBlocks: RDD[ArrayBlock[H]],
                                                           boostConf: BoostConfig,
                                                           iteration: Int,
                                                           computeGrad: (H, Array[H], Array[H]) => Array[H],
                                                           recoder: ResourceRecoder)
                                                          (implicit ct: ClassTag[T], int: Integral[T], net: NumericExt[T],
                                                           cn: ClassTag[N], inn: Integral[N], nen: NumericExt[N],
                                                           cc: ClassTag[C], inc: Integral[C], nec: NumericExt[C],
                                                           cb: ClassTag[B], inb: Integral[B], neb: NumericExt[B],
                                                           ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H],
                                                           cg: ClassTag[G], ing: Integral[G], neg: NumericExt[G]): (RDD[(KVVector[C, B], Array[T], Array[H])], RDD[(KVVector[C, B], Array[T], Array[H])]) = {
    import RDDFunctions._

    val numVParts = boostConf.getNumVerticalPartitions
    val rawSize = boostConf.getRawSize
    val numBaseModels = boostConf.getBaseModelParallelism
    val numTrees = numBaseModels * rawSize
    val instanceIdOffsets = boostConf.getBlockIdOffsetPerPartition
    val vColIds = boostConf.getVerticalColumnIds[C]
    val numCols = boostConf.getNumCols

    val instanceSelector = Selector.create(boostConf.getSubSampleRate,
      boostConf.getNumInstances, numBaseModels, boostConf.getRawSize, boostConf.getSeed + iteration)
    logInfo(s"Iteration $iteration, instanceSelector $instanceSelector")

    val gradBlocks = weightLabelBlocks.zip(rawBlocks).mapPartitionsWithIndex { case (partId, iter) =>
      var instanceId = instanceIdOffsets(partId) - 1
      val emptyValue = (neh.emptyArray, net.emptyArray)

      iter.map { case ((weightBlock, labelBlock), rawBlock) =>
        require(weightBlock.size == rawBlock.size)
        require(labelBlock.size == rawBlock.size)

        val seq = weightBlock.iterator.zip(labelBlock.iterator).zip(rawBlock.iterator)
          .map { case ((weight, label), rawSeq) =>
            instanceId += 1

            val treeIds = Array.range(0, numTrees)
              .filter { i => instanceSelector.contains(i, instanceId) }.map(int.fromInt)

            if (treeIds.nonEmpty) {
              val grad = computeGrad(weight, label, rawSeq)
              (grad, treeIds)
            } else {
              emptyValue
            }
          }.toSeq

        val gradBlock = ArrayBlock.build[H](seq.iterator.map(_._1))
        val treeIdBlock = ArrayBlock.build[T](seq.iterator.map(_._2))

        (gradBlock, treeIdBlock)
      }
    }

    gradBlocks.setName(s"GradientBlocks with TreeIds (iteration $iteration)")
    gradBlocks.persist(boostConf.getStorageLevel)
    recoder.append(gradBlocks)

    val data = binVecBlocks.zip(gradBlocks).flatMap { case (binVecBlock, (gradBlock, treeIdBlock)) =>
      require(binVecBlock.size == gradBlock.size)
      require(binVecBlock.size == treeIdBlock.size)

      binVecBlock.iterator.zip(treeIdBlock.iterator).zip(gradBlock.iterator)
        .flatMap { case ((binVec, treeIds), grad) =>
          if (treeIds.nonEmpty) {
            Iterator.single(binVec, treeIds, grad)
          } else {
            Iterator.empty
          }
        }
    }.setName(s"Gradients with TreeIds (iteration $iteration) (Instance-Based Sampled)")


    val agGradBlocks = gradBlocks.map(_._1).allgather(numVParts)
    agGradBlocks.setName(s"GradientBlocks (iteration $iteration) (AllGathered)")
    agGradBlocks.persist(boostConf.getStorageLevel)
    recoder.append(agGradBlocks)


    val sampled = vSubBinVecBlocks.mapPartitionsWithIndex { case (vPartId, iter) =>
      val localVColIds = vColIds(vPartId)
      var instanceId = -1L

      iter.flatMap { subBinVecBlock =>
        require(subBinVecBlock.size % localVColIds.length == 0)

        subBinVecBlock.iterator.map(_._2)
          .grouped(localVColIds.length)
          .map { values => KVVector.sparse[C, B](numCols, localVColIds, values.toArray) }

      }.flatMap { subBinVec =>
        instanceId += 1

        val treeIds = Array.range(0, numTrees)
          .filter { i => instanceSelector.contains(i, instanceId) }.map(int.fromInt)

        if (treeIds.nonEmpty) {
          Iterator.single((subBinVec, treeIds))
        } else {
          Iterator.empty
        }
      }
    }
    
    val vdata = sampled.zip(agGradBlocks.flatMap(_.iterator))
      .map { case ((subBinVec, treeIds), grad) => (subBinVec, treeIds, grad) }

    (data, vdata)
  }
}


