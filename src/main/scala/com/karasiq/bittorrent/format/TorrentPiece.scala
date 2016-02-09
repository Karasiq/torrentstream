package com.karasiq.bittorrent.format

import akka.util.ByteString

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

case class TorrentPiece(index: Int, size: Int, sha1: ByteString, file: TorrentFile)
case class TorrentPieceBlock(piece: TorrentPiece, offset: Int, size: Int)

object TorrentPiece {
  def pieces(files: TorrentFiles): IndexedSeq[TorrentPiece] = {
    // Total torrent size
    val totalSize = files.files.map(_.size).sum

    @tailrec
    def pieceSequenceRec(buffer: ArrayBuffer[TorrentPiece], offset: Long, fileOffset: Long, pieceIndex: Int, fileSeq: Seq[TorrentFile]): IndexedSeq[TorrentPiece] = fileSeq match {
      case Seq(currentFile, fs @ _*) if fs.nonEmpty && fileOffset >= currentFile.size ⇒
        pieceSequenceRec(buffer, offset, 0L, pieceIndex, fs)

      case fs @ Seq(currentFile, _*) if offset < totalSize ⇒
        val length = Seq(files.pieceLength.toLong, totalSize - offset).min
        require(length <= Int.MaxValue)
        val sha1 = files.pieces.slice(pieceIndex * 20, (pieceIndex * 20) + 20)
        val piece = TorrentPiece(buffer.length, length.toInt, sha1, currentFile)
        pieceSequenceRec(buffer :+ piece, offset + length, fileOffset + length, pieceIndex + 1, fs)

      case other ⇒
        buffer.result()
    }
    pieceSequenceRec(new ArrayBuffer[TorrentPiece](files.pieces.length / 20), 0L, 0L, 0, files.files)
  }

  def blocks(piece: TorrentPiece, sizeLimit: Int): IndexedSeq[TorrentPieceBlock] = {
    @tailrec
    def pieceBlockRec(buffer: ArrayBuffer[TorrentPieceBlock], offset: Int): IndexedSeq[TorrentPieceBlock] = {
      if (offset >= piece.size) {
        buffer.result()
      } else {
        val block = TorrentPieceBlock(piece, offset, Seq(sizeLimit, piece.size - offset).min)
        pieceBlockRec(buffer :+ block, offset + block.size)
      }
    }
    pieceBlockRec(new ArrayBuffer[TorrentPieceBlock](piece.size / sizeLimit + 1), 0)
  }
}