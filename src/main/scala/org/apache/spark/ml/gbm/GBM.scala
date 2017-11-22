package org.apache.spark.ml.gbm

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag
import scala.util.Random

import org.apache.hadoop.fs.Path

import org.apache.spark.internal.Logging
import org.apache.spark.ml.linalg._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.storage.StorageLevel


/**
  * RDD-based API for gradient boosting machine
  */
class GBM extends Logging with Serializable {

  /** maximum number of iterations */
  private var maxIter: Int = 20

  def setMaxIter(value: Int): this.type = {
    require(value >= 0)
    maxIter = value
    this
  }

  def getMaxIter: Int = maxIter


  /** maximum tree depth */
  private var maxDepth: Int = 5

  def setMaxDepth(value: Int): this.type = {
    require(value >= 1)
    maxDepth = value
    this
  }

  def getMaxDepth: Int = maxDepth


  /** maximum number of tree leaves */
  private var maxLeaves: Int = 1000

  def setMaxLeaves(value: Int): this.type = {
    require(value >= 1)
    maxLeaves = value
    this
  }

  def getMaxLeaves: Int = maxLeaves


  /** minimum gain for each split */
  private var minGain: Double = 0.0

  def setMinGain(value: Double): this.type = {
    require(value >= 0 && !value.isNaN && !value.isInfinity)
    minGain = value
    this
  }

  def getMinGain: Double = minGain


  /** maximum number of bins for each column */
  private var maxBins: Int = 128

  def setMaxBins(value: Int): this.type = {
    require(value >= 4)
    maxBins = value
    this
  }

  def getMaxBins: Int = maxBins


  /** base score for global bias */
  private var baseScore: Double = 0.0

  def setBaseScore(value: Double): this.type = {
    require(!value.isNaN && !value.isInfinity)
    baseScore = value
    this
  }

  def getBaseScore: Double = baseScore


  /** minimum sum of hess for each node */
  private var minNodeHess: Double = 0.0

  def setMinNodeHess(value: Double): this.type = {
    require(value >= 0 && !value.isNaN && !value.isInfinity)
    minNodeHess = value
    this
  }

  def getMinNodeHess: Double = minNodeHess


  /** learning rate */
  private var stepSize: Double = 0.1

  def setStepSize(value: Double): this.type = {
    require(value > 0 && !value.isNaN && !value.isInfinity)
    stepSize = value
    this
  }

  def getStepSize: Double = stepSize


  /** L1 regularization term on weights */
  private var regAlpha: Double = 0.0

  def setRegAlpha(value: Double): this.type = {
    require(value >= 0 && !value.isNaN && !value.isInfinity)
    regAlpha = value
    this
  }

  def getRegAlpha: Double = regAlpha


  /** L2 regularization term on weights */
  private var regLambda: Double = 1.0

  def setRegLambda(value: Double): this.type = {
    require(value >= 0 && !value.isNaN && !value.isInfinity)
    regLambda = value
    this
  }

  def getRegLambda: Double = regLambda


  /** objective function */
  private var objectiveFunc: ObjFunc = new SquareObj

  def setObjectiveFunc(value: ObjFunc): this.type = {
    require(value != null)
    objectiveFunc = value
    this
  }

  def getObjectiveFunc: ObjFunc = objectiveFunc


  /** evaluation functions */
  private var evaluateFunc: Array[EvalFunc] = Array(new MAEEval, new MSEEval, new RMSEEval)

  def setEvaluateFunc(value: Array[EvalFunc]): this.type = {
    require(value.forall(e => e.isInstanceOf[IncrementalEvalFunc] || e.isInstanceOf[BatchEvalFunc]))
    require(value.map(_.name).distinct.length == value.length)
    evaluateFunc = value
    this
  }

  def getEvaluateFunc: Array[EvalFunc] = evaluateFunc


  /** callback functions */
  private var callbackFunc: Array[CallbackFunc] = Array(new EarlyStopFunc)

  def setCallbackFunc(value: Array[CallbackFunc]): this.type = {
    require(value.map(_.name).distinct.length == value.length)
    callbackFunc = value
    this
  }

  def getCallbackFunc: Array[CallbackFunc] = callbackFunc


  /** indices of categorical columns */
  private var catCols: Set[Int] = Set.empty

  def setCatCols(value: Set[Int]): this.type = {
    catCols = value
    this
  }

