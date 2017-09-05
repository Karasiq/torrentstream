package com.karasiq.bittorrent.format

import java.time.Instant

import akka.util.ByteString

import com.karasiq.bittorrent.utils.Utils

// TODO: Create metadata from files
final case class Torrent(infoHash: ByteString, announce: String, announceList: Seq[Seq[String]],
                         createdBy: Option[String], comment: Option[String], encoding: Option[String],
                         date: Option[Instant], data: TorrentFiles) {

  val size: Long = data.files.map(_.size).sum
  val piecesCount: Int = data.pieces.length / Torrent.PieceHashSize

  def infoHashString: String = {
    Utils.toHexString(infoHash).toUpperCase
  }
}

final case class TorrentFiles(name: String, pieceSize: Int, pieces: ByteString, files: Seq[TorrentFile]) {
  require(pieces.length % Torrent.PieceHashSize == 0, "Invalid pieces data")

  def piecesIterator: Iterator[ByteString] = {
    pieces.grouped(Torrent.PieceHashSize)
  }
}

final case class TorrentFile(name: String, size: Long)

object Torrent extends DefaultTorrentParser {
  val PieceHashAlgorithm = "SHA-1"
  val PieceHashSize = 20

  def apply(data: ByteString): Torrent = {
    this.fromBytes(data)
  }
}