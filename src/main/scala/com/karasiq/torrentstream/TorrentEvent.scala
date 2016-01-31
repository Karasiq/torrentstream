package com.karasiq.torrentstream

import akka.util.ByteString

sealed trait TorrentEvent

case class TorrentFileStart(file: String, size: Long) extends TorrentEvent

case class TorrentChunk(file: String, offset: Long, data: ByteString) extends TorrentEvent

case class DownloadTorrent(torrent: ByteString, file: String)