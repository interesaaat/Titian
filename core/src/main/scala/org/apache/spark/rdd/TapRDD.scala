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

package org.apache.spark.rdd

import java.util.concurrent.atomic.AtomicLong

import org.apache.spark._
import org.apache.spark.storage.StorageLevel

import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag

private[spark]
class TapRDD[T : ClassTag](@transient sc: SparkContext, @transient deps: Seq[Dependency[_]])
    extends RDD[T](sc, deps) {

  // TODO make recordInfo grow in memory and spill to disk if a certain percentage of available
  // memory is reached.
  private val recordInfo = new ArrayBuffer[(Any, Any)]()

  setCaptureLineage(true)

  def addRecordInfo(key: (Int, Int, Long), value: Seq[(_)]) = {
    value.foreach(v => recordInfo += key -> v)
  }

  def getRecordInfos = recordInfo

  protected var splitId: Int = 0

  protected var tContext: TaskContext = null

  protected var nextRecord: AtomicLong = new AtomicLong(0)

  protected def newRecordId = nextRecord.getAndIncrement

  override def getPartitions: Array[Partition] = firstParent[T].partitions

  private var offset: Long = 0

  private[spark] var cached: ShuffledRDD[_, _, _] = null

  def setCached(shuffle: ShuffledRDD[_, _, _]): TapRDD[T] = {
     throw new UnsupportedOperationException("TapRDDs cannot have a cache")
  }

  def getCached: ShuffledRDD[_, _, _] =
    throw new UnsupportedOperationException("TapRDDs cannot have a cache")

  override def compute(split: Partition, context: TaskContext) = {
    if(tContext == null) {
      tContext = context
    }
    splitId = split.index

    SparkEnv.get.cacheManager.initMaterialization(this, split, StorageLevel.MEMORY_ONLY)

    firstParent[T].iterator(split, context).map(tap)
  }

  /**
   * Compute an RDD partition or read it from a checkpoint if the RDD is checkpointing.
   */
  private[spark] override def computeOrReadCheckpoint(
    split: Partition,
    context: TaskContext): Iterator[T] =
  {
    compute(split, context)
  }

  def tap(record: T): T = {
    val checkpointRDD = firstParent[T].asInstanceOf[CheckpointRDD[T]]
    val tuple3 = (checkpointRDD.checkpointPath, splitId, offset)
    val recordId = (id, splitId, newRecordId)
    tContext.currentRecordInfo = Seq(recordId)
    addRecordInfo(recordId, Seq(tuple3))
    offset += record.toString.size - 1
    // println("Tapping " + record + " with id " + id + " joins with " + tuple2)
    record
  }
}
