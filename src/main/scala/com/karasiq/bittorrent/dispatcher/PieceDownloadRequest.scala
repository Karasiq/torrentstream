package com.karasiq.bittorrent.dispatcher

import akka.util.ByteString

case class PieceDownloadRequest(pieceIndex: Int, sha1: ByteString)

case class DownloadedPiece(pieceIndex: Int, data: ByteString)