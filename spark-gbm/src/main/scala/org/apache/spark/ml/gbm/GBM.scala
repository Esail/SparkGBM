package org.apache.spark.ml.gbm

import scala.collection.{BitSet, mutable}
import scala.reflect.ClassTag
import scala.{specialized => spec}
import scala.util.Random

import org.apache.spark.internal.Logging
import org.apache.spark.ml.linalg._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.storage.StorageLevel
import org.apache.spark.util.random.XORShiftRandom


/**
  * RDD-based API for gradient boosting machine
  */
class GBM extends Logging with Serializable {

  val boostConf = new BoostConfig

  /** maximum number of iterations */
  def setMaxIter(value: Int): this.type = {
    require(value >= 0)
    boostConf.setMaxIter(value)
    this
  }

  def getMaxIter: Int = boostConf.getMaxIter


  /** maximum tree depth */
  def setMaxDepth(value: Int): this.type = {
    require(value >= 1 && value <= 30)
    boostConf.setMaxDepth(value)
    this
  }

  def getMaxDepth: Int = boostConf.getMaxDepth


  /** maximum number of tree leaves */
  def setMaxLeaves(value: Int): this.type = {
    require(value >= 2)
    boostConf.setMaxLeaves(value)
    this
  }

  def getMaxLeaves: Int = boostConf.getMaxLeaves


  /** minimum gain for each split */
  def setMinGain(value: Double): this.type = {
    require(value >= 0 && !value.isNaN && !value.isInfinity)
    boostConf.setMinGain(value)
    this
  }

  def getMinGain: Double = boostConf.getMinGain


  /** base score for global bias */
  def setBaseScore(value: Array[Double]): this.type = {
    require(value.nonEmpty)
    require(value.forall(v => !v.isNaN && !v.isInfinity))
    boostConf.setBaseScore(value)
    this
  }

  def getBaseScore: Array[Double] = boostConf.getBaseScore


  /** minimum sum of hess for each node */
  def setMinNodeHess(value: Double): this.type = {
    require(value >= 0 && !value.isNaN && !value.isInfinity)
    boostConf.setMinNodeHess(value)
    this
  }

  def getMinNodeHess: Double = boostConf.getMinNodeHess


  /** learning rate */
  def setStepSize(value: Double): this.type = {
    require(value > 0 && !value.isNaN && !value.isInfinity)
    boostConf.setStepSize(value)
    this
  }

  def getStepSize: Double = boostConf.getStepSize


  /** L1 regularization term on weights */
  def setRegAlpha(value: Double): this.type = {
    require(value >= 0 && !value.isNaN && !value.isInfinity)
    boostConf.setRegAlpha(value)
    this
  }

  def getRegAlpha: Double = boostConf.getRegAlpha


  /** L2 regularization term on weights */
  def setRegLambda(value: Double): this.type = {
    require(value >= 0 && !value.isNaN && !value.isInfinity)
    boostConf.setRegLambda(value)
    this
  }

  def getRegLambda: Double = boostConf.getRegLambda


  /** objective function */
  def setObjFunc(value: ObjFunc): this.type = {
    require(value != null)
    boostConf.setObjFunc(value)
    this
  }

  def getObjFunc: ObjFunc = boostConf.getObjFunc


  /** evaluation functions */
  def setEvalFunc(value: Array[EvalFunc]): this.type = {
    require(value.map(_.name).distinct.length == value.length)
    boostConf.setEvalFunc(value)
    this
  }

  def getEvalFunc: Array[EvalFunc] = boostConf.getEvalFunc


  /** callback functions */
  def setCallbackFunc(value: Array[CallbackFunc]): this.type = {
    require(value.map(_.name).distinct.length == value.length)
    boostConf.setCallbackFunc(value)
    this
  }

  def getCallbackFunc: Array[CallbackFunc] = boostConf.getCallbackFunc


  /** indices of categorical columns */
  def setCatCols(value: Set[Int]): this.type = {
    require(value.forall(_ >= 0))
    val builder = BitSet.newBuilder
    builder ++= value
    boostConf.setCatCols(builder.result)
    this
  }

  def getCatCols: Set[Int] = boostConf.getCatCols.toSet


  /** indices of ranking columns */
  def setRankCols(value: Set[Int]): this.type = {
    require(value.forall(_ >= 0))
    val builder = BitSet.newBuilder
    builder ++= value
    boostConf.setRankCols(builder.result)
    this
  }

  def getRankCols: Set[Int] = boostConf.getRankCols.toSet


  /** subsample ratio of the training instance */
  def setSubSample(value: Double): this.type = {
    require(value > 0 && value <= 1 && !value.isNaN && !value.isInfinity)
    boostConf.setSubSample(value)
    this
  }

  def getSubSample: Double = boostConf.getSubSample


  /** subsample ratio of columns when constructing each tree */
  def setColSampleByTree(value: Double): this.type = {
    require(value > 0 && value <= 1 && !value.isNaN && !value.isInfinity)
    boostConf.setColSampleByTree(value)
    this
  }

  def getColSampleByTree: Double = boostConf.getColSampleByTree


  /** subsample ratio of columns when constructing each level */
  def setColSampleByLevel(value: Double): this.type = {
    require(value > 0 && value <= 1 && !value.isNaN && !value.isInfinity)
    boostConf.setColSampleByLevel(value)
    this
  }

  def getColSampleByLevel: Double = boostConf.getColSampleByLevel


  /** checkpoint interval */
  def setCheckpointInterval(value: Int): this.type = {
    require(value == -1 || value > 0)
    boostConf.setCheckpointInterval(value)
    this
  }

  def getCheckpointInterval: Int = boostConf.getCheckpointInterval


  /** storage level */
  def setStorageLevel(value: StorageLevel): this.type = {
    require(value != StorageLevel.NONE)
    boostConf.setStorageLevel(value)
    this
  }

  def getStorageLevel: StorageLevel = boostConf.getStorageLevel


  /** depth for treeAggregate */
  def setAggregationDepth(value: Int): this.type = {
    require(value >= 2)
    boostConf.setAggregationDepth(value)
    this
  }

