package com.karasiq.torrentstream

import akka.stream.stage.{Context, PushPullStage, SyncDirective, TerminationDirective}
import akka.util.ByteString
import com.karasiq.bittorrent.dispatcher.DownloadedPiece

import scala.annotation.tailrec

private[torrentstream] class TorrentStreamingStage(pieceLength: Int, private var ranges: Seq[TorrentFileOffset]) extends PushPullStage[DownloadedPiece, ByteString] {
  private var currentRange: TorrentFileOffset = ranges.head

  private var currentOffset: Long = currentRange.start

  private var buffer = Seq.empty[DownloadedPiece]


  override def onUpstreamFinish(ctx: Context[ByteString]): TerminationDirective = {
    if (buffer.nonEmpty) ctx.absorbTermination()
    else ctx.finish()
  }

  @tailrec
  private def deliverBuffer(ctx: Context[ByteString]): SyncDirective = buffer match {
    case Seq(DownloadedPiece(index, data), rest @ _*) if (index.toLong * pieceLength) <= currentOffset ⇒
      val pieceOffset = (currentOffset - (index * pieceLength)).toInt
      val chunkLength = Seq(data.length.toLong - pieceOffset, currentRange.end - currentOffset).min.toInt
      require(chunkLength > 0)
      buffer = rest
      currentOffset += chunkLength
      val chunk = data.slice(pieceOffset, pieceOffset + chunkLength)
      if (currentOffset >= currentRange.end) {
        if (ranges.tail.nonEmpty) {
          currentRange = ranges.tail.head
          ranges = ranges.tail
          currentOffset = currentRange.start
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
    buffer = (buffer :+ elem).sortBy(_.pieceIndex)
    deliverBuffer(ctx)
  }

  override def onPull(ctx: Context[ByteString]): SyncDirective = {
    deliverBuffer(ctx)
  }
}
