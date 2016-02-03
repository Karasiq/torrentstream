package com.karasiq.bittorrent.streams

import java.security.MessageDigest

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}
import akka.stream.scaladsl.{ImplicitMaterializer, Sink, Source}
import akka.util.ByteString
import com.karasiq.bittorrent.dispatcher.{DownloadedBlock, DownloadedPiece, PieceBlockDownloadRequest, PieceDownloadRequest}
import com.karasiq.bittorrent.format.TorrentPiece

import scala.concurrent.duration._
import scala.language.postfixOps

class PeerPiecePublisher(request: PieceDownloadRequest, peerDispatcher: ActorRef) extends Actor with ActorLogging with ActorPublisher[DownloadedPiece] with ImplicitMaterializer {
  private val blockSize = context.system.settings.config.getInt("karasiq.torrentstream.peer-load-balancer.block-size")
  private var piece: Option[DownloadedPiece] = None

  private def checkHash(data: ByteString, hash: ByteString): Boolean = {
    val md = MessageDigest.getInstance("SHA-1")
    ByteString(md.digest(data.toArray)) == hash
  }

  private def publish(): Unit = {
    if (totalDemand > 0) {
      onNext(piece.get)
      onCompleteThenStop()
    }
  }

  //noinspection VariablePatternShadow
  override def receive: Receive = {
    case PieceDownloadRequest(index, piece) if this.piece.isEmpty ⇒
      val self = context.self
      val blocks = TorrentPiece.blocks(piece, blockSize).toVector
      Source(blocks)
        .completionTimeout(1 minute)
        .flatMapConcat(block ⇒ Source.actorPublisher[DownloadedBlock](Props(classOf[PeerBlockPublisher], PieceBlockDownloadRequest(index, block), peerDispatcher)))
        .fold(ByteString.empty)((bs, block) ⇒ bs ++ block.data)
        .runWith(Sink.foreach(data ⇒ self ! DownloadedPiece(index, data)))

    case piece @ DownloadedPiece(index, data) if this.piece.isEmpty ⇒
      if (checkHash(data, request.piece.sha1)) {
        this.piece = Some(piece)
        publish()
      } else {
        // Retry
        log.warning(s"Invalid piece: #$index")
        self ! request
      }

    case Request(_) ⇒
      if (piece.isDefined) {
        publish()
      } else {
        self ! request
      }

    case Cancel ⇒
      context.stop(self)
  }
}