  def getAggregationDepth: Int = boostConf.getAggregationDepth


  /** random number seed */
  def setSeed(value: Long): this.type = {
    boostConf.setSeed(value)
    this
  }

  def getSeed: Long = boostConf.getSeed


  /** parallelism of histogram computation */
  def setReduceParallelism(value: Double): this.type = {
    require(value != 0 && !value.isNaN && !value.isInfinity)
    boostConf.setReduceParallelism(value)
    this
  }

  def getReduceParallelism: Double = boostConf.getReduceParallelism


  /** parallelism of split searching */
  def setTrialParallelism(value: Double): this.type = {
    require(value != 0 && !value.isNaN && !value.isInfinity)
    boostConf.setTrialParallelism(value)
    this
  }

  def getTrialParallelism: Double = boostConf.getTrialParallelism


  /** boosting type */
  def setBoostType(value: String): this.type = {
    require(value == GBM.GBTree || value == GBM.Dart)
    boostConf.setBoostType(value)
    this
  }

  def getBoostType: String = boostConf.getBoostType


  /** dropout rate */
  def setDropRate(value: Double): this.type = {
    require(value >= 0 && value <= 1 && !value.isNaN && !value.isInfinity)
    boostConf.setDropRate(value)
    this
  }

  def getDropRate: Double = boostConf.getDropRate


  /** probability of skipping drop */
  def setDropSkip(value: Double): this.type = {
    require(value >= 0 && value <= 1 && !value.isNaN && !value.isInfinity)
    boostConf.setDropSkip(value)
    this
  }

  def getDropSkip: Double = boostConf.getDropSkip

  /** minimum number of dropped trees in each iteration */
  def setMinDrop(value: Int): this.type = {
    require(value >= 0)
    boostConf.setMinDrop(value)
    this
  }

  def getMinDrop: Int = boostConf.getMinDrop


  /** maximum number of dropped trees in each iteration */
  def setMaxDrop(value: Int): this.type = {
    require(value >= 0)
    boostConf.setMaxDrop(value)
    this
  }

  def getMaxDrop: Int = boostConf.getMaxDrop


  /** the maximum number of non-zero histogram bins to search split for categorical columns by brute force */
  def setMaxBruteBins(value: Int): this.type = {
    require(value >= 0)
    boostConf.setMaxBruteBins(value)
    this
  }

  def getMaxBruteBins: Int = boostConf.getMaxBruteBins

  /** Double precision to represent internal gradient, hessian and prediction */
  def setFloatType(value: String): this.type = {
    require(value == GBM.SinglePrecision || value == GBM.DoublePrecision)
    boostConf.setFloatType(value)
    this
  }

  def getFloatType: String = boostConf.getFloatType


  /** number of base models in one round */
  def setBaseModelParallelism(value: Int): this.type = {
    require(value > 0)
    boostConf.setBaseModelParallelism(value)
    this
  }

  def getBaseModelParallelism: Int = boostConf.getBaseModelParallelism


  /** whether to sample partitions instead of instances if possible */
  def setSampleBlocks(value: Boolean): this.type = {
    boostConf.setSampleBlocks(value)
    this
  }

  def getSampleBlocks: Boolean = boostConf.getSampleBlocks


  /** size of block */
  def setBlockSize(value: Int): this.type = {
    require(value > 0)
    boostConf.setBlockSize(value)
    this
  }

  def getBlockSize: Int = boostConf.getBlockSize


  /** initial model */
  private var initialModel: Option[GBMModel] = None

  def setInitialModel(value: Option[GBMModel]): this.type = {
    initialModel = value
    this
  }

  def getInitialModel: Option[GBMModel] = initialModel


  /** maximum number of bins for each column */
  private var maxBins: Int = 64

  def setMaxBins(value: Int): this.type = {
    require(value >= 4)
    maxBins = value
    this
  }

  def getMaxBins: Int = maxBins


  /** method to discretize numerical columns */
  private var numericalBinType: String = GBM.Width

  def setNumericalBinType(value: String): this.type = {
    require(value == GBM.Width || value == GBM.Depth)
    numericalBinType = value
    this
  }

  def getNumericalBinType: String = numericalBinType


  /** whether zero is viewed as missing value */
  private var zeroAsMissing: Boolean = false

  def setZeroAsMissing(value: Boolean): this.type = {
    zeroAsMissing = value
    this
  }

  def getZeroAsMissing: Boolean = zeroAsMissing


  /** training, dataset contains (weight, label, vec) */
  def fit(data: RDD[(Double, Array[Double], Vector)]): GBMModel = {
    fit(data, None)
  }


  /** training with validation, dataset contains (weight, label, vec) */
  def fit(data: RDD[(Double, Array[Double], Vector)],
          test: RDD[(Double, Array[Double], Vector)]): GBMModel = {
    fit(data, Some(test))
  }


