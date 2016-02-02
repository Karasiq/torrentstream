package com.karasiq.bittorrent.dispatcher

import akka.actor.ActorRef
import akka.io.Tcp.{Received, Write}
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}
import com.karasiq.bittorrent.dispatcher.PeerProtocol.{Msg, PieceBlock, PieceRequest}
import com.karasiq.bittorrent.format.TorrentPieceBlock

import scala.annotation.tailrec

class PieceDownloader(index: Int, connection: ActorRef) extends ActorPublisher[PieceBlock]  {
  private var buffer = Vector.empty[PieceBlock]

  @tailrec
  private def deliverBuffer(): Unit = {
    if (totalDemand > 0) {
      if (totalDemand <= Int.MaxValue) {
        val (use, keep) = buffer.splitAt(totalDemand.toInt)
        buffer = keep
        use foreach onNext
      } else {
        val (use, keep) = buffer.splitAt(Int.MaxValue)
        buffer = keep
        use foreach onNext
        deliverBuffer()
      }
    }
  }

  private def deliver(block: PieceBlock): Unit = {
    if (buffer.isEmpty && totalDemand > 0) {
      onNext(block)
    } else {
      buffer :+= block
      deliverBuffer()
    }
  }

  override def receive: Receive = {
    case TorrentPieceBlock(offset, size) ⇒
      connection ! Write(PieceRequest(index, offset.toInt, size.toInt).toBytes)

    case Received(Msg(Msg.Piece(block))) if block.index == index ⇒
      deliver(block)

    case Request(_) ⇒
      deliverBuffer()

    case Cancel ⇒
      context.stop(self)

    case m ⇒
      context.parent.forward(m)
  }
}
