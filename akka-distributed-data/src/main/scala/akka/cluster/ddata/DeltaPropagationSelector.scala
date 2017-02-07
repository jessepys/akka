/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.ddata

import scala.collection.immutable.TreeMap
import akka.cluster.ddata.Replicator.Internal.DeltaPropagation
import akka.actor.Address
import akka.cluster.ddata.Replicator.Internal.DataEnvelope
import akka.cluster.UniqueAddress

/**
 * INTERNAL API: Used by the Replicator actor.
 * Extracted to separate trait to make it easy to test.
 */
private[akka] trait DeltaPropagationSelector {

  private var _propagationCount = 0L
  def propagationCount: Long = _propagationCount
  private var deltaCounter = Map.empty[String, Long]
  private var deltaEntries = Map.empty[String, TreeMap[Long, ReplicatedData]]
  private var deltaSentToNode = Map.empty[String, Map[Address, Long]]
  private var deltaNodeRoundRobinCounter = 0L

  def gossipIntervalDivisor: Int

  def allNodes: Vector[Address]

  def createDeltaPropagation(deltas: Map[String, (ReplicatedData, Long, Long)]): DeltaPropagation

  def currentVersion(key: String): Long = deltaCounter.get(key) match {
    case Some(v) ⇒ v
    case None    ⇒ 0L
  }

  def update(key: String, delta: ReplicatedData): Unit = {
    // bump the counter for each update
    val version = deltaCounter.get(key) match {
      case Some(c) ⇒ c + 1
      case None    ⇒ 1L
    }
    deltaCounter = deltaCounter.updated(key, version)
    println(s"# update delta $key [$version] -> $delta") // FIXME

    val deltaEntriesForKey = deltaEntries.get(key) match {
      case Some(m) ⇒ m
      case None    ⇒ TreeMap.empty[Long, ReplicatedData]
    }

    deltaEntries = deltaEntries.updated(key, deltaEntriesForKey.updated(version, delta))
  }

  def delete(key: String): Unit = {
    deltaEntries -= key
    deltaCounter -= key
    deltaSentToNode -= key
  }

  def nodesSliceSize(allNodesSize: Int): Int = {
    // 2 - 10 nodes
    math.min(math.max((allNodesSize / gossipIntervalDivisor) + 1, 2), math.min(allNodesSize, 10))
  }

  def collectPropagations(): Map[Address, DeltaPropagation] = {
    _propagationCount += 1
    val all = allNodes
    if (all.isEmpty)
      Map.empty
    else {
      // For each tick we pick a few nodes in round-robin fashion, 2 - 10 nodes for each tick.
      // Normally the delta is propagated to all nodes within the gossip tick, so that
      // full state gossip is not needed.
      val sliceSize = nodesSliceSize(all.size)
      val slice = {
        if (all.size <= sliceSize)
          all
        else {
          val i = (deltaNodeRoundRobinCounter % all.size).toInt
          val first = all.slice(i, i + sliceSize)
          if (first.size == sliceSize) first
          else first ++ all.take(sliceSize - first.size)
        }
      }
      deltaNodeRoundRobinCounter += sliceSize

      var result = Map.empty[Address, DeltaPropagation]

      slice.foreach { node ⇒
        // collect the deltas that have not already been sent to the node and merge
        // them into a delta group
        var deltas = Map.empty[String, (ReplicatedData, Long, Long)]
        deltaEntries.foreach {
          case (key, entries) ⇒
            val deltaSentToNodeForKey = deltaSentToNode.getOrElse(key, TreeMap.empty[Address, Long])
            val j = deltaSentToNodeForKey.getOrElse(node, 0L)
            val deltaEntriesAfterJ = deltaEntriesAfter(entries, j)
            if (deltaEntriesAfterJ.nonEmpty) {
              val (fromSeqNr, _) = deltaEntriesAfterJ.head
              val (toSeqNr, _) = deltaEntriesAfterJ.last
              // FIXME in most cases the delta group merging will be the same for each node,
              //       so we should cache that merge in this method
              val deltaGroup = deltaEntriesAfterJ.valuesIterator.reduceLeft {
                (d1, d2) ⇒ d1.merge(d2.asInstanceOf[d1.T])
              }
              deltas = deltas.updated(key, (deltaGroup, fromSeqNr, toSeqNr))
              deltaSentToNode = deltaSentToNode.updated(key, deltaSentToNodeForKey.updated(node, deltaEntriesAfterJ.lastKey))
            }
        }

        if (deltas.nonEmpty) {
          // Important to include the pruning state in the deltas. For example if the delta is based
          // on an entry that has been pruned but that has not yet been performed on the target node.
          val deltaPropagation = createDeltaPropagation(deltas)
          result = result.updated(node, deltaPropagation)
        }
      }

      result
    }
  }

  private def deltaEntriesAfter(entries: TreeMap[Long, ReplicatedData], version: Long): TreeMap[Long, ReplicatedData] =
    entries.from(version) match {
      case ntrs if ntrs.isEmpty             ⇒ ntrs
      case ntrs if ntrs.firstKey == version ⇒ ntrs.tail // exclude first, i.e. version j that was already sent
      case ntrs                             ⇒ ntrs
    }

  def hasDeltaEntries(key: String): Boolean = {
    deltaEntries.get(key) match {
      case Some(m) ⇒ m.nonEmpty
      case None    ⇒ false
    }
  }

  private def findSmallestVersionPropagatedToAllNodes(key: String, all: Vector[Address]): Long = {
    deltaSentToNode.get(key) match {
      case None ⇒ 0L
      case Some(deltaSentToNodeForKey) ⇒
        if (deltaSentToNodeForKey.isEmpty) 0L
        else if (all.exists(node ⇒ !deltaSentToNodeForKey.contains(node))) 0L
        else deltaSentToNodeForKey.valuesIterator.min
    }
  }

  def cleanupDeltaEntries(): Unit = {
    val all = allNodes
    if (all.isEmpty)
      deltaEntries = Map.empty
    else {
      deltaEntries = deltaEntries.map {
        case (key, entries) ⇒
          val minVersion = findSmallestVersionPropagatedToAllNodes(key, all)

          val deltaEntriesAfterMin = deltaEntriesAfter(entries, minVersion)

          // TODO perhaps also remove oldest when deltaCounter is too far ahead (e.g. 10 cylces)

          key → deltaEntriesAfterMin
      }
    }
  }

  def cleanupRemovedNode(address: Address): Unit = {
    deltaSentToNode = deltaSentToNode.map {
      case (key, deltaSentToNodeForKey) ⇒
        key → (deltaSentToNodeForKey - address)
    }
  }
}