  /** training with validation if any, dataset contains (weight, label, vec) */
  private[ml] def fit(data: RDD[(Double, Array[Double], Vector)],
                      test: Option[RDD[(Double, Array[Double], Vector)]]): GBMModel = {
    if (getBoostType == GBM.Dart) {
      require(getMaxDrop >= getMinDrop)
    }

    val sc = data.sparkContext

    val numCols = data.first._3.size
    require(numCols > 0)

    val validation = test.nonEmpty && getEvalFunc.nonEmpty

    val discretizer = if (initialModel.nonEmpty) {
      require(numCols == initialModel.get.discretizer.numCols)
      logWarning(s"Discretizer is already provided in the initial model, related params are ignored: " +
        s"maxBins,catCols,rankCols,numericalBinType,zeroAsMissing")
      initialModel.get.discretizer

    } else {
      require(getCatCols.forall(v => v >= 0 && v < numCols))
      require(getRankCols.forall(v => v >= 0 && v < numCols))
      require((getCatCols & getRankCols).isEmpty)

      Discretizer.fit(data.map(_._3), numCols, boostConf.getCatCols, boostConf.getRankCols,
        maxBins, numericalBinType, zeroAsMissing, getAggregationDepth)
    }
    logInfo(s"Bins: ${discretizer.numBins.mkString(",")}, " +
      s"Min: ${discretizer.numBins.min}, Max: ${discretizer.numBins.max}, " +
      s"Avg: ${discretizer.numBins.sum.toDouble / discretizer.numCols}")
    logInfo(s"Sparsity of train data: ${discretizer.sparsity}")


    if (initialModel.nonEmpty) {
      val baseScore_ = initialModel.get.baseScore
      logWarning(s"BaseScore is already provided in the initial model, related param is overridden: " +
        s"${boostConf.getBaseScore.mkString(",")} -> ${baseScore_.mkString(",")}")
      boostConf.setBaseScore(baseScore_)

    } else if (boostConf.getBaseScore.isEmpty) {

      val (_, avgLabel) = data.map { case (weight, label, _) =>
        (weight, label)
      }.treeReduce(f = {
        case ((w1, avg1), (w2, avg2)) =>
          require(avg1.length == avg2.length)
          val w = w1 + w2
          avg1.indices.foreach { i => avg1(i) += (avg2(i) - avg1(i)) * w2 / w }
          (w, avg1)
      }, depth = boostConf.getAggregationDepth)

      logInfo(s"Basescore is not provided, assign it to average label value " +
        s"${boostConf.getBaseScore.mkString(",")}")
      boostConf.setBaseScore(avgLabel)
    }

    val rawBase = boostConf.computeRawBaseScore
    logInfo(s"base score vector: ${boostConf.getBaseScore.mkString(",")}, raw base vector: ${rawBase.mkString(",")}")

    boostConf
      .setNumCols(numCols)
      .setRawSize(rawBase.length)

    GBM.boost(data, test.getOrElse(sc.emptyRDD), boostConf, validation, discretizer, initialModel)
  }
}


