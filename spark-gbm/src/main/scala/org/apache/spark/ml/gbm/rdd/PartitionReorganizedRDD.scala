package org.apache.spark.ml.gbm.rdd

import scala.reflect.ClassTag

import org.apache.spark._
import org.apache.spark.rdd.RDD


/**
  * Partition of `PartitionReorganizedRDD`
  */
private[gbm] class PartitionReorganizedRDDPartition[T](val index: Int,
                                                       val prevs: Array[Partition]) extends Partition


/**
  * Reorganize and concatenate current partitions to form new partitions.
  * E.g `partIds` = Array(Array(0,0), Array(3,4)), will create a new RDD with 2 partitions,
  * the first one is two copies of current part0, and the second one is composed of current part3 and part4.
  * @param partIds indicate how to form new partitions
  */
private[gbm] class PartitionReorganizedRDD[T: ClassTag](@transient val parent: RDD[T],
                                                        val partIds: Array[Array[Int]]) extends RDD[T](parent) {
  require(partIds.iterator.flatten
    .forall(p => p >= 0 && p < parent.getNumPartitions))

  override def compute(split: Partition, context: TaskContext): Iterator[T] = {
    val part = split.asInstanceOf[PartitionReorganizedRDDPartition[T]]
    part.prevs.iterator.flatMap { prev =>
      firstParent[T].iterator(prev, context)
    }
  }

  override protected def getPartitions: Array[Partition] = {
    partIds.zipWithIndex.map { case (partId, i) =>
      val prevs = partId.map { pid => firstParent[T].partitions(pid) }
      new PartitionReorganizedRDDPartition(i, prevs)
    }
  }

  override def getPreferredLocations(split: Partition): Seq[String] = {
    val prefs = split.asInstanceOf[PartitionReorganizedRDDPartition[T]].prevs
      .map { prev => firstParent[T].preferredLocations(prev) }

    val intersect = prefs.reduce((p1, p2) => p1.intersect(p2))

    if (intersect.nonEmpty) {
      intersect
    } else {
      prefs.flatten.distinct
    }
  }

  override def getDependencies: Seq[Dependency[_]] = Seq(
    new NarrowDependency(parent) {
      def getParents(id: Int): Seq[Int] = partIds(id)
    })
}
