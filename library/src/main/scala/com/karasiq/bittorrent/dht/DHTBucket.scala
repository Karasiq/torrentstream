package com.karasiq.bittorrent.dht

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Random, Success}

import akka.actor.{Actor, ActorLogging, ActorRef, DeadLetterSuppression, NotInfluenceReceiveTimeout, PossiblyHarmful, Props}
import akka.pattern.{ask, pipe}
import akka.util.Timeout

import com.karasiq.bittorrent.dht.DHTMessageDispatcher.SendQuery
import com.karasiq.bittorrent.dht.DHTMessages.{DHTNodeAddress, DHTQueries, FindNodeResponse}

object DHTBucket {
  // Messages
  sealed trait Message
  final case class AssociateNodes(nodes: Set[DHTNodeAddress]) extends Message
  final case object BucketIsEmpty extends Message with NotInfluenceReceiveTimeout with DeadLetterSuppression

  final case class FindNodes(id: NodeId) extends Message
  object FindNodes {
    sealed trait Status
    final case class Success(nodes: Seq[DHTNodeAddress]) extends Status
  }

  final case object GetAllNodes {
    sealed trait Status
    final case class Success(nodes: Set[DHTNodeAddress]) extends Status
  }

  // Internal messages
  private sealed trait InternalMessage extends Message with PossiblyHarmful
  private case object RefreshNodes extends InternalMessage with NotInfluenceReceiveTimeout with DeadLetterSuppression
  private final case class RemoveNode(node: DHTNodeAddress) extends InternalMessage
  private final case class DeSplit(nodes: Set[DHTNodeAddress]) extends InternalMessage

  // Events
  sealed trait Event

  // Props
  def props(dhtCtx: DHTContext, start: BigInt, end: BigInt): Props = {
    Props(new DHTBucket(dhtCtx, start, end))
  }
}

class DHTBucket(dhtCtx: DHTContext, start: BigInt, end: BigInt) extends Actor with ActorLogging {
  import context.dispatcher

  import DHTBucket._
  private[this] implicit val timeout = Timeout(10 seconds)
  private[this] val maxNodesInBucket = 8

  override def receive: Receive = receiveDefault(Set.empty)

  def receiveDefault(nodes: Set[DHTNodeAddress], lastChanged: Long = System.nanoTime()): Receive = {
    case AssociateNodes(addNodes) ⇒
      val newNodes = nodes ++ addNodes.filter { n ⇒
        val idInt = n.nodeId.toBigInt
        idInt >= start && idInt <= end
      }

      if (canSplit(newNodes)) split(newNodes)
      else context.become(receiveDefault(newNodes))

    case RemoveNode(address) ⇒
      val newNodes = nodes - address
      if (newNodes.isEmpty) context.parent ! BucketIsEmpty
      context.become(receiveDefault(newNodes))

    case FindNodes(target) ⇒
      val result = nodes.toVector.sortBy(_.nodeId.distanceTo(target))
      sender() ! FindNodes.Success(result)

    case GetAllNodes ⇒
      sender() ! GetAllNodes.Success(nodes)

    case RefreshNodes ⇒
      val changedAgo = (System.nanoTime() - lastChanged).nanos
      if (changedAgo > 15.minutes) refreshBucket(nodes)
  }

  def receiveSplit(half: BigInt, first: ActorRef, second: ActorRef): Receive = {
    case AssociateNodes(nodes) ⇒
      val (firstNodes, secondNodes) = nodes.partition(_.nodeId.toBigInt < half)
      first.forward(AssociateNodes(firstNodes))
      second.forward(AssociateNodes(secondNodes))

    case rn @ RemoveNode(address) ⇒
      if (address.nodeId.toBigInt < half) first.forward(rn)
      else second.forward(rn)

    case fn @ FindNodes(target) ⇒
      if (target.toBigInt < half) first.forward(fn)
      else second.forward(fn)

    case GetAllNodes ⇒
      val future = for {
        GetAllNodes.Success(firstList) ← (first ? GetAllNodes).mapTo[GetAllNodes.Success]
        GetAllNodes.Success(secondList) ← (second ? GetAllNodes).mapTo[GetAllNodes.Success]
      } yield GetAllNodes.Success(firstList ++ secondList)

      future.pipeTo(sender())

    case BucketIsEmpty ⇒
      (self ? GetAllNodes).mapTo[GetAllNodes.Success].foreach {
        case GetAllNodes.Success(nodes) ⇒
          if (nodes.size <= maxNodesInBucket) self ! DeSplit(nodes)
      }

    case RefreshNodes ⇒
      // Ignore

    case DeSplit(nodes) ⇒
      context.stop(first)
      context.stop(second)
      context.become(receiveDefault(nodes))
  }

  def split(nodes: Set[DHTNodeAddress]): Unit = {
    val half = (start + end) / 2
    val (firstNodes, secondNodes) = nodes.partition(_.nodeId.toBigInt < half)
    val firstBucket = context.actorOf(DHTBucket.props(dhtCtx, start, half))
    val secondBucket = context.actorOf(DHTBucket.props(dhtCtx, half, end))
    firstBucket ! AssociateNodes(firstNodes)
    secondBucket ! AssociateNodes(secondNodes)
    context.become(receiveSplit(half, firstBucket, secondBucket))
  }

  def canSplit(nodes: Set[DHTNodeAddress]): Boolean = {
    (end - start) > maxNodesInBucket && nodes.size > maxNodesInBucket
  }

  def refreshBucket(nodes: Set[DHTNodeAddress]): Unit = {
    val randomId = start + BigInt((end - start).bitLength, Random)
    assert(randomId >= start && randomId <= end, "Invalid random id")
    log.debug("Refreshing bucket: {}", randomId: NodeId)

    nodes.toVector.foreach { nodeAddress ⇒
      val future = (dhtCtx.messageDispatcher ? SendQuery(nodeAddress.address, DHTQueries.findNode(dhtCtx.selfNodeId, randomId))).mapTo[SendQuery.Status]

      future.onComplete {
        case Success(SendQuery.Success(FindNodeResponse.Encoded(FindNodeResponse(_, nodes)))) ⇒
          log.info("Bucket refreshed: {}", nodes)
          nodes
            .map(na ⇒ DHTRoutingTable.AddNode(na.address))
            .foreach(dhtCtx.routingTable ! _)

        case Failure(_) | Success(SendQuery.Failure(_)) ⇒
          self ! RemoveNode(nodeAddress)

        case _ ⇒
          // Ignore
      }
    }
  }

  override def preStart(): Unit = {
    super.preStart()
    context.system.scheduler.schedule(5 minutes, 5 minutes, self, RefreshNodes)
  }
}