private[gbm] object GBM extends Logging {

  val GBTree = "gbtree"
  val Dart = "dart"

  val Width = "width"
  val Depth = "depth"

  val SinglePrecision = "float"
  val DoublePrecision = "double"


  /**
    * train a GBM model, dataset contains (weight, label, vec)
    */
  def boost(data: RDD[(Double, Array[Double], Vector)],
            test: RDD[(Double, Array[Double], Vector)],
            boostConf: BoostConfig,
            validation: Boolean,
            discretizer: Discretizer,
            initialModel: Option[GBMModel]): GBMModel = {

    logInfo(s"DataType of RealValue: ${boostConf.getFloatType.capitalize}")

    boostConf.getFloatType match {
      case SinglePrecision =>
        boost1[Double](data, test, boostConf, validation, discretizer, initialModel)

      case DoublePrecision =>
        boost1[Double](data, test, boostConf, validation, discretizer, initialModel)
    }
  }


  /**
    * train a GBM model, dataset contains (weight, label, vec)
    */
  def boost1[H](data: RDD[(Double, Array[Double], Vector)],
                test: RDD[(Double, Array[Double], Vector)],
                boostConf: BoostConfig,
                validation: Boolean,
                discretizer: Discretizer,
                initialModel: Option[GBMModel])
               (implicit ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H]): GBMModel = {
    val data2 = data.map { case (weight, label, vec) =>
      (neh.fromDouble(weight), neh.fromDouble(label), vec)
    }

    val test2 = test.map { case (weight, label, vec) =>
      (neh.fromDouble(weight), neh.fromDouble(label), vec)
    }


    val columnIndexType = Utils.getTypeByRange(discretizer.numCols)
    logInfo(s"DataType of ColumnId: $columnIndexType")

    val binType = Utils.getTypeByRange(discretizer.numBins.max * 2 - 1)
    logInfo(s"DataType of Bin: $binType")

    (columnIndexType, binType) match {
      case ("Byte", "Byte") =>
        boost2[Byte, Byte, H](data2, test2, boostConf, validation, discretizer, initialModel)

      case ("Byte", "Short") =>
        boost2[Byte, Short, H](data2, test2, boostConf, validation, discretizer, initialModel)

      case ("Byte", "Int") =>
        boost2[Byte, Int, H](data2, test2, boostConf, validation, discretizer, initialModel)

      case ("Short", "Byte") =>
        boost2[Short, Byte, H](data2, test2, boostConf, validation, discretizer, initialModel)

      case ("Short", "Short") =>
        boost2[Short, Short, H](data2, test2, boostConf, validation, discretizer, initialModel)

      case ("Short", "Int") =>
        boost2[Short, Int, H](data2, test2, boostConf, validation, discretizer, initialModel)

      case ("Int", "Byte") =>
        boost2[Int, Byte, H](data2, test2, boostConf, validation, discretizer, initialModel)

      case ("Int", "Short") =>
        boost2[Int, Short, H](data2, test2, boostConf, validation, discretizer, initialModel)

      case ("Int", "Int") =>
        boost2[Int, Int, H](data2, test2, boostConf, validation, discretizer, initialModel)
    }
  }


  /**
    * train a GBM model, dataset contains (weight, label, vec)
    */
  def boost2[C, B, H](data: RDD[(H, Array[H], Vector)],
                      test: RDD[(H, Array[H], Vector)],
                      boostConf: BoostConfig,
                      validation: Boolean,
                      discretizer: Discretizer,
                      initialModel: Option[GBMModel])
                     (implicit cc: ClassTag[C], inc: Integral[C], nec: NumericExt[C],
                      cb: ClassTag[B], inb: Integral[B], neb: NumericExt[B],
                      ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H]): GBMModel = {
    val binData = data.map { case (weight, label, vec) => (weight, label, discretizer.transformToGBMVector[C, B](vec)) }

    val binTest = test.map { case (weight, label, vec) => (weight, label, discretizer.transformToGBMVector[C, B](vec)) }

    boostImpl[C, B, H](binData, binTest, boostConf, validation, discretizer, initialModel)
  }


  /**
    * implementation of GBM, train a GBMModel, with given types
    *
    * @param trainInstances training instances containing (weight, label, bins)
    * @param testInstances  validation instances containing (weight, label, bins)
    * @param boostConf      boosting configuration
    * @param validation     whether to validate on test data
    * @param discretizer    discretizer to convert raw features into bins
    * @param initialModel   inital model
    * @return the model
    */
  def boostImpl[C, B, H](trainInstances: RDD[(H, Array[H], KVVector[C, B])],
                         testInstances: RDD[(H, Array[H], KVVector[C, B])],
                         boostConf: BoostConfig,
                         validation: Boolean,
                         discretizer: Discretizer,
                         initialModel: Option[GBMModel])
                        (implicit cc: ClassTag[C], inc: Integral[C], nec: NumericExt[C],
                         cb: ClassTag[B], inb: Integral[B], neb: NumericExt[B],
                         ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H]): GBMModel = {
    val spark = SparkSession.builder.getOrCreate
    val sc = spark.sparkContext
    Utils.registerKryoClasses(sc)

    val rawBase = boostConf.computeRawBaseScore

    val trainBlocks = InstanceBlock.blockify[C, B, H](trainInstances, boostConf.getBlockSize)
      .setName("Train Blocks")
    trainBlocks.persist(boostConf.getStorageLevel)
    val (numInstances, numBlocks) = trainBlocks.map { block => (block.size.toLong, 1L) }
      .treeReduce(f = {
        case (t1, t2) => (t1._1 + t2._1, t1._2 + t2._2)
      }, depth = boostConf.getAggregationDepth)
    logInfo(s"Train Data: $numInstances instances, $numBlocks blocks")

    var testBlocks = sc.emptyRDD[InstanceBlock[C, B, H]]
    if (validation) {
      testBlocks = InstanceBlock.blockify[C, B, H](testInstances, boostConf.getBlockSize)
        .setName("Test Blocks")
      testBlocks.persist(boostConf.getStorageLevel)
      val (numInstances, numBlocks) = testBlocks.map { block => (block.size.toLong, 1L) }
        .treeReduce(f = {
          case (t1, t2) => (t1._1 + t2._1, t1._2 + t2._2)
        }, depth = boostConf.getAggregationDepth)
      logInfo(s"Test Data: $numInstances instances, $numBlocks blocks")
    }


    val weightsBuff = mutable.ArrayBuffer.empty[H]
    val treesBuff = mutable.ArrayBuffer.empty[TreeModel]
    if (initialModel.isDefined) {
      weightsBuff.appendAll(neh.fromDouble(initialModel.get.weights))
      treesBuff.appendAll(initialModel.get.trees)
    }


    // raw scores and checkpointers
    var trainRawBlocks = computeRawBlocks[C, B, H](trainBlocks, treesBuff.toArray, weightsBuff.toArray, boostConf)
      .setName("Train Raw Blocks (Initial)")
    val trainRawBlockCheckpointer = new Checkpointer[ArrayBlock[H]](sc,
      boostConf.getCheckpointInterval, boostConf.getStorageLevel)
    if (treesBuff.nonEmpty) {
      trainRawBlockCheckpointer.update(trainRawBlocks)
    }

    var testRawBlocks = sc.emptyRDD[ArrayBlock[H]]
    val testRawBlockCheckpointer = new Checkpointer[ArrayBlock[H]](sc,
      boostConf.getCheckpointInterval, boostConf.getStorageLevel)
    if (validation) {
      testRawBlocks = computeRawBlocks[C, B, H](testBlocks, treesBuff.toArray, weightsBuff.toArray, boostConf)
        .setName("Test Raw Blocks (Initial)")
      if (treesBuff.nonEmpty) {
        testRawBlockCheckpointer.update(testRawBlocks)
      }
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
      if (boostConf.getBoostType == Dart) {
        dropTrees(dropped, boostConf, numTrees, dartRng)
        if (dropped.nonEmpty) {
          logInfo(s"$logPrefix ${dropped.size} trees dropped")
        } else {
          logInfo(s"$logPrefix skip drop")
        }
      }


      // build trees
      logInfo(s"$logPrefix start")
      val start = System.nanoTime
      val trees = buildTrees[C, B, H](trainBlocks, trainRawBlocks, weightsBuff.toArray, boostConf, iter, dropped.toSet)
      logInfo(s"$logPrefix finish, duration: ${(System.nanoTime - start) / 1e9} sec")

      if (trees.forall(_.isEmpty)) {
        // fail to build a new tree
        logInfo(s"$logPrefix no more tree built, GBM training finished")
        finished = true

      } else {
        // update base model buffer
        updateTreeBuffer(weightsBuff, treesBuff, trees, dropped.toSet, boostConf)

        // whether to keep the weights of previous trees
        val keepWeights = boostConf.getBoostType != Dart || dropped.isEmpty

        // update train data predictions
        trainRawBlocks = updateRawBlocks[C, B, H](trainBlocks, trainRawBlocks, trees, weightsBuff.toArray, boostConf, keepWeights)
          .setName(s"Train Raw Blocks (Iteration $iter)")
        trainRawBlockCheckpointer.update(trainRawBlocks)


        if (boostConf.getEvalFunc.isEmpty) {
          // materialize predictions
          trainRawBlocks.count()
        }

        // evaluate on train data
        if (boostConf.getEvalFunc.nonEmpty) {
          val trainMetrics = evaluate(trainBlocks, trainRawBlocks, boostConf)
          trainMetricsHistory.append(trainMetrics)
          logInfo(s"$logPrefix train metrics ${trainMetrics.mkString("(", ", ", ")")}")
        }

        if (validation) {
          // update test data predictions
          testRawBlocks = updateRawBlocks[C, B, H](testBlocks, testRawBlocks, trees, weightsBuff.toArray, boostConf, keepWeights)
            .setName(s"Test Raw Blocks (Iteration $iter)")
          testRawBlockCheckpointer.update(testRawBlocks)

          // evaluate on test data
          val testMetrics = evaluate(testBlocks, testRawBlocks, boostConf)
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

    trainBlocks.unpersist(blocking = false)
    trainRawBlockCheckpointer.cleanup()

    if (validation) {
      testBlocks.unpersist(blocking = false)
      testRawBlockCheckpointer.cleanup()
    }

    new GBMModel(boostConf.getObjFunc, discretizer, rawBase,
      treesBuff.toArray, neh.toDouble(weightsBuff.toArray))
  }


  /**
    * append new tree to the model buffer
    *
    * @param weights   weights of trees
    * @param treeBuff  trees
    * @param trees     tree to be appended
    * @param dropped   indices of dropped trees
    * @param boostConf boosting configuration
    */
  def updateTreeBuffer[H](weights: mutable.ArrayBuffer[H],
                          treeBuff: mutable.ArrayBuffer[TreeModel],
                          trees: Array[TreeModel],
                          dropped: Set[Int],
                          boostConf: BoostConfig)
                         (implicit ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H]): Unit = {
    import nuh._

    treeBuff.appendAll(trees)

    boostConf.getBoostType match {
      case GBTree =>
        weights.appendAll(Iterator.fill(trees.length)(neh.fromDouble(boostConf.getStepSize)))

      case Dart if dropped.isEmpty =>
        weights.appendAll(Iterator.fill(trees.length)(nuh.one))

      case Dart if dropped.nonEmpty =>
        require(dropped.size % boostConf.getRawSize == 0)
        // number of droped base models
        val k = dropped.size / boostConf.getRawSize
        val w = neh.fromDouble(1 / (k + boostConf.getStepSize))
        weights.appendAll(Iterator.fill(trees.length)(w))
        val scale = neh.fromDouble(k / (k + boostConf.getStepSize))

        val updateStrBuilder = mutable.ArrayBuilder.make[String]
        dropped.foreach { i =>
          val newWeight = weights(i) * scale
          updateStrBuilder += s"Tree $i: ${weights(i)} -> $newWeight"
          weights(i) = newWeight
        }

        logInfo(s"Weights updated : ${updateStrBuilder.result().mkString("(", ",", ")")}")
    }
  }


  /**
    * drop trees
    *
    * @param dropped   indices of dropped trees
    * @param boostConf boosting configuration
    * @param numTrees  number of trees
    * @param dartRng   random number generator
    */
  def dropTrees(dropped: mutable.Set[Int],
                boostConf: BoostConfig,
                numTrees: Int,
                dartRng: Random): Unit = {
    dropped.clear()

    if (boostConf.getDropSkip < 1 &&
      dartRng.nextDouble < 1 - boostConf.getDropSkip) {

      require(numTrees % boostConf.getRawSize == 0)
      val numBaseModels = numTrees / boostConf.getRawSize

      var k = (numBaseModels * boostConf.getDropRate).ceil.toInt
      k = math.max(k, boostConf.getMinDrop)
      k = math.min(k, boostConf.getMaxDrop)
      k = math.min(k, numBaseModels)

      if (k > 0) {
        dartRng.shuffle(Seq.range(0, numBaseModels)).take(k)
          .flatMap { i => Iterator.range(boostConf.getRawSize * i, boostConf.getRawSize * (i + 1)) }
          .foreach(dropped.add)
      }
    }
  }


  def buildTrees[C, B, H](blocks: RDD[InstanceBlock[C, B, H]],
                          rawBlocks: RDD[ArrayBlock[H]],
                          weights: Array[H],
                          boostConf: BoostConfig,
                          iteration: Int,
                          dropped: Set[Int])
                         (implicit cc: ClassTag[C], inc: Integral[C], nec: NumericExt[C],
                          cb: ClassTag[B], inb: Integral[B], neb: NumericExt[B],
                          ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H]): Array[TreeModel] = {
    val numTrees = boostConf.getBaseModelParallelism * boostConf.getRawSize
    logInfo(s"Iteration $iteration: Starting to create next $numTrees trees")

    val treeIdType = Utils.getTypeByRange(numTrees)
    logInfo(s"DataType of TreeId: $treeIdType")

    val nodeIdType = Utils.getTypeByRange(1 << boostConf.getMaxDepth)
    logInfo(s"DataType of NodeId: $nodeIdType")

    (treeIdType, nodeIdType) match {
      case ("Byte", "Byte") =>
        buildTreesImpl[Byte, Byte, C, B, H](blocks, rawBlocks, weights, boostConf, iteration, dropped)

      case ("Byte", "Short") =>
        buildTreesImpl[Byte, Short, C, B, H](blocks, rawBlocks, weights, boostConf, iteration, dropped)

      case ("Byte", "Int") =>
        buildTreesImpl[Byte, Int, C, B, H](blocks, rawBlocks, weights, boostConf, iteration, dropped)

      case ("Short", "Byte") =>
        buildTreesImpl[Short, Byte, C, B, H](blocks, rawBlocks, weights, boostConf, iteration, dropped)

      case ("Short", "Short") =>
        buildTreesImpl[Short, Short, C, B, H](blocks, rawBlocks, weights, boostConf, iteration, dropped)

      case ("Short", "Int") =>
        buildTreesImpl[Short, Int, C, B, H](blocks, rawBlocks, weights, boostConf, iteration, dropped)

      case ("Int", "Byte") =>
        buildTreesImpl[Int, Byte, C, B, H](blocks, rawBlocks, weights, boostConf, iteration, dropped)

      case ("Int", "Short") =>
        buildTreesImpl[Int, Short, C, B, H](blocks, rawBlocks, weights, boostConf, iteration, dropped)

      case ("Int", "Int") =>
        buildTreesImpl[Int, Int, C, B, H](blocks, rawBlocks, weights, boostConf, iteration, dropped)
    }
  }


  /**
    * build new trees
    *
    * @param blocks    blockified instances containing (weight, label, bins)
    * @param rawBlocks previous raw predictions
    * @param weights   weights of trees
    * @param boostConf boosting configuration
    * @param iteration current iteration
    * @param dropped   indices of trees which are selected to drop during building of current tree
    * @return new trees
    */
  def buildTreesImpl[T, N, C, B, H](blocks: RDD[InstanceBlock[C, B, H]],
                                    rawBlocks: RDD[ArrayBlock[H]],
                                    weights: Array[H],
                                    boostConf: BoostConfig,
                                    iteration: Int,
                                    dropped: Set[Int])
                                   (implicit ct: ClassTag[T], int: Integral[T], net: NumericExt[T],
                                    cn: ClassTag[N], inn: Integral[N], nen: NumericExt[N],
                                    cc: ClassTag[C], inc: Integral[C], nec: NumericExt[C],
                                    cb: ClassTag[B], inb: Integral[B], neb: NumericExt[B],
                                    ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H]): Array[TreeModel] = {
    import nuh._

    val numBaseModels = boostConf.getBaseModelParallelism
    val numTrees = numBaseModels * boostConf.getRawSize

    val rawBase = neh.fromDouble(boostConf.computeRawBaseScore)
    val rawSize = boostConf.getRawSize

    val persisted = mutable.ArrayBuffer.empty[RDD[_]]

    val computeRaw = boostConf.getBoostType match {
      case GBTree =>
        rawSeq: Array[H] => rawSeq

      case Dart if dropped.isEmpty =>
        rawSeq: Array[H] => rawSeq.take(rawSize)

      case Dart if dropped.nonEmpty =>
        rawSeq: Array[H] =>
          val raw = rawBase.clone()
          Iterator.range(rawSize, rawSeq.length)
            .filterNot(i => dropped.contains(i - rawSize))
            .foreach { i => raw(i % rawSize) += rawSeq(i) * weights(i - rawSize) }
          raw
    }

    val computeGrad =
      (weight: H, label: Array[H], rawSeq: Array[H]) => {
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

    val computeGradBlock =
      (block: InstanceBlock[C, B, H], rawBlock: ArrayBlock[H]) => {
        require(block.size == rawBlock.size)

        val iter = block.weightIterator
          .zip(block.labelIterator)
          .zip(rawBlock.iterator)
          .map { case ((weight, label), rawSeq) =>
            computeGrad(weight, label, rawSeq)
          }

        ArrayBlock.build[H](iter)
      }


    val data = if (boostConf.getSubSample == 1) {
      val treeIds = Array.range(0, numBaseModels).map(int.fromInt)

      val gradBlocks = blocks.zip(rawBlocks)
        .map { case (block, rawBlock) => computeGradBlock(block, rawBlock) }

      gradBlocks.setName(s"GradientBlocks (iteration $iteration)")
      gradBlocks.persist(boostConf.getStorageLevel)
      persisted.append(gradBlocks)

      blocks.zip(gradBlocks).flatMap { case (block, gradBlock) =>
        require(block.size == gradBlock.size)
        block.vectorIterator
          .zip(gradBlock.iterator)
          .map { case (bin, grad) => (bin, treeIds, grad) }
      }.setName(s"Gradients with TreeIds (iteration $iteration)")

    } else {

      val computeTreeIds = if (rawSize == 1) {
        // In case prediction values are scalar, then baseIds == treeIds
        baseIds: Array[T] => baseIds
      } else {
        baseIds: Array[T] =>
          baseIds.flatMap { i =>
            val offset = rawSize * int.toInt(i)
            Iterator.range(offset, offset + rawSize).map(int.fromInt)
          }
      }

      val seedOffset = boostConf.getSeed + iteration

      if (boostConf.getSampleBlocks) {

        val emptyValue = (net.emptyArray, ArrayBlock.empty[H])

        val gradBlocks = blocks.zip(rawBlocks)
          .mapPartitionsWithIndex { case (partId, iter) =>
            val sampleRNGs = Array.tabulate(numBaseModels)(i =>
              new XORShiftRandom(seedOffset + partId + i))

            iter.map { case (block, rawBlock) =>
              val baseIds = Array.range(0, numBaseModels).filter { i =>
                sampleRNGs(i).nextDouble < boostConf.getSubSample
              }.map(int.fromInt)

              if (baseIds.nonEmpty) {
                val gradBlock = computeGradBlock(block, rawBlock)
                val treeIds = computeTreeIds(baseIds)
                (treeIds, gradBlock)
              } else {
                emptyValue
              }
            }
          }

        gradBlocks.setName(s"GradientBlocks with TreeIds (iteration $iteration)")
        gradBlocks.persist(boostConf.getStorageLevel)
        persisted.append(gradBlocks)

        blocks.zip(gradBlocks).flatMap { case (block, (treeIds, gradBlock)) =>
          if (treeIds.nonEmpty) {
            require(block.size == gradBlock.size)
            block.vectorIterator
              .zip(gradBlock.iterator)
              .map { case (bin, grad) => (bin, treeIds, grad) }
          } else {
            require(gradBlock.isEmpty)
            Iterator.empty
          }
        }.setName(s"Gradients with TreeIds (iteration $iteration) (Block-Based Sampled)")

      } else {

        val emptyValue = (net.emptyArray, neh.emptyArray)

        val gradBlocks = blocks.zip(rawBlocks)
          .mapPartitionsWithIndex { case (partId, iter) =>
            val sampleRNGs = Array.tabulate(numBaseModels)(i =>
              new XORShiftRandom(seedOffset + partId + i))

            iter.map { case (block, rawBlock) =>
              require(block.size == rawBlock.size)

              val grads = block.weightIterator
                .zip(block.labelIterator)
                .zip(rawBlock.iterator)
                .map { case ((weight, label), rawSeq) =>
                  val baseIds = Array.range(0, numBaseModels).filter { i =>
                    sampleRNGs(i).nextDouble < boostConf.getSubSample
                  }.map(int.fromInt)

                  if (baseIds.nonEmpty) {
                    val treeIds = computeTreeIds(baseIds)
                    val grad = computeGrad(weight, label, rawSeq)
                    (treeIds, grad)
                  } else {
                    emptyValue
                  }
                }.toSeq

              val treeIdBlock = ArrayBlock.build[T](grads.iterator.map(_._1))
              val gradBlock = ArrayBlock.build[H](grads.iterator.map(_._2))
              (treeIdBlock, gradBlock)
            }
          }

        gradBlocks.setName(s"GradientBlocks with TreeIdBlocks (iteration $iteration)")
        gradBlocks.persist(boostConf.getStorageLevel)
        persisted.append(gradBlocks)

        blocks.zip(gradBlocks).flatMap { case (block, (treeIdBlock, gradBlock)) =>
          require(block.size == treeIdBlock.size)
          require(block.size == gradBlock.size)

          block.vectorIterator
            .zip(treeIdBlock.iterator)
            .zip(gradBlock.iterator)
            .map { case ((bin, treeIds), grad) => (bin, treeIds, grad) }
            .filter(_._2.nonEmpty)
        }.setName(s"Gradients with TreeIds (iteration $iteration) (Instance-Based Sampled)")
      }
    }

    val baseConfig = if (boostConf.getColSampleByTree == 1) {
      new BaseConfig(iteration, numTrees, Array.empty)

    } else if (boostConf.getNumCols * boostConf.getColSampleByTree > 32) {
      val rng = new Random(boostConf.getSeed.toInt + iteration)
      val maximum = (Int.MaxValue * boostConf.getColSampleByTree).ceil.toInt
      val selectors: Array[ColumSelector] = Array.range(0, numBaseModels).flatMap { i =>
        val seed = rng.nextInt
        Iterator.fill(boostConf.getRawSize)(HashSelector(maximum, seed))
      }
      new BaseConfig(iteration, numTrees, selectors)

    } else {
      val rng = new Random(boostConf.getSeed.toInt + iteration)
      val numSelected = (boostConf.getNumCols * boostConf.getColSampleByTree).ceil.toInt
      val selectors: Array[ColumSelector] = Array.range(0, numBaseModels).flatMap { i =>
        val selected = rng.shuffle(Seq.range(0, boostConf.getNumCols)).take(numSelected)
        Iterator.fill(boostConf.getRawSize)(SetSelector(selected.toArray.sorted))
      }
      new BaseConfig(iteration, numTrees, selectors)
    }

    logInfo(s"Column Selectors: ${Array.range(0, numTrees).map(baseConfig.getSelector).mkString(",")}")

    val trees = Tree.train[T, N, C, B, H](data, boostConf, baseConfig)

    persisted.foreach(_.unpersist(false))
    persisted.clear()

    trees
  }


  /**
    * compute prediction of instances, containing the final score and the scores of each tree.
    *
    * @param blocks    instances containing (weight, label, bins)
    * @param trees     array of trees
    * @param weights   array of weights
    * @param boostConf boosting configuration
    * @return RDD containing final score (weighted) and the scores of each tree (non-weighted, only for DART)
    */
  def computeRawBlocks[C, B, H](blocks: RDD[InstanceBlock[C, B, H]],
                                trees: Array[TreeModel],
                                weights: Array[H],
                                boostConf: BoostConfig)
                               (implicit cc: ClassTag[C], inc: Integral[C], nec: NumericExt[C],
                                cb: ClassTag[B], inb: Integral[B],
                                ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H]): RDD[ArrayBlock[H]] = {
    import nuh._

    require(trees.length == weights.length)
    require(trees.length % boostConf.getRawSize == 0)

    val rawBase = neh.fromDouble(boostConf.computeRawBaseScore)
    val rawSize = boostConf.getRawSize
    require(rawSize == rawBase.length)

    if (trees.nonEmpty) {
      boostConf.getBoostType match {
        case GBTree =>
          blocks.map { block =>
            val iter = block.vectorIterator.map { bins =>
              val raw = rawBase.clone()
              var j = 0
              while (j < trees.length) {
                val p = neh.fromDouble(trees(j).predict(bins.apply))
                raw(j % rawSize) += p * weights(j)
                j += 1
              }
              raw
            }

            ArrayBlock.build[H](iter)
          }

        case Dart =>
          blocks.map { block =>
            val iter = block.vectorIterator.map { bins =>
              val raw = rawBase.clone() ++ trees.map(tree => neh.fromDouble(tree.predict(bins.apply)))

              var j = 0
              while (j < trees.length) {
                val p = raw(rawSize + j)
                raw(j % rawSize) += p * weights(j)
                j += 1
              }
              raw
            }

            ArrayBlock.build[H](iter)
          }
      }

    } else {
      blocks.mapPartitions { iter =>
        val defaultRawBlock = ArrayBlock.fill[H](rawBase, boostConf.getBlockSize)
        iter.map { block =>
          if (block.size == defaultRawBlock.size) {
            defaultRawBlock
          } else {
            ArrayBlock.build[H](Iterator.range(0, block.size).map(_ => rawBase))
          }
        }
      }
    }
  }


  /**
    * update prediction of instances, containing the final score and the predictions of each tree.
    *
    * @param blocks      instances containing (weight, label, bins)
    * @param rawScores   previous predictions (may be blockfied)
    * @param newTrees    array of trees (new built)
    * @param weights     array of weights (total = old ++ new)
    * @param boostConf   boosting configuration
    * @param keepWeights whether to keep the weights of previous trees
    * @return RDD containing final score and the predictions of each tree
    */
  def updateRawBlocks[C, B, H](blocks: RDD[InstanceBlock[C, B, H]],
                               rawScores: RDD[ArrayBlock[H]],
                               newTrees: Array[TreeModel],
                               weights: Array[H],
                               boostConf: BoostConfig,
                               keepWeights: Boolean)
                              (implicit cc: ClassTag[C], inc: Integral[C], nec: NumericExt[C],
                               cb: ClassTag[B], inb: Integral[B],
                               ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H]): RDD[ArrayBlock[H]] = {
    import nuh._

    require(newTrees.length % boostConf.getRawSize == 0)
    require(weights.length % boostConf.getRawSize == 0)

    val rawSize = boostConf.getRawSize
    val treeOffset = weights.length - newTrees.length

    boostConf.getBoostType match {
      case GBTree =>
        blocks.zip(rawScores).map { case (block, rawBlock) =>
          require(rawBlock.size == block.size)

          val iter = block.vectorIterator
            .zip(rawBlock.iterator)
            .map { case (bins, raw) =>
              require(raw.length == rawSize)
              var j = 0
              while (j < newTrees.length) {
                val p = neh.fromDouble(newTrees(j).predict(bins.apply))
                raw(j % rawSize) += p * weights(treeOffset + j)
                j += 1
              }
              raw
            }

          ArrayBlock.build[H](iter)
        }


      case Dart =>
        val rawBase = neh.fromDouble(boostConf.computeRawBaseScore)
        require(rawSize == rawBase.length)

        blocks.zip(rawScores).map { case (block, rawBlock) =>
          require(rawBlock.size == block.size)

          val iter = block.vectorIterator
            .zip(rawBlock.iterator)
            .map { case (bins, raw) =>
              require(raw.length == rawSize + treeOffset)
              val newRaw = raw ++ newTrees.map(tree => neh.fromDouble(tree.predict(bins.apply)))

              if (keepWeights) {
                var j = 0
                while (j < newTrees.length) {
                  val p = newRaw(rawSize + treeOffset + j)
                  newRaw(j % rawSize) += p * weights(treeOffset + j)
                  j += 1
                }

              } else {
                Array.copy(rawBase, 0, newRaw, 0, rawSize)
                var j = 0
                while (j < weights.length) {
                  val p = newRaw(rawSize + j)
                  newRaw(j % rawSize) += p * weights(j)
                  j += 1
                }
              }

              newRaw
            }

          ArrayBlock.build[H](iter)
        }
    }
  }


  /**
    * Evaluate current model and output the result
    *
    * @param blocks    instances containing (weight, label, bins)
    * @param rawBlocks prediction of instances, containing the final score and the scores of each tree
    * @param boostConf boosting configuration containing the evaluation functions
    * @return Evaluation result with names as the keys and metrics as the values
    */
  def evaluate[H, C, B](blocks: RDD[InstanceBlock[C, B, H]],
                        rawBlocks: RDD[ArrayBlock[H]],
                        boostConf: BoostConfig)
                       (implicit cc: ClassTag[C], inc: Integral[C],
                        cb: ClassTag[B], inb: Integral[B],
                        ch: ClassTag[H], nuh: Numeric[H], neh: NumericExt[H]): Map[String, Double] = {
    if (boostConf.getEvalFunc.isEmpty) {
      return Map.empty
    }

    val rawSize = boostConf.getRawSize

    val scores = blocks.zip(rawBlocks)
      .flatMap { case (block, rawBlock) =>
        require(block.size == rawBlock.size)

        block.weightIterator
          .zip(block.labelIterator)
          .zip(rawBlock.iterator)
          .map { case ((weight, label), rawSeq) =>
            val raw = neh.toDouble(rawSeq.take(rawSize))
            val score = boostConf.getObjFunc.transform(raw)
            (nuh.toDouble(weight), neh.toDouble(label), raw, score)
          }
      }

    val result = mutable.OpenHashMap.empty[String, Double]

    // persist if there are batch evaluators
    if (boostConf.getBatchEvalFunc.nonEmpty) {
      scores.setName(s"Evaluation Dataset (weight, label, raw, score)")
      scores.persist(boostConf.getStorageLevel)
    }

    if (boostConf.getIncEvalFunc.nonEmpty) {
      IncEvalFunc.compute(scores,
        boostConf.getIncEvalFunc, boostConf.getAggregationDepth)
        .foreach { case (name, value) => result.update(name, value) }
    }

    if (boostConf.getBatchEvalFunc.nonEmpty) {
      boostConf.getBatchEvalFunc
        .foreach { eval => result.update(eval.name, eval.compute(scores)) }
      scores.unpersist(blocking = false)
    }

    result.toMap
  }
}


