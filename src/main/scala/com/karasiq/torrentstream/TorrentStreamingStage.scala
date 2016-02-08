package com.karasiq.torrentstream

import akka.stream.stage.{Context, PushPullStage, SyncDirective, TerminationDirective}
import akka.util.ByteString
import com.karasiq.bittorrent.dispatcher.DownloadedPiece

import scala.annotation.tailrec

class TorrentStreamingStage(pieceLength: Int, private var ranges: Seq[(Long, Long)]) extends PushPullStage[DownloadedPiece, ByteString] {
  private var currentRange: (Long, Long) = ranges.head

  private var currentOffset: Long = currentRange._1

  private var buffer = Seq.empty[DownloadedPiece]


  override def onUpstreamFinish(ctx: Context[ByteString]): TerminationDirective = {
    if (buffer.nonEmpty) ctx.absorbTermination()
    else ctx.finish()
  }

  @tailrec
  private def deliverBuffer(ctx: Context[ByteString]): SyncDirective = buffer match {
    case Seq(DownloadedPiece(index, data), rest @ _*) if (index * pieceLength) <= currentOffset ⇒
      val pieceOffset = (currentOffset - (index * pieceLength)).toInt
      val chunkLength = Seq(data.length.toLong - pieceOffset, currentRange._2 - currentOffset).min.toInt
      buffer = rest
      currentOffset += chunkLength
      val chunk = data.slice(pieceOffset, pieceOffset + chunkLength)
      if (currentOffset >= currentRange._2) {
        if (ranges.tail.nonEmpty) {
          currentRange = ranges.tail.head
          ranges = ranges.tail
          currentOffset = currentRange._1
          ctx.push(chunk)
          deliverBuffer(ctx)
        } else {
          ctx.pushAndFinish(chunk)
        }
      } else {
        ctx.push(chunk)
      }

    case _ ⇒
      if (ctx.isFinishing) {
        ctx.finish()
      } else {
        ctx.pull()
      }
  }

  override def onPush(elem: DownloadedPiece, ctx: Context[ByteString]): SyncDirective = {
    if ((elem.pieceIndex * pieceLength) + elem.data.length > currentOffset) {
      buffer :+= elem
    }
    deliverBuffer(ctx)
  }

  override def onPull(ctx: Context[ByteString]): SyncDirective = {
    deliverBuffer(ctx)
  }
}