  def getCatCols: Set[Int] = catCols


  /** indices of ranking columns */
  private var rankCols: Set[Int] = Set.empty

  def setRankCols(value: Set[Int]): this.type = {
    rankCols = value
    this
  }

  def getRankCols: Set[Int] = rankCols


  /** subsample ratio of the training instance */
  private var subSample: Double = 1.0

  def setSubSample(value: Double): this.type = {
    require(value > 0 && value <= 1 && !value.isNaN && !value.isInfinity)
    subSample = value
    this
  }

  def getSubSample: Double = subSample


  /** subsample ratio of columns when constructing each tree */
  private var colSampleByTree: Double = 1.0

  def setColSampleByTree(value: Double): this.type = {
    require(value > 0 && value <= 1 && !value.isNaN && !value.isInfinity)
    colSampleByTree = value
    this
  }

  def getColSampleByTree: Double = colSampleByTree


  /** subsample ratio of columns when constructing each level */
  private var colSampleByLevel: Double = 1.0

  def setColSampleByLevel(value: Double): this.type = {
    require(value > 0 && value <= 1 && !value.isNaN && !value.isInfinity)
    colSampleByLevel = value
    this
  }

  def getColSampleByLevel: Double = colSampleByLevel


  /** checkpoint interval */
  private var checkpointInterval: Int = 10

  def setCheckpointInterval(value: Int): this.type = {
    require(value == -1 || value > 0)
    checkpointInterval = value
    this
  }

  def getCheckpointInterval: Int = checkpointInterval


  /** storage level */
  private var storageLevel: StorageLevel = StorageLevel.MEMORY_AND_DISK

  def setStorageLevel(value: StorageLevel): this.type = {
    require(value != StorageLevel.NONE)
    storageLevel = value
    this
  }

  def getStorageLevel: StorageLevel = storageLevel


  /** depth for treeAggregate */
  private var aggregationDepth: Int = 2

  def setAggregationDepth(value: Int): this.type = {
    require(value >= 2)
    aggregationDepth = value
    this
  }

  def getAggregationDepth: Int = aggregationDepth


  /** random number seed */
  private var seed: Long = -1L

  def setSeed(value: Long): this.type = {
    seed = value
    this
  }

  def getSeed: Long = seed


  /** boosting type */
  private var boostType: String = GBM.GBTree

  def setBoostType(value: String): this.type = {
    require(value == GBM.GBTree || value == GBM.Dart)
    boostType = value
    this
  }

  def getBoostType: String = boostType


  /** dropout rate */
  private var dropRate: Double = 0.0

  def setDropRate(value: Double): this.type = {
    require(value >= 0 && value <= 1 && !value.isNaN && !value.isInfinity)
    dropRate = value
    this
  }

  def getDropRate: Double = dropRate


  /** probability of skipping drop */
  private var dropSkip: Double = 0.5

  def setDropSkip(value: Double): this.type = {
    require(value >= 0 && value <= 1 && !value.isNaN && !value.isInfinity)
    dropSkip = value
    this
  }

  def getDropSkip: Double = dropSkip

  /** minimum number of dropped trees in each iteration */
  private var minDrop: Int = 0

  def setMinDrop(value: Int): this.type = {
    require(value >= 0)
    minDrop = value
    this
  }

  def getMinDrop: Int = minDrop


  /** maximum number of dropped trees in each iteration */
  private var maxDrop: Int = 50

  def setMaxDrop(value: Int): this.type = {
    require(value >= 0)
    maxDrop = value
    this
  }

  def getMaxDrop: Int = maxDrop


  /** initial model */
  private var initialModel: Option[GBMModel] = None

  def setInitialModel(value: Option[GBMModel]): this.type = {
    initialModel = value
    this
  }

  def getInitialModel: Option[GBMModel] = initialModel


  /** the maximum number of non-zero histogram bins to search split for categorical columns by brute force */
  private var maxBruteBins: Int = 10

  def setMaxBruteBins(value: Int): this.type = {
    require(value >= 0)
    maxBruteBins = value
    this
  }

  def getMaxBruteBins: Int = maxBruteBins

  /** method to discretize numerical columns */
  private var numericalBinType: String = "width"

  def setNumericalBinType(value: String): this.type = {
    require(value == "width" || value == "depth")
    numericalBinType = value
    this
  }

