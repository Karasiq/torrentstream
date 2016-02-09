package com.karasiq.bittorrent.dispatcher

import akka.actor._
import akka.pattern.{ask, pipe}
import akka.stream.actor.ActorPublisher
import akka.stream.scaladsl.Tcp._
import akka.stream.scaladsl._
import akka.stream.{ActorMaterializer, SourceShape}
import akka.util.{ByteString, Timeout}
import com.karasiq.bittorrent.format.Torrent
import com.karasiq.bittorrent.protocol.PeerMessages.PeerHandshake
import org.apache.commons.codec.binary.Hex

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

case class CreateDispatcher(torrent: Torrent)
case class RequestDispatcher(infoHash: ByteString)
case class PeerDispatcherData(torrent: Torrent, actorRef: ActorRef, state: SeedData)

class TorrentManager extends Actor with ActorLogging {
  private implicit val ec = context.dispatcher
  implicit val timeout = Timeout(10 seconds)
  private var dispatchers = Vector.empty[(Torrent, ActorRef)]

  //noinspection VariablePatternShadow
  override def receive: Receive = {
    case CreateDispatcher(torrent) ⇒
      val sender = context.sender()
      val dispatcher = dispatchers.find(_._1.infoHash == torrent.infoHash) match {
        case Some((_, dispatcher)) ⇒
          dispatcher

        case None ⇒
          val dispatcher = context.actorOf(PeerDispatcher.props(torrent))
          context.watch(dispatcher)
          dispatchers :+= torrent → dispatcher
          dispatcher
      }
      (dispatcher ? RequestData).collect {
        case DispatcherData(data) ⇒
          PeerDispatcherData(torrent, dispatcher, data)
      }.pipeTo(sender)

    case RequestDispatcher(infoHash) ⇒
      dispatchers.find(_._1.infoHash == infoHash).foreach {
        case (torrent, dispatcher) ⇒
          (dispatcher ? RequestData).collect {
            case DispatcherData(data) ⇒
              PeerDispatcherData(torrent, dispatcher, data)
          }.pipeTo(sender)

        case _ ⇒
          log.warning("Dispatcher not found for info hash: {}", Hex.encodeHexString(infoHash.toArray))
      }

    case Terminated(dispatcher) ⇒
      dispatchers = dispatchers.filterNot(_._2 == dispatcher)
  }
}

object TorrentManager {
  def props: Props = {
    Props[TorrentManager]
  }

  def listener(manager: ActorRef)(implicit arf: ActorRefFactory, am: ActorMaterializer): Sink[IncomingConnection, Future[Unit]] = {
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
          val messageProcessor = arf.actorOf(PeerConnection.props(dispatcher, torrent, connection.remoteAddress, ownData))
          Source.fromGraph(GraphDSL.create() { implicit b ⇒
            b.add(messages.to(Sink.foreach(messageProcessor ! _)))
            val output = b.add(Source.fromPublisher(ActorPublisher[ByteString](messageProcessor)))
            SourceShape(output.out)
          })
        }

      connection.handleWith(flow)
    }
  }
}