class InstanceBlock[@spec(Byte, Short, Int) C, @spec(Byte, Short, Int) B, @spec(Float, Double) H](val weights: Array[H],
                                                                                                  val labels: Array[H],
                                                                                                  val matrix: KVMatrix[C, B]) extends Serializable {
  def size: Int = matrix.size

  require(labels.length % size == 0)
  if (weights.nonEmpty) {
    require(weights.length == size)
  }

  def iterator()
              (implicit cc: ClassTag[C], cb: ClassTag[B],
               ch: ClassTag[H], nuh: Numeric[H]): Iterator[(H, Array[H], KVVector[C, B])] = {
    weightIterator.zip(labelIterator)
      .zip(vectorIterator)
      .map { case ((weight, label), vec) =>
        (weight, label, vec)
      }
  }

  def weightIterator()
                    (implicit nuh: Numeric[H]): Iterator[H] = {
    if (weights.nonEmpty) {
      weights.iterator
    } else {
      Iterator.fill(size)(nuh.one)
    }
  }

  def labelIterator: Iterator[Array[H]] = {
    val g = labels.length / size
    labels.grouped(g)
  }

  def vectorIterator()
                    (implicit cc: ClassTag[C], cb: ClassTag[B]): Iterator[KVVector[C, B]] = matrix.iterator
}


object InstanceBlock extends Serializable {

  def blockify[C, B, H](instances: Seq[(H, Array[H], KVVector[C, B])])
                       (implicit cc: ClassTag[C], cb: ClassTag[B],
                        ch: ClassTag[H], nuh: Numeric[H]): InstanceBlock[C, B, H] = {
    val weights = instances.map(_._1).toArray
    val labels = instances.flatMap(_._2).toArray
    val matrix = KVMatrix.build[C, B](instances.iterator.map(_._3))

    if (weights.forall(w => nuh.equiv(w, nuh.one))) {
      new InstanceBlock[C, B, H](Array.empty[H], labels, matrix)
    } else {
      new InstanceBlock[C, B, H](weights, labels, matrix)
    }
  }


  def blockify[C, B, H](data: RDD[(H, Array[H], KVVector[C, B])],
                        blockSize: Int)
                       (implicit cc: ClassTag[C], cb: ClassTag[B],
                        ch: ClassTag[H], nuh: Numeric[H]): RDD[InstanceBlock[C, B, H]] = {
    require(blockSize > 0)
    data.mapPartitions {
      _.grouped(blockSize).map(blockify[C, B, H])
    }
  }
}