  def getNumericalBinType: String = numericalBinType


  /** training */
  def fit(data: RDD[(Double, Double, Vector)]): GBMModel = {
    fit(data, None)
  }


  /** training with validation */
  def fit(data: RDD[(Double, Double, Vector)],
          test: RDD[(Double, Double, Vector)]): GBMModel = {
    fit(data, Some(test))
  }


  /** training with validation if any */
  private[ml] def fit(data: RDD[(Double, Double, Vector)],
                      test: Option[RDD[(Double, Double, Vector)]]): GBMModel = {
    if (boostType == GBM.Dart) {
      require(maxDrop >= minDrop)
    }

    val sc = data.sparkContext

    val numCols = data.first._3.size
    require(numCols > 0)

    val validation = test.isDefined

    val discretizer = if (initialModel.isDefined) {
      require(numCols == initialModel.get.discretizer.colDiscretizers.length)
      logWarning(s"Discretizer is already provided by the initial model, related params (catCols,rankCols,quantileBins) will be ignored")
      initialModel.get.discretizer
    } else {
      if (catCols.nonEmpty) {
        require(catCols.min >= 0 && catCols.max < numCols)
      }
      if (rankCols.nonEmpty) {
        require(rankCols.min >= 0 && rankCols.max < numCols)
      }
      require(catCols.intersect(rankCols).isEmpty)
      Discretizer.fit(data.map(_._3), numCols, catCols, rankCols, maxBins, numericalBinType, aggregationDepth)
    }

    logWarning(s"Number of bins: ${discretizer.numBins.mkString("(", ",", ")")}")

    if (initialModel.isDefined &&
      initialModel.get.baseScore != baseScore) {
      logWarning(s"Base score not equal to the value in initial model")
    }

    val boostConfig = new BoostConfig
    boostConfig.setMaxIter(maxIter)
      .setMaxDepth(maxDepth)
      .setMaxLeaves(maxLeaves)
      .setNumCols(numCols)
      .setBaseScore(baseScore)
      .setMinGain(minGain)
      .setMinNodeHess(minNodeHess)
      .setStepSize(stepSize)
      .setRegAlpha(regAlpha)
      .setRegLambda(regLambda)
      .setObjectiveFunc(objectiveFunc)
      .setEvaluateFunc(evaluateFunc)
      .setCallbackFunc(callbackFunc)
      .setCatCols(catCols)
      .setRankCols(rankCols)
      .setSubSample(subSample)
      .setColSampleByTree(colSampleByTree)
      .setColSampleByLevel(colSampleByLevel)
      .setCheckpointInterval(checkpointInterval)
      .setStorageLevel(storageLevel)
      .setBoostType(boostType)
      .setDropRate(dropRate)
      .setDropSkip(dropSkip)
      .setMinDrop(minDrop)
      .setMaxDrop(maxDrop)
      .setAggregationDepth(aggregationDepth)
      .setMaxBruteBins(maxBruteBins)
      .setSeed(seed)

    val trainRDD = data.map { case (weight, label, features) =>
      (weight, label, discretizer.transform(features))
    }

    val testRDD = if (validation) {
      test.get.map { case (weight, label, features) =>
        (weight, label, discretizer.transform(features))
      }
    } else {
      sc.emptyRDD[(Double, Double, Array[Int])]
    }

    val maxBinIndex = discretizer.numBins.max - 1

    if (maxBinIndex <= Byte.MaxValue) {
      logWarning(s"Data storage for bins: Array[Byte]")
      val byteTrainRDD = trainRDD.map { case (weight, label, bins) =>
        (weight, label, bins.map(_.toByte))
      }
      val byteTestRDD = testRDD.map { case (weight, label, bins) =>
        (weight, label, bins.map(_.toByte))
      }
      GBM.boost[Byte](byteTrainRDD, byteTestRDD, boostConfig, validation, discretizer, initialModel)

    } else if (maxBinIndex <= Short.MaxValue) {
      logWarning(s"Data storage for bins: Array[Short]")
      val shortTrainRDD = trainRDD.map { case (weight, label, bins) =>
        (weight, label, bins.map(_.toShort))
      }
      val shortTestRDD = testRDD.map { case (weight, label, bins) =>
        (weight, label, bins.map(_.toShort))
      }
      GBM.boost[Short](shortTrainRDD, shortTestRDD, boostConfig, validation, discretizer, initialModel)

    } else {
      logWarning(s"Data storage for bins: Array[Int]")
      GBM.boost[Int](trainRDD, testRDD, boostConfig, validation, discretizer, initialModel)
    }
  }
}


