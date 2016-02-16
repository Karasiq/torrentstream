package com.karasiq.bittorrent.dispatcher

import akka.util.ByteString
import com.karasiq.bittorrent.format.TorrentPiece

trait PieceBlockInfo {
  def index: Int
  def offset: Int
  def length: Int
}

trait PieceBlockData extends PieceBlockInfo {
  def data: ByteString

  override def length: Int = data.length
}

case class PieceDownloadRequest(piece: TorrentPiece)

case class DownloadedPiece(pieceIndex: Int, data: ByteString)

case class DownloadedBlock(index: Int, offset: Int, data: ByteString) extends PieceBlockData

case class CancelBlockDownload(index: Int, offset: Int, length: Int) extends PieceBlockInfo

case class BlockDownloadFailed(index: Int, offset: Int, length: Int) extends PieceBlockInfo