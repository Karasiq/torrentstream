package com.karasiq.bittorrent.dht

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

import akka.actor.{Actor, ActorLogging, ActorRef, NotInfluenceReceiveTimeout, PossiblyHarmful, Props}
import akka.pattern.{ask, pipe}
import akka.util.Timeout

import com.karasiq.bittorrent.dht.DHTMessageDispatcher.SendQuery
import com.karasiq.bittorrent.dht.DHTMessages.{DHTNodeAddress, DHTQueries}

object DHTBucket {
  // Messages
  sealed trait Message
  final case class AddNodes(nodes: Set[DHTNodeAddress]) extends Message

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
  private case object RefreshNodes extends InternalMessage with NotInfluenceReceiveTimeout
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

  def receiveDefault(nodes: Set[DHTNodeAddress]): Receive = {
    case AddNodes(nodes1) ⇒
      val newNodes = nodes ++ nodes1.filter { n ⇒
        val idInt = n.nodeId.toBigInt
        idInt >= start && idInt <= end
      }

      if (canSplit(newNodes)) split(newNodes)
      else context.become(receiveDefault(newNodes))

    case RemoveNode(address) ⇒
      context.become(receiveDefault(nodes - address))

    case FindNodes(target) ⇒
      val result = nodes.toVector.sortBy(_.nodeId.distanceTo(target))
      sender() ! FindNodes.Success(result)

    case GetAllNodes ⇒
      sender() ! GetAllNodes.Success(nodes)

    // TODO: Buckets that have not been changed in 15 minutes should be "refreshed." This is done by picking a random ID in the range of the bucket and performing a find_nodes search on it. 
    case RefreshNodes ⇒
      nodes.toVector.foreach { nodeAddress ⇒
        val future = (dhtCtx.messageDispatcher ? SendQuery(nodeAddress.address, DHTQueries.ping(dhtCtx.selfNodeId))).mapTo[SendQuery.Status]

        future.onComplete {
          case Failure(_) | Success(SendQuery.Failure(_)) ⇒
            self ! RemoveNode(nodeAddress)

          case _ ⇒
            // Ignore 
        }
      }
  }

  def receiveSplit(half: BigInt, first: ActorRef, second: ActorRef): Receive = {
    case AddNodes(nodes) ⇒
      val (firstNodes, secondNodes) = nodes.partition(_.nodeId.toBigInt < half)
      first.forward(AddNodes(firstNodes))
      second.forward(AddNodes(secondNodes))

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

    case RefreshNodes ⇒
      (self ? GetAllNodes).mapTo[GetAllNodes.Success].foreach {
        case GetAllNodes.Success(nodes) ⇒
          if (nodes.size <= maxNodesInBucket) self ! DeSplit(nodes)
      }

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
    firstBucket ! AddNodes(firstNodes)
    secondBucket ! AddNodes(secondNodes)
    context.become(receiveSplit(half, firstBucket, secondBucket))
  }

  def canSplit(nodes: Set[DHTNodeAddress]): Boolean = {
    (end - start) > maxNodesInBucket && nodes.size > maxNodesInBucket
  }

  override def preStart(): Unit = {
    super.preStart()
    context.system.scheduler.schedule(10 minutes, 10 minutes, self, RefreshNodes)
  }
}
