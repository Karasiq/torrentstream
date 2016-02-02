package com.karasiq.bittorrent.dispatcher

import java.security.MessageDigest

import akka.actor.{Actor, ActorLogging, ActorRef, Stash}
import akka.pattern.{ask, pipe}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.util.ByteString
import org.apache.commons.codec.binary.Hex

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

class PeerDispatcher(implicit am: ActorMaterializer) extends Actor with ActorLogging with Stash {
  import context.dispatcher
  var peers = Vector.empty[(PeerData, ActorRef)]

  private def checkHash(data: ByteString, hash: ByteString): Boolean = {
    val md = MessageDigest.getInstance("SHA-1")
    ByteString(md.digest(data.toArray)) == hash
  }

  override def receive: Receive = {
    case request @ PieceDownloadRequest(index, sha1) ⇒
      implicit val timeout = 3 minutes
      if (log.isInfoEnabled) {
        log.info("Piece download request: {} with hash {}", index, Hex.encodeHexString(sha1.toArray))
      }
      val seeders = Random.shuffle(peers.collect {
        case (data, connection) if data.completed(index) ⇒
          connection
      })
      val result = Source(seeders)
        .mapAsyncUnordered(3)(peer ⇒ (peer ? request).mapTo[DownloadedPiece])
        .filter(piece ⇒ checkHash(piece.data, sha1))
        .runWith(Sink.head)
      result.pipeTo(context.sender())
  }
}