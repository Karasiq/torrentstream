package com.karasiq.torrentstream

import akka.util.ByteString

sealed trait TorrentEvent

case class TorrentFileStart(file: String, size: Long) extends TorrentEvent

case class TorrentChunk(file: String, offset: Long, data: ByteString) extends TorrentEvent

sealed trait TorrentCommand

case class DownloadTorrent(torrent: ByteString, file: String, offset: Long) extends TorrentCommand

case object InterruptTorrentDownload extends TorrentCommand

sealed trait TorrentStatus

case object TorrentStarted extends TorrentStatus

case object TorrentStopped extends TorrentStatus