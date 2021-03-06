package com.karasiq.bittorrent.streams

import scala.annotation.tailrec
import scala.language.postfixOps

import akka.actor._
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}

import com.karasiq.bittorrent.dispatcher._
import com.karasiq.bittorrent.dispatcher.MessageConversions._
import com.karasiq.bittorrent.protocol.PeerMessages.PieceBlockRequest

object PeerBlockPublisher {
  def props(peerDispatcher: ActorRef, pieceSize: Int): Props = {
    Props(new PeerBlockPublisher(peerDispatcher, pieceSize))
  }
}

class PeerBlockPublisher(peerDispatcher: ActorRef, pieceSize: Int) extends Actor with ActorPublisher[DownloadedBlock] with ActorLogging {
  // Buffers
  private var requested = Set.empty[PieceBlockRequest]
  private var buffer = List.empty[DownloadedBlock]
  private var currentOffset = 0

  override def postStop(): Unit = {
    for (PieceBlockRequest(index, offset, length) ← requested) {
      peerDispatcher ! CancelBlockDownload(index, offset, length)
    }
    super.postStop()
  }

  @tailrec
  private def deliverBuffer(): Unit = {
    if (totalDemand > 0) {
      val (use, keep) = buffer.partition(_.offset == currentOffset)
      if (use.nonEmpty) {
        buffer = keep
        onNext(use.head)
        currentOffset += use.head.data.length
        if (currentOffset == pieceSize) {
          onCompleteThenStop()
        } else {
          deliverBuffer()
        }
      }
    }
  }

  private def deliver(chunk: DownloadedBlock): Unit = {
    if (chunk.offset >= currentOffset) {
      if (buffer.length < 200) {
        buffer :+= chunk
      } else {
        // Overflow
        buffer = buffer.drop(1) :+ chunk
      }
    }
    deliverBuffer()
  }

  override def receive: Receive = {
    case Request(_) ⇒
      deliverBuffer()

    case Cancel ⇒
      context.stop(self)

    case request: PieceBlockRequest ⇒
      requested += request
      peerDispatcher ! request

    case block: DownloadedBlock ⇒
      val request = block.request
      if (requested.contains(request)) {
        requested -= request
        deliver(block)
      }
  }
}
