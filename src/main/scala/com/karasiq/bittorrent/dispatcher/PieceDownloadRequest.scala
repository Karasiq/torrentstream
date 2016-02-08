package com.karasiq.bittorrent.dispatcher

import akka.util.ByteString
import com.karasiq.bittorrent.format.TorrentPiece

case class PieceDownloadRequest(piece: TorrentPiece)

case class DownloadedPiece(pieceIndex: Int, data: ByteString)

case class DownloadedBlock(index: Int, offset: Int, data: ByteString)

case class CancelBlockDownload(index: Int, offset: Int, length: Int)

case class BlockDownloadFailed(index: Int, offset: Int, length: Int)