private[gbm] object GBM extends Logging {

  val GBTree = "gbtree"
  val Dart = "dart"

  /**
    * Implementation of GBM, train a GBMModel
    *
    * @param data         training instances containing (weight, label, bins)
    * @param test         validation instances containing (weight, label, bins)
    * @param boostConfig  boosting configuration
    * @param validation   whether to validate on test data
    * @param discretizer  discretizer to convert raw features into bins
    * @param initialModel inital model
    * @tparam B
    * @return the model
    */
  def boost[B: Integral : ClassTag](data: RDD[(Double, Double, Array[B])],
                                    test: RDD[(Double, Double, Array[B])],
                                    boostConfig: BoostConfig,
                                    validation: Boolean,
                                    discretizer: Discretizer,
                                    initialModel: Option[GBMModel]): GBMModel = {
    val sc = data.sparkContext

    data.persist(boostConfig.getStorageLevel)
    logWarning(s"${data.count} instances in train data")

    if (validation) {
      test.persist(boostConfig.getStorageLevel)
      logWarning(s"${test.count} instances in test data")
    }

    val weights = ArrayBuffer[Double]()
    val trees = ArrayBuffer[TreeModel]()

    if (initialModel.isDefined) {
      weights.appendAll(initialModel.get.weights)
      trees.appendAll(initialModel.get.trees)
    }

    val trainPredsCheckpointer = new Checkpointer[(Double, Array[Double])](sc,
      boostConfig.getCheckpointInterval, boostConfig.getStorageLevel)
    var trainPreds = if (initialModel.isDefined) {
      computePrediction(data, trees.zip(weights).toArray, boostConfig.getBaseScore)
    } else {
      data.map(_ => (boostConfig.getBaseScore, Array.emptyDoubleArray))
    }
    trainPredsCheckpointer.update(trainPreds)

    val testPredsCheckpointer = new Checkpointer[(Double, Array[Double])](sc,
      boostConfig.getCheckpointInterval, boostConfig.getStorageLevel)
    var testPreds = sc.emptyRDD[(Double, Array[Double])]
    if (validation && boostConfig.getEvaluateFunc.nonEmpty) {
      testPreds = if (initialModel.isDefined) {
        computePrediction(test, trees.zip(weights).toArray, boostConfig.getBaseScore)
      } else {
        test.map(_ => (boostConfig.getBaseScore, Array.emptyDoubleArray))
      }
      testPredsCheckpointer.update(testPreds)
    }

    /** metrics history recoder */
    val trainMetricsHistory = ArrayBuffer[Map[String, Double]]()
    val testMetricsHistory = ArrayBuffer[Map[String, Double]]()

    /** random number generator for drop out */
    val dartRand = new Random(boostConfig.getSeed)
    val dropped = mutable.Set[Int]()

    /** random number generator for column sampling */
    val colSampleRand = new Random(boostConfig.getSeed)

    var iter = 0
    var finished = false

    while (!finished) {
      /** if initial model is provided, round will not equal to iter */
      val round = trees.length
      val logPrefix = s"Iter $iter: Tree $round:"

      /** drop out */
      if (boostConfig.getBoostType == Dart) {
        dropTrees(dropped, boostConfig, round, dartRand)
        if (dropped.nonEmpty) {
          logWarning(s"$logPrefix ${dropped.size} trees dropped")
        } else {
          logWarning(s"$logPrefix skip drop")
        }
      }

      /** build tree */
      logWarning(s"$logPrefix start")
      val start = System.nanoTime
      val tree = buildTree(data, trainPreds, weights.toArray, boostConfig, iter, round, dropped.toSet, colSampleRand)
      logWarning(s"$logPrefix finish, duration ${(System.nanoTime - start) / 1e9} seconds")

      if (tree.isEmpty) {
        /** fail to build a new tree */
        logWarning(s"$logPrefix no more tree built, GBM training finished")
        finished = true

      } else {
        /** update base models */
        trees.append(tree.get)
        var keepWeights = true
        boostConfig.getBoostType match {
          case GBTree =>
            weights.append(boostConfig.getStepSize)

          case Dart if dropped.isEmpty =>
            weights.append(1.0)

          case Dart if dropped.nonEmpty =>
            val k = dropped.size
            weights.append(1 / (k + boostConfig.getStepSize))
            val scale = k / (k + boostConfig.getStepSize)
            dropped.foreach { i =>
              weights(i) *= scale
            }
            keepWeights = false
        }

        /** update train data predictions */
        trainPreds = updatePrediction(data, trainPreds, weights.toArray, tree.get, boostConfig.getBaseScore, keepWeights)
        trainPredsCheckpointer.update(trainPreds)

        if (boostConfig.getEvaluateFunc.isEmpty) {
          /** materialize predictions */
          trainPreds.count()
        }

        /** evaluate on train data */
        if (boostConfig.getEvaluateFunc.nonEmpty) {
          val trainMetrics = evaluate(data, trainPreds, boostConfig)
          trainMetricsHistory.append(trainMetrics)
          logWarning(s"$logPrefix train metrics ${trainMetrics.mkString("(", ", ", ")")}")
        }

        if (validation && boostConfig.getEvaluateFunc.nonEmpty) {
          /** update test data predictions */
          testPreds = updatePrediction(test, testPreds, weights.toArray, tree.get, boostConfig.getBaseScore, keepWeights)
          testPredsCheckpointer.update(testPreds)

          /** evaluate on test data */
          val testMetrics = evaluate(test, testPreds, boostConfig)
          testMetricsHistory.append(testMetrics)
          logWarning(s"$logPrefix test metrics ${testMetrics.mkString("(", ", ", ")")}")
        }

        /** callback */
        if (boostConfig.getCallbackFunc.nonEmpty) {
          val snapshot = new GBMModel(discretizer, boostConfig.getBaseScore, trees.toArray, weights.toArray)

          /** callback can update boosting configuration */
          boostConfig.getCallbackFunc.foreach { callback =>
            if (callback.compute(boostConfig, snapshot, trainMetricsHistory.toArray, testMetricsHistory.toArray)) {
              finished = true
              logWarning(s"$logPrefix callback ${callback.name} stop training")
            }
          }
        }
      }

      iter += 1
      if (iter >= boostConfig.getMaxIter) {
        finished = true
      }
    }

    if (iter >= boostConfig.getMaxIter) {
      logWarning(s"maxIter=${boostConfig.getMaxIter} reached, GBM training finished")
    }

    data.unpersist(blocking = false)
    trainPredsCheckpointer.unpersistDataSet()
    trainPredsCheckpointer.deleteAllCheckpoints()

    if (validation) {
      test.unpersist(blocking = false)
      testPredsCheckpointer.unpersistDataSet()
      testPredsCheckpointer.deleteAllCheckpoints()
    }

    new GBMModel(discretizer, boostConfig.getBaseScore, trees.toArray, weights.toArray)
  }

  /**
    * Drop trees
    *
    * @param dropped     indices of dropped trees
    * @param boostConfig boosting configuration
    * @param numTrees    number of trees
    * @param dartRand    random number generator
    */
  def dropTrees(dropped: mutable.Set[Int],
                boostConfig: BoostConfig,
                numTrees: Int,
                dartRand: Random): Unit = {
    dropped.clear()

    if (boostConfig.getDropSkip < 1 &&
      dartRand.nextDouble < 1 - boostConfig.getDropSkip) {
      var k = (numTrees * boostConfig.getDropRate).ceil.toInt
      k = math.max(k, boostConfig.getMinDrop)
      k = math.min(k, boostConfig.getMaxDrop)
      k = math.min(k, numTrees)

      if (k > 0) {
        dartRand.shuffle(Seq.range(0, numTrees))
          .take(k).foreach(dropped.add)
      }
    }
  }

  /**
    * Build a new tree
    *
    * @param instances     instances containing (weight, label, bins)
    * @param preds         previous predictions
    * @param weights       weights of trees
    * @param boostConfig   boosting configuration
    * @param iteration     current iteration
    * @param treeIndex     current round
    * @param dropped       indices of columns which are selected to drop during building of current tree
    * @param colSampleRand random number generator for column sampling
    * @tparam B
    * @return a new tree if possible
    */
  def buildTree[B: Integral : ClassTag](instances: RDD[(Double, Double, Array[B])],
                                        preds: RDD[(Double, Array[Double])],
                                        weights: Array[Double],
                                        boostConfig: BoostConfig,
                                        iteration: Int,
                                        treeIndex: Int,
                                        dropped: Set[Int],
                                        colSampleRand: Random): Option[TreeModel] = {
    val rowSampled = if (boostConfig.getSubSample == 1) {
      instances.zip(preds)
    } else {
      instances.zip(preds).sample(false, boostConfig.getSubSample, boostConfig.getSeed + treeIndex)
    }

    /** selected columns */
    val cols = if (boostConfig.getColSampleByTree == 1) {
      Array.range(0, boostConfig.getNumCols)
    } else {
      val numCols = (boostConfig.getNumCols * boostConfig.getColSampleByTree).ceil.toInt
      colSampleRand.shuffle(Seq.range(0, boostConfig.getNumCols))
        .take(numCols).toArray.sorted
    }

    /** indices of categorical columns in the selected column subset */
    val catCols = cols.zipWithIndex
      .filter { case (col, _) =>
        boostConfig.getCatCols.contains(col)
      }.map(_._2).toSet

    val colSampled = boostConfig.getBoostType match {
      case GBTree =>
        rowSampled.map { case ((weight, label, bins), (score, _)) =>
          val (grad, hess) = boostConfig.getObjectiveFunc.compute(label, score)
          (grad * weight, hess * weight, cols.map(bins))
        }

      case Dart if dropped.isEmpty =>
        rowSampled.map { case ((weight, label, bins), (score, _)) =>
          val (grad, hess) = boostConfig.getObjectiveFunc.compute(label, score)
          (grad * weight, hess * weight, cols.map(bins))
        }

      case Dart if dropped.nonEmpty =>
        rowSampled.map { case ((weight, label, bins), (_, pred)) =>
          var score = boostConfig.getBaseScore
          pred.zip(weights).zipWithIndex.foreach { case ((p, w), i) =>
            if (!dropped.contains(i)) {
              score += p * w
            }
          }
          val (grad, hess) = boostConfig.getObjectiveFunc.compute(label, score)
          (grad * weight, hess * weight, cols.map(bins))
        }
    }

    val treeConfig = new TreeConfig(iteration, treeIndex, catCols, cols)
    Tree.train[B](colSampled, boostConfig, treeConfig)
  }

  /**
    * Compute prediction of instances, containing the final score and the scores of each tree.
    *
    * @param instances instances containing (weight, label, bins)
    * @param trees     array of trees with weights
    * @param baseScore global bias
    * @tparam B
    * @return RDD containing final score and the scores of each tree
    */
  def computePrediction[B: Integral](instances: RDD[(Double, Double, Array[B])],
                                     trees: Array[(TreeModel, Double)],
                                     baseScore: Double): RDD[(Double, Array[Double])] = {
    instances.map { case (_, _, bins) =>
      var score = baseScore
      val pred = Array.ofDim[Double](trees.length)

      var i = 0
      while (i < trees.length) {
        val (tree, w) = trees(i)
        pred(i) = tree.predict(bins)
        score += pred(i) * w
        i += 1
      }

      (score, pred)
    }
  }

  /**
    * Update prediction of instances, containing the final score and the predictions of each tree.
    *
    * @param instances   instances containing (weight, label, bins)
    * @param preds       previous predictions
    * @param weights     weights of each tree
    * @param tree        the last tree model
    * @param baseScore   global bias
    * @param keepWeights whether to keep the weights of previous trees
    * @tparam B
    * @return RDD containing final score and the predictions of each tree
    */
  def updatePrediction[B: Integral](instances: RDD[(Double, Double, Array[B])],
                                    preds: RDD[(Double, Array[Double])],
                                    weights: Array[Double],
                                    tree: TreeModel,
                                    baseScore: Double,
                                    keepWeights: Boolean): RDD[(Double, Array[Double])] = {

    if (keepWeights) {
      instances.zip(preds).map { case ((_, _, bins), (score, pred)) =>
        val newPred = pred :+ tree.predict(bins)
        require(newPred.length == weights.length)
        val newScore = score + newPred.last * weights.last
        (newScore, newPred)
      }

    } else {
      instances.zip(preds).map { case ((_, _, bins), (_, pred)) =>
        val newPred = pred :+ tree.predict(bins)
        require(newPred.length == weights.length)
        var newScore = baseScore
        var i = 0
        while (i < newPred.length) {
          newScore += newPred(i) * weights(i)
          i += 1
        }
        (newScore, newPred)
      }
    }
  }


  /**
    * Evaluate current model and output the result
    *
    * @param instances   instances containing (weight, label, bins)
    * @param preds       prediction of instances, containing the final score and the scores of each tree
    * @param boostConfig boosting configuration containing the evaluation functions
    * @tparam B
    * @return Evaluation result with names as the keys and metrics as the values
    */
  def evaluate[B: Integral](instances: RDD[(Double, Double, Array[B])],
                            preds: RDD[(Double, Array[Double])],
                            boostConfig: BoostConfig): Map[String, Double] = {

    if (boostConfig.getEvaluateFunc.isEmpty) {
      return Map.empty
    }

    val scores = instances.zip(preds).map {
      case ((weight, label, _), (score, _)) =>
        (weight, label, score)
    }

    val result = mutable.Map[String, Double]()

    /** persist if there are batch evaluators */
    if (boostConfig.getBatchEvaluateFunc.nonEmpty) {
      scores.persist(boostConfig.getStorageLevel)
    }

    if (boostConfig.getIncrementalEvaluateFunc.nonEmpty) {
      val values = IncrementalEvalFunc.compute(scores,
        boostConfig.getIncrementalEvaluateFunc, boostConfig.getAggregationDepth)

      boostConfig.getIncrementalEvaluateFunc.map(_.name)
        .zip(values).foreach {
        case (name, value) =>
          result.update(name, value)
      }
    }

    if (boostConfig.getBatchEvaluateFunc.nonEmpty) {
      boostConfig.getBatchEvaluateFunc.foreach { eval =>
        val value = eval.compute(scores)
        result.update(eval.name, value)
      }
      scores.unpersist(blocking = false)
    }

    result.toMap
  }
}


