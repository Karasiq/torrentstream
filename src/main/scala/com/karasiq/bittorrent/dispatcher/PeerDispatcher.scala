package com.karasiq.bittorrent.dispatcher

import java.security.MessageDigest

import akka.actor._
import akka.pattern.{ask, pipe}
import akka.stream.scaladsl._
import akka.util.ByteString
import com.karasiq.bittorrent.format.TorrentMetadata
import org.apache.commons.codec.binary.Hex

import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

class PeerDispatcher(torrent: TorrentMetadata) extends Actor with ActorLogging with Stash with ImplicitMaterializer {
  import context.dispatcher
  private val peers = mutable.Map.empty[ActorRef, PeerData]

  private def checkHash(data: ByteString, hash: ByteString): Boolean = {
    val md = MessageDigest.getInstance("SHA-1")
    ByteString(md.digest(data.toArray)) == hash
  }

  override def receive: Receive = {
    case request @ PieceDownloadRequest(index, piece) ⇒
      implicit val timeout = 3 minutes
      if (log.isInfoEnabled) {
        log.info("Piece download request: {} with hash {}", index, Hex.encodeHexString(piece.sha1.toArray))
      }
      val seeders = Random.shuffle(peers.collect {
        case (peer, data) if data.completed(index) && !data.chokedBy ⇒
          peer
      })
      val result = Source(seeders.toVector)
        .mapAsyncUnordered(3)(peer ⇒ (peer ? request).collect {
          case dp: DownloadedPiece ⇒
            dp
        })
        .filter(r ⇒ checkHash(r.data, piece.sha1))
        .runWith(Sink.head)
      result.pipeTo(context.sender())

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