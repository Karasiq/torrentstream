package com.karasiq.bittorrent.dispatcher

import akka.stream.stage._
import akka.util.ByteString
import com.karasiq.bittorrent.dispatcher.PeerProtocol.PieceBlock

import scala.annotation.tailrec

class PieceBlockStage(index: Int, pieceLength: Int) extends PushStage[PieceBlock, DownloadedPiece] {
  private var piece = ByteString.empty
  private var buffer = Vector.empty[PieceBlock]

  override def onPush(elem: PieceBlock, ctx: Context[DownloadedPiece]): SyncDirective = {
    buffer :+= elem

    @tailrec
    def updatePieceData(): Unit = {
      val (head, tail) = buffer.partition(_.offset == piece.length)
      if (head.nonEmpty) {
        piece ++= head.head.data
        buffer = tail
        updatePieceData()
      }
    }

    updatePieceData()
    if (piece.length == pieceLength) {
      ctx.pushAndFinish(DownloadedPiece(index, piece))
    } else {
      ctx.pull()
    }
  }
}
