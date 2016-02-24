package com.karasiq.torrentstream

import com.karasiq.bittorrent.format.{Torrent, TorrentFile, TorrentPiece}

case class TorrentFileOffset(file: TorrentFile, start: Long, end: Long) {
  require(start <= end, "Invalid range")
}

case class TorrentRangeList(pieces: Seq[TorrentPiece], offsets: Seq[TorrentFileOffset]) {
  def size: Long = offsets.map(ofs ⇒ ofs.end - ofs.start).sum
}

object TorrentFileOffset {
  private def pieceOffset(torrent: Torrent, piece: TorrentPiece): TorrentFileOffset = {
    val start = torrent.data.pieceLength.toLong * piece.index
    val end = start + piece.size
    TorrentFileOffset(piece.file, start, end)
  }

  def file(torrent: Torrent, file: TorrentFile): TorrentRangeList = {
    val pieces = TorrentPiece.pieces(torrent.data).filter(_.file == file)
    val start = pieces.headOption.map(p ⇒ pieceOffset(torrent, p).start).getOrElse(0L)
    val end = start + file.size
    TorrentRangeList(pieces, Vector(TorrentFileOffset(file, start, end)))
  }

  def absoluteOffsets(torrent: Torrent, offsets: Seq[TorrentFileOffset]): TorrentRangeList = {
    val pieces = TorrentPiece.pieces(torrent.data).collect {
      case piece @ TorrentPiece(_, _, _, file) if offsets.exists(_.file == file) ⇒
        piece
    }

    val fileOffset = pieces.headOption.map(p ⇒ pieceOffset(torrent, p).start).getOrElse(0L)

    val absolute = offsets.map {
      case TorrentFileOffset(file, start, end) ⇒
        TorrentFileOffset(file, Array(start + fileOffset, 0L).max, Array(end + fileOffset, pieceOffset(torrent, pieces.last).end).min)
    }

    val boundedPieces = pieces
      .dropWhile { piece ⇒ pieceOffset(torrent, piece).end <= absolute.head.start }
      .takeWhile { piece ⇒ pieceOffset(torrent, piece).start < absolute.last.end }

    TorrentRangeList(boundedPieces, absolute)
  }
}
