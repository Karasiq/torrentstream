package com.karasiq.bittorrent.dispatcher

import akka.stream.stage._
import akka.util.ByteString

import scala.annotation.tailrec

class PieceBlockStage(blockSize: Int, index: Int, pieceLength: Int) extends PushPullStage[DownloadedBlock, ByteString] {
  private var offset = 0
  private var piece = ByteString.empty
  private var buffer = Vector.empty[DownloadedBlock]
  private val maxBufferSize = 200

  private def emitChunk(ctx: Context[ByteString]): SyncDirective = {
    if (piece.isEmpty) {
      if (ctx.isFinishing) {
        ctx.finish()
      } else {
        ctx.pull()
      }
    } else {
      val result = piece
      piece = ByteString.empty
      if ((offset + result.length) >= pieceLength) {
        ctx.pushAndFinish(result)
      } else {
        ctx.push(result)
      }
    }
  }

  override def onUpstreamFinish(ctx: Context[ByteString]): TerminationDirective = {
    if (buffer.nonEmpty) ctx.absorbTermination()
    else ctx.finish()
  }

  @tailrec
  private def updatePieceData(): Unit = {
    val (head, tail) = buffer.partition(_.offset == offset)
    if (head.nonEmpty) {
      piece ++= head.head.data
      offset += head.head.data.length
      buffer = tail
      updatePieceData()
    }
  }

  override def onPush(elem: DownloadedBlock, ctx: Context[ByteString]): SyncDirective = {
    if (elem.offset >= offset) {
      if (buffer.length < maxBufferSize) {
        buffer :+= elem
      } else {
        buffer = buffer.drop(1) :+ elem
      }
      updatePieceData()
    }
    emitChunk(ctx)
  }

  override def onPull(ctx: Context[ByteString]): SyncDirective = {
    emitChunk(ctx)
  }
}
