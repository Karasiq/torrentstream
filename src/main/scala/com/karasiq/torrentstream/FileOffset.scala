package com.karasiq.torrentstream

import com.karasiq.bittorrent.format.{TorrentFileInfo, TorrentMetadata, TorrentPiece}

private[torrentstream] case class FileOffset(file: TorrentFileInfo, start: Long, end: Long) {
  require(start <= end, "Invalid range")
}

private[torrentstream] case class TorrentRangeList(pieces: Seq[TorrentPiece], offsets: Seq[FileOffset]) {
  def size: Long = offsets.map(ofs ⇒ ofs.end - ofs.start).sum
}

private[torrentstream] object FileOffset {
  private def pieceOffset(torrent: TorrentMetadata, piece: TorrentPiece): FileOffset = {
    val start = torrent.files.pieceLength.toLong * piece.index
    val end = start + piece.size
    FileOffset(piece.file, start, end)
  }

  def file(torrent: TorrentMetadata, file: TorrentFileInfo): TorrentRangeList = {
    val pieces = TorrentPiece.sequence(torrent.files).filter(_.file == file)
    val start = pieces.headOption.map(p ⇒ pieceOffset(torrent, p).start).getOrElse(0L)
    val end = start + file.size
    TorrentRangeList(pieces, Seq(FileOffset(file, start, end)))
  }

  def absoluteOffsets(torrent: TorrentMetadata, offsets: Seq[FileOffset]): TorrentRangeList = {
    val pieces = TorrentPiece.sequence(torrent.files).collect {
      case piece @ TorrentPiece(_, _, _, file) if offsets.exists(_.file == file) ⇒
        piece
    }

    val fileOffset = pieces.headOption.map(p ⇒ pieceOffset(torrent, p).start).getOrElse(0L)

    val absolute = offsets.map {
      case FileOffset(file, start, end) ⇒
        FileOffset(file, Seq(start + fileOffset, 0L).max, Seq(end + fileOffset, pieceOffset(torrent, pieces.last).end).min)
    }

    val boundedPieces = pieces
      .dropWhile { piece ⇒ pieceOffset(torrent, piece).end <= absolute.head.start }
      .takeWhile { piece ⇒ pieceOffset(torrent, piece).start < absolute.last.end }

    TorrentRangeList(boundedPieces, absolute)
  }
}
