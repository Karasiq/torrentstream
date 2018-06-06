package com.karasiq.bittorrent.dispatcher

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor._
import akka.pattern.{ask, pipe}
import akka.stream.{Materializer, SourceShape}
import akka.stream.actor.ActorPublisher
import akka.stream.scaladsl._
import akka.stream.scaladsl.Tcp._
import akka.util.{ByteString, Timeout}

import com.karasiq.bittorrent.announce.HttpAnnouncer
import com.karasiq.bittorrent.dht.DHTRoutingTable
import com.karasiq.bittorrent.dispatcher.PeerDispatcher.{DispatcherData, RequestDispatcherData}
import com.karasiq.bittorrent.dispatcher.TorrentManager.{CreateDispatcher, PeerDispatcherData, RequestDispatcher}
import com.karasiq.bittorrent.format.Torrent
import com.karasiq.bittorrent.protocol.PeerMessages.PeerHandshake
import com.karasiq.bittorrent.utils.Utils

object TorrentManager {
  def props: Props = {
    Props(new TorrentManager)
  }

  def listener(manager: ActorRef)(implicit actorRefFactory: ActorRefFactory,
                                  materializer: Materializer): Sink[IncomingConnection, Future[akka.Done]] = {
    import akka.pattern.ask
    implicit val timeout = Timeout(10 seconds)

    Sink.foreach[IncomingConnection] { connection ⇒
      val flow = Flow[ByteString]
        .via(PeerConnection.framing)
        .prefixAndTail(1)
        .initialTimeout(10 seconds)
        .mapAsync(1) { case (Seq(hs @ PeerHandshake(_, infoHash, _, _)), messages) ⇒
          (manager ? RequestDispatcher(infoHash)).mapTo[PeerDispatcherData].zip(Future.successful(Source.single(hs).concat(messages)))
        }
        .flatMapConcat { case (PeerDispatcherData(torrent, dispatcher, ownData), messages) ⇒
          val messageProcessor = actorRefFactory.actorOf(PeerConnection.props(dispatcher, torrent, connection.remoteAddress, ownData))
          Source.fromGraph(GraphDSL.create() { implicit b ⇒
            b.add(messages.to(Sink.foreach(messageProcessor ! _)))
            val output = b.add(Source.fromPublisher(ActorPublisher[ByteString](messageProcessor)))
            SourceShape(output.out)
          })
        }

      connection.handleWith(flow)
    }
  }

  sealed trait Message
  case class CreateDispatcher(torrent: Torrent) extends Message
  case class RequestDispatcher(infoHash: ByteString) extends Message
  case class PeerDispatcherData(torrent: Torrent, actorRef: ActorRef, state: SeedData)
}

class TorrentManager extends Actor with ActorLogging {
  private[this] implicit val executionContext = context.dispatcher
  private[this] implicit val askTimeout = Timeout(10 seconds)

  private[this] val dhtRoutingTable = context.actorOf(DHTRoutingTable.props, "dhtRoutingTable")
  private[this] val httpAnnouncer = context.actorOf(HttpAnnouncer.props, "httpAnnouncer")
  private[this] val dispatchers = mutable.AnyRefMap.empty[ByteString, (Torrent, ActorRef)]

  //noinspection VariablePatternShadow
  override def receive: Receive = {
    case CreateDispatcher(torrent) ⇒
      val sender = context.sender()
      val dispatcher = dispatchers.get(torrent.infoHash) match {
        case Some((_, dispatcher)) ⇒
          dispatcher

        case None ⇒
          val dispatcher = context.actorOf(PeerDispatcher.props(dhtRoutingTable, httpAnnouncer, torrent))
          context.watch(dispatcher)
          dispatchers += torrent.infoHash → (torrent, dispatcher)
          dispatcher
      }
      (dispatcher ? RequestDispatcherData).collect {
        case DispatcherData(data) ⇒
          PeerDispatcherData(torrent, dispatcher, data)
      }.pipeTo(sender)

    case RequestDispatcher(infoHash) ⇒
      dispatchers.get(infoHash).foreach {
        case (torrent, dispatcher) ⇒
          (dispatcher ? RequestDispatcherData).collect {
            case DispatcherData(data) ⇒
              PeerDispatcherData(torrent, dispatcher, data)
          }.pipeTo(sender)

        case _ ⇒
          log.warning("Dispatcher not found for info hash: {}", Utils.toHexString(infoHash))
      }

    case Terminated(terminated) ⇒
      dispatchers.foreach { case (infoHash, (_, dispatcher)) ⇒
        if (dispatcher == terminated) dispatchers -= infoHash
      }
  }
}