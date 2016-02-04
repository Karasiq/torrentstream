package com.karasiq.bittorrent.streams

import java.security.MessageDigest

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}
import akka.stream.scaladsl.{ImplicitMaterializer, Sink}
import akka.util.ByteString
import com.karasiq.bittorrent.dispatcher._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

case class PieceFrom(source: ActorRef, data: DownloadedPiece)

class PeerPiecePublisher(request: PieceDownloadRequest, peerDispatcher: ActorRef) extends Actor with ActorLogging with ActorPublisher[DownloadedPiece] with ImplicitMaterializer {
  import context.dispatcher
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

  override def receive: Receive = {
    case PieceDownloadRequest(index, _) if this.piece.isEmpty ⇒
      log.info("Requesting piece #{}", request.index)
      val self = context.self
      TorrentSource.pieceBlocks(peerDispatcher, request.index, request.piece, blockSize)
        .alsoTo(Sink.onComplete {
          case Success(_) ⇒
            // Nothing

          case Failure(_) ⇒
            log.warning("Retrying piece #{}", request.index)
            context.system.scheduler.scheduleOnce(100 millis, self, request)
        })
        .runWith(Sink.foreach(data ⇒ self ! DownloadedPiece(request.index, data)))

    case piece @ DownloadedPiece(index, data) if this.piece.isEmpty ⇒
      if (checkHash(data, request.piece.sha1)) {
        log.info("Piece finished #{}", index)
        peerDispatcher ! HasPiece(index)
        this.piece = Some(piece)
        publish()
      } else {
        // Retry
        log.warning(s"Invalid piece #$index")
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
