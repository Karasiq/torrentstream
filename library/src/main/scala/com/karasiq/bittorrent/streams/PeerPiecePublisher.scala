package com.karasiq.bittorrent.streams

import java.security.MessageDigest

import scala.language.postfixOps
import scala.util.{Failure, Success}

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}
import akka.stream.scaladsl.Sink
import akka.util.ByteString

import com.karasiq.bittorrent.dispatcher._
import com.karasiq.bittorrent.format.TorrentPiece

object PeerPiecePublisher {
  def props(peerDispatcher: ActorRef, request: PieceDownloadRequest): Props = {
    Props(new PeerPiecePublisher(peerDispatcher, request))
  }
}

class PeerPiecePublisher(peerDispatcher: ActorRef, request: PieceDownloadRequest) extends Actor with ActorLogging with ActorPublisher[DownloadedPiece] {
  final implicit val materializer: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(context.system))

  private val blockSize = context.system.settings.config.getInt("karasiq.bittorrent.peer-dispatcher.block-size")
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
    case PieceDownloadRequest(TorrentPiece(index, _, _, _)) if this.piece.isEmpty ⇒
      log.debug("Requesting piece #{}", index)
      TorrentSource.pieceBlocks(peerDispatcher, index, request.piece, blockSize)
        .map(data ⇒ DownloadedPiece(index, data))
        .runWith(Sink.actorRef(self, Success(null)))

    case piece @ DownloadedPiece(index, data) if this.piece.isEmpty ⇒
      if (checkHash(data, request.piece.sha1)) {
        log.debug("Piece finished #{}", index)
        peerDispatcher ! piece
        this.piece = Some(piece)
        publish()
      } else {
        // Retry
        log.warning(s"Invalid piece #$index")
        self ! request
      }

    case Failure(exc) ⇒
      onErrorThenStop(exc)

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
