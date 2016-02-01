package com.karasiq.bittorrent.format

import akka.util.ByteString

import scala.collection.mutable.ArrayBuffer

case class TorrentPiece(size: Long, sha1: ByteString, file: TorrentFileInfo)

object TorrentPiece {
  def sequence(files: TorrentFiles): IndexedSeq[TorrentPiece] = {
    val buffer = new ArrayBuffer[TorrentPiece](files.pieces.length / 20)
    val totalSize = files.files.map(_.size).sum
    var fileOffset = 0L
    var offset = 0L
    var pieceIndex = 0
    var (currentFile, tail) = files.files.head → files.files.tail
    while (offset < totalSize) {
      if (fileOffset >= currentFile.size) {
        currentFile = tail.head
        tail = tail.tail
        fileOffset = 0L
      }
      buffer += TorrentPiece(files.pieceLength, files.pieces.slice(pieceIndex * 20, (pieceIndex * 20) + 20), currentFile)
      offset += files.pieceLength
      fileOffset += files.pieceLength
      pieceIndex += 1
    }
    buffer.result()
  }
}