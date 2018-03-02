/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.lineage.rdd

import org.apache.spark._
import org.apache.spark.lineage.util.LongIntLongByteBuffer
import org.apache.spark.lineage.{LineageContext, LineageManager}
import org.apache.spark.rdd.RDD
import org.apache.spark.util.PackIntIntoLong

import scala.collection.mutable
import scala.reflect._

private[spark]
class TapLRDD[T: ClassTag](@transient lc: LineageContext, @transient deps: Seq[Dependency[_]])
    extends RDD[T](lc.sparkContext, deps) with Lineage[T] {

  @transient private[spark] var splitId: Short = 0

  @transient private[spark] var tContext: TaskContextImpl = _

  @transient private[spark] var nextRecord: Int = _

  @transient private var buffer: LongIntLongByteBuffer = _

  private var combine: Boolean = true

  tapRDD = Some(this)

  var isLast = false

  private[spark] var shuffledData: Lineage[_] = _

  setCaptureLineage(true)

  private[spark] def newRecordId() = {
    nextRecord += 1
    nextRecord
  }

  private[spark] override def computeOrReadCheckpoint(
     split: Partition,
     context: TaskContext): Iterator[T] = compute(split, context)

  override def ttag = classTag[T]

  override def lineageContext: LineageContext = lc

  override def getPartitions: Array[Partition] = firstParent[T].partitions

  override def compute(split: Partition, context: TaskContext) = {
    if(tContext == null) {
      tContext = context.asInstanceOf[TaskContextImpl]
    }
    splitId = split.index.toShort
    nextRecord = -1

    initializeBuffer()

    LineageManager.initMaterialization(this, split, context)

    // Make sure to time the current tap function here too.
    firstParent[T].iterator(split, context).map(measureTime(context, tap, this.id))
  }

  override def filter(f: T => Boolean): Lineage[T] = {
    val cleanF = sparkContext.clean(f)
    // Jason: need to understand this better
    // difference from Lineage#filter is that no "withScope" is used.
    new MapPartitionsLRDD[T, T](
      this, (context, pid, iter, rddId) => iter.filter(cleanF), preservesPartitioning = true)
  }

  override def materializeBuffer: Array[Any] = buffer.iterator.toArray.map(r => (r._1, r
    ._2.toLong, r._3))

  override def releaseBuffer(): Unit = {
    buffer.clear()
    tContext.addToBufferPool(buffer.getData)
  }

  def setCached(cache: Lineage[_]): TapLRDD[T] = {
    shuffledData = cache
    this
  }

  def combinerEnabled(enabled: Boolean) = {
    combine = enabled
    this
  }

  def isCombinerEnabled = combine

  def getCachedData = shuffledData.setIsPostShuffleCache()

  def initializeBuffer() = buffer = new LongIntLongByteBuffer(tContext.getFromBufferPool())

  def tap(record: T) = {
    val id = newRecordId()
    val timeTaken = computeTotalTime()
    logInfo(s"computed time: $timeTaken")
    buffer.put(PackIntIntoLong(splitId, id),  tContext.currentInputId, timeTaken)
    if(isLast) {
      (record, PackIntIntoLong(splitId, id)).asInstanceOf[T]
    } else {
      record
    }
  }
  
  // TODO optimize this further, eg if you can cache accumulated times within each RDD?
  // Do we want to be computing this now?
  // iterative implementation of a post-order DAG traversal
  // in recursive terms, this would compute accumulate(currValue, aggregate(childrenValues))
  // Because the current RDD has no time (it's still computing), we only return the aggregate over
  // its dependencies
  def computeTotalTime(accumulateFunction: (Long, Long) => Long = _+_,
                       aggregateFunction:Seq[Long]=>Long = _.foldLeft(0L)(math.max)) : Long = {
    val dependencyRDDs = this.dependencies.map(_.rdd)
    val s1 = mutable.Stack[RDD[_]](dependencyRDDs:_*)
    val postOrderStack = new mutable.Stack[RDD[_]]
    val seen = new mutable.HashSet[RDD[_]]
    var curr: RDD[_] = null
    // First: add all RDDs in post-order traversal
    while (s1.nonEmpty) {
      curr = s1.pop()
      postOrderStack.push(curr)
      seen.add(curr)
      s1.pushAll(curr.dependencies.map(_.rdd).filter(!seen(_)))
    }
    
    val cachedTimes = mutable.HashMap[RDD[_], Long]() // initialization/base value
    while(postOrderStack.nonEmpty) {
      curr = postOrderStack.pop()
      if(!cachedTimes.contains(curr)) {
        val currTime: Long = accumulateFunction(
          tContext.getRddRecordOutputTime(curr.id),
          aggregateFunction(curr.dependencies.map(_.rdd).map(cachedTimes(_))))
        cachedTimes(curr) = currTime
      }
    }
    // No time taken for the current RDD because it's still running...
    aggregateFunction(dependencyRDDs.map(cachedTimes(_)))
  }
}