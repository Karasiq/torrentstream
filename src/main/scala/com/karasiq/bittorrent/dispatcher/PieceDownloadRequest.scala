package com.karasiq.bittorrent.dispatcher

import akka.util.ByteString
import com.karasiq.bittorrent.format.{TorrentPiece, TorrentPieceBlock}

case class PieceDownloadRequest(index: Int, piece: TorrentPiece)

case class DownloadedPiece(pieceIndex: Int, data: ByteString)

case class PieceBlockDownloadRequest(index: Int, block: TorrentPieceBlock)

case class DownloadedBlock(index: Int, offset: Int, data: ByteString)

case class CancelPieceBlockDownload(index: Int, offset: Int, length: Int)

case class PieceBlockDownloadFailed(index: Int, offset: Int, length: Int)