/**
  * Model of GBM
  *
  * @param discretizer discretizer to convert raw features into bins
  * @param baseScore   global bias
  * @param trees       array of trees
  * @param weights     weights of trees
  */
class GBMModel(val discretizer: Discretizer,
               val baseScore: Double,
               val trees: Array[TreeModel],
               val weights: Array[Double]) extends Serializable {
  require(trees.length == weights.length)
  require(!baseScore.isNaN && !baseScore.isInfinity)
  require(weights.forall(w => !w.isNaN || !w.isInfinity))

  /** feature importance of whole trees */
  lazy val importance: Vector = computeImportance(numTrees)

  def numCols: Int = discretizer.colDiscretizers.length

  def numTrees: Int = trees.length

  def numLeaves: Array[Long] = trees.map(_.numLeaves)

  def numNodes: Array[Long] = trees.map(_.numNodes)

  def depths: Array[Int] = trees.map(_.depth)

  /** feature importance of the first trees */
  def computeImportance(firstTrees: Int): Vector = {
    require(firstTrees > 0 && firstTrees <= trees.length)

    val gains = Array.ofDim[Double](numCols)

    var i = 0
    while (i < firstTrees) {
      trees(i).computeImportance.foreach {
        case (index, gain) =>
          gains(index) += gain * weights(i)
      }
      i += 1
    }

    val sum = gains.sum
    Vectors.dense(gains.map(_ / sum)).compressed
  }

  def predict(features: Vector): Double = {
    predict(features, numTrees)
  }

  def predict(features: Vector,
              firstTrees: Int): Double = {
    require(features.size == numCols)
    require(firstTrees > 0 && firstTrees <= numTrees)

    val bins = discretizer.transform(features)
    var score = baseScore
    var i = 0
    while (i < firstTrees) {
      score += trees(i).predict(bins) * weights(i)
      i += 1
    }
    score
  }

  def leaf(features: Vector): Vector = {
    leaf(features, false)
  }

  def leaf(features: Vector,
           oneHot: Boolean): Vector = {
    leaf(features, oneHot, trees.length)
  }

  /** leaf transformation with first trees, if oneHot is enable, transform input into a sparse one-hot encoded vector */
  def leaf(features: Vector,
           oneHot: Boolean,
           firstTrees: Int): Vector = {
    require(features.size == numCols)
    require(firstTrees > 0 && firstTrees <= numTrees)

    val bins = discretizer.transform(features)

    if (oneHot) {
      val indices = Array.ofDim[Int](firstTrees)

      var step = 0
      var i = 0
      while (i < firstTrees) {
        val index = trees(i).index(bins)
        indices(i) = step + index.toInt
        step += numLeaves(i).toInt
        i += 1
      }

      val values = Array.fill(firstTrees)(1.0)
      Vectors.sparse(step, indices, values)

    } else {

      val indices = Array.ofDim[Double](firstTrees)
      var i = 0
      while (i < firstTrees) {
        indices(i) = trees(i).index(bins).toDouble
        i += 1
      }
      Vectors.dense(indices)
    }
  }
}


object GBMModel {

  /** save GBMModel to a path */
  def save(model: GBMModel,
           path: String): Unit = {
    val (discretizerDF, weightsDF, treesDF, extraDF) = toDF(model)

    val discretizerPath = new Path(path, "discretizer").toString
    discretizerDF.write.parquet(discretizerPath)

    val weightsPath = new Path(path, "weights").toString
    weightsDF.write.parquet(weightsPath)

    val treesPath = new Path(path, "trees").toString
    treesDF.write.parquet(treesPath)

    val extraPath = new Path(path, "extra").toString
    extraDF.write.parquet(extraPath)
  }

  /** load GBMModel from a path */
  def load(path: String): GBMModel = {
    val spark = SparkSession.builder.getOrCreate()

    val discretizerPath = new Path(path, "discretizer").toString
    val discretizerDF = spark.read.parquet(discretizerPath)

    val weightsPath = new Path(path, "weights").toString
    val weightsDF = spark.read.parquet(weightsPath)

    val treesPath = new Path(path, "trees").toString
    val treesDF = spark.read.parquet(treesPath)

    val extraPath = new Path(path, "extra").toString
    val extraDF = spark.read.parquet(extraPath)

    fromDF(discretizerDF, weightsDF, treesDF, extraDF)
  }

  /** helper function to convert GBMModel to dataframes */
  private[gbm] def toDF(model: GBMModel): (DataFrame, DataFrame, DataFrame, DataFrame) = {
    val spark = SparkSession.builder.getOrCreate()

    val discretizerDF = Discretizer.toDF(model.discretizer)

    val weightsDatum = model.weights.zipWithIndex
    val weightsDF = spark.createDataFrame(weightsDatum).toDF("weight", "treeIndex")

    val treesDatum = model.trees.zipWithIndex.flatMap {
      case (tree, index) =>
        val (nodeData, _) = NodeData.createData(tree.root, 0)
        nodeData.map((_, index))
    }
    val treesDF = spark.createDataFrame(treesDatum).toDF("node", "treeIndex")

    val extraDF = spark.createDataFrame(Seq(
      ("baseScore", model.baseScore.toString))).toDF("key", "value")

    (discretizerDF, weightsDF, treesDF, extraDF)
  }

  /** helper function to convert dataframes back to GBMModel */
  private[gbm] def fromDF(discretizerDF: DataFrame,
                          weightsDF: DataFrame,
                          treesDF: DataFrame,
                          extraDF: DataFrame): GBMModel = {
    val spark = discretizerDF.sparkSession
    import spark.implicits._

    val discretizer = Discretizer.fromDF(discretizerDF)

    val (indices, weights) =
      weightsDF.select("treeIndex", "weight").rdd
        .map { row =>
          val index = row.getInt(0)
          val weight = row.getDouble(1)
          (index, weight)
        }.collect().sortBy(_._1).unzip

    require(indices.length == indices.distinct.length)
    require(indices.length == indices.max + 1)

    val (indices2, trees) =
      treesDF.select("treeIndex", "node").as[(Int, NodeData)].rdd
        .groupByKey().map { case (index, nodes) =>
        val root = NodeData.createNode(nodes.toArray)
        (index, new TreeModel(root))
      }.collect().sortBy(_._1).unzip

    require(indices.length == indices2.length)
    require(indices2.length == indices2.distinct.length)
    require(indices2.length == indices2.max + 1)

    var baseScore = Double.NaN
    extraDF.select("key", "value").collect()
      .foreach { row =>
        val key = row.getString(0)
        val value = row.getString(1)

        key match {
          case "baseScore" =>
            baseScore = value.toDouble
        }
      }
    require(!baseScore.isNaN && !baseScore.isInfinity)

    new GBMModel(discretizer, baseScore, trees, weights)
  }
}
