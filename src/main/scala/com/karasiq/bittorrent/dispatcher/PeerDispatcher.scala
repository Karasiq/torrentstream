package com.karasiq.bittorrent.dispatcher

import akka.actor._
import akka.stream.scaladsl._
import com.karasiq.bittorrent.format.TorrentMetadata
import com.karasiq.bittorrent.streams.PeerPiecePublisher
import org.apache.commons.codec.binary.Hex

import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps

case class RequestPeers(piece: Int)
case class PeerList(peers: Seq[ActorRef])

class PeerDispatcher(torrent: TorrentMetadata) extends Actor with ActorLogging with Stash with ImplicitMaterializer {
  import context.dispatcher
  private val peers = mutable.Map.empty[ActorRef, PeerData]

  override def receive: Receive = {
    case r @ RequestPeers(index) ⇒
      val result = peers.collect {
        case (peer, data) if data.completed(index) && !data.chokedBy && !data.busy ⇒
          peer
      }.toSeq
      if (result.nonEmpty) {
        sender() ! PeerList(result)
      } else {
        val self = context.self
        val sender = context.sender()
        context.system.scheduler.scheduleOnce(3 seconds) {
          // Retry
          self.tell(r, sender)
        }
      }

    case request @ PieceDownloadRequest(index, piece) ⇒
      val sender = context.sender()
      if (log.isInfoEnabled) {
        log.info("Piece download request: {} with hash {}", index, Hex.encodeHexString(piece.sha1.toArray))
      }

      Source.actorPublisher[DownloadedPiece](Props(classOf[PeerPiecePublisher], request, self))
        .runWith(Sink.foreach(sender ! _))

    case c @ ConnectPeer(address, data) ⇒
      if (!peers.exists(_._2.address == address)) {
        log.info("Connecting to: {}", address)
        context.actorOf(Props(classOf[PeerConnection], torrent)) ! c
      }

    case PeerConnected(peerData) ⇒
      peers += sender() → peerData

    case PeerStateChanged(peerData) ⇒
      peers += sender() → peerData

    case PeerDisconnected(peerData) ⇒
      peers -= sender()
  }
}
