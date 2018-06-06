package com.karasiq.bittorrent.dht

import java.net.InetSocketAddress

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.{Actor, ActorLogging, ActorRef, PossiblyHarmful, Props, Status}
import akka.pattern.ask
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout

import com.karasiq.bittorrent.dht.DHTMessageDispatcher.SendQuery
import com.karasiq.bittorrent.dht.DHTMessages._

object DHTBootstrapQueue {
  // Messages
  sealed trait Message
  final case class PingNode(nodeAddress: InetSocketAddress) extends Message

  // Internal messages
  private sealed trait InternalMessage extends Message with PossiblyHarmful
  private final case class RegisterNode(nodeAddress: DHTNodeAddress) extends InternalMessage
  private case object ResetPinged extends InternalMessage

  // Events
  sealed trait Event

  // Props
  def props(dhtCtx: DHTContext, bucket: ActorRef): Props = {
    Props(new DHTBootstrapQueue(dhtCtx, bucket))
  }
}

class DHTBootstrapQueue(dhtCtx: DHTContext, bucket: ActorRef) extends Actor with ActorLogging {
  import context.dispatcher

  import DHTBootstrapQueue._
  private[this] implicit val materializer = ActorMaterializer()
  private[this] implicit val timeout = Timeout(10 seconds)

  val queue = Source.queue[InetSocketAddress](200, OverflowStrategy.dropHead)
    .mapAsyncUnordered(8) { nodeAddress ⇒
      sendQuery(nodeAddress, DHTQueries.ping(dhtCtx.selfNodeId))
        .map((nodeAddress, _))
    }
    .log("dht-ping-result")
    .collect {
      case (nodeAddress, SendQuery.Success(PingResponse.Encoded(PingResponse(nodeId)))) ⇒
        DHTNodeAddress(nodeId, nodeAddress)
    }
    .to(Sink.foreach(node ⇒ self ! RegisterNode(node)))
    .named("dhtPingQueue")
    .run()

  override def receive: Receive = receiveDefault(Set.empty)

  def receiveDefault(pinged: Set[InetSocketAddress]): Receive = {
    case PingNode(address) if !pinged.contains(address) ⇒
      log.debug("Pinging DHT node: {}", address)
      queue.offer(address)
      context.become(receiveDefault(pinged + address))

    case RegisterNode(nodeAddress) ⇒
      log.info("DHT node responded: {}", nodeAddress)
      bucket ! DHTBucket.AddNodes(Set(nodeAddress))
      sendQuery(nodeAddress.address, DHTQueries.findNode(dhtCtx.selfNodeId, dhtCtx.selfNodeId)).foreach {
        case SendQuery.Success(FindNodeResponse.Encoded(FindNodeResponse(_, nodes))) ⇒
          nodes
            .filter(_.nodeId != dhtCtx.selfNodeId)
            .foreach(node ⇒ self ! PingNode(node.address))
      }

    case ResetPinged ⇒
      context.become(receiveDefault(Set.empty))
  }

  def sendQuery(nodeAddress: InetSocketAddress, query: DHTQuery): Future[SendQuery.Status] = {
    (dhtCtx.messageDispatcher ? SendQuery(nodeAddress, query))
      .mapTo[SendQuery.Status]
      .recover { case exc ⇒ SendQuery.Failure(exc) }
  }

  override def preStart(): Unit = {
    super.preStart()
    queue.watchCompletion().onComplete(_ ⇒ self ! Status.Failure(new Exception("Queue terminated")))
    context.system.scheduler.schedule(5 minutes, 5 minutes, self, ResetPinged)
  }

  override def postStop(): Unit = {
    queue.complete()
    super.postStop()
  }
}
