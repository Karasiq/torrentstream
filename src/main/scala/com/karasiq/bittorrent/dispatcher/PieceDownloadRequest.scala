package com.karasiq.bittorrent.dispatcher

import akka.util.ByteString
import com.karasiq.bittorrent.format.TorrentPiece

case class PieceDownloadRequest(index: Int, piece: TorrentPiece)

sealed trait PieceDownloadResult

case object PieceDownloadFailed extends PieceDownloadResult

case class DownloadedPiece(pieceIndex: Int, data: ByteString) extends PieceDownloadResult