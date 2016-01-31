package com.karasiq.torrentstream

import java.io.File
import java.net.InetAddress

import akka.actor.{FSM, Stash}
import com.karasiq.torrentstream.TorrentWriterData.{NoData, TorrentFileInfo, TorrentInfo}
import com.karasiq.torrentstream.TorrentWriterState._
import com.karasiq.ttorrent.client.{Client, SharedTorrent}

import scala.concurrent.duration._
import scala.language.postfixOps

sealed trait TorrentWriterState
object TorrentWriterState {
  case object Idle extends TorrentWriterState
  case object AwaitingMetadata extends TorrentWriterState
  case object Loading extends TorrentWriterState
}

sealed trait TorrentWriterData
object TorrentWriterData {
  case object NoData extends TorrentWriterData
  case class TorrentInfo(torrent: SharedTorrent, client: Client, file: String) extends TorrentWriterData
  case class TorrentFileInfo(torrent: SharedTorrent, client: Client, file: String, fileSize: Long, currentOffset: Long) extends TorrentWriterData
}

class TorrentWriter extends FSM[TorrentWriterState, TorrentWriterData] with Stash {
  startWith(Idle, NoData)

  when(Idle) {
    case Event(DownloadTorrent(data, file), _) ⇒
      log.info("Starting torrent download: {}", file)
      val torrent = new SharedTorrent(self, data.toArray, new File(sys.props("java.io.tmpdir")), false)
      torrent.setWantedFile(file)
      val client = new Client(InetAddress.getLocalHost, torrent)
      client.download()
      goto(AwaitingMetadata) using TorrentInfo(torrent, client, file)
  }

  when(AwaitingMetadata, stateTimeout = 5 minutes) {
    case Event(TorrentFileStart(file, size), TorrentInfo(torrent, client, fileName)) if file == fileName ⇒
      log.info("Torrent downloading started: {} ({} bytes)", file, size)
      goto(Loading) using TorrentFileInfo(torrent, client, file, size, 0)

    case Event(StateTimeout, TorrentInfo(torrent, client, fileName)) ⇒
      client.stop(true)
      torrent.close()
      goto(Idle) using NoData
  }

  when(Loading, stateTimeout = 5 minutes) {
    case Event(TorrentChunk(file, offset, data), TorrentFileInfo(torrent, client, fileName, fileSize, fileOffset)) if file == fileName ⇒
      if (offset == fileOffset) {
        log.info("Chunk: {} at {} with size {} of total {}", file, offset, data.length, fileSize)
        val newOffset = fileOffset + data.length
        if (newOffset == fileSize) {
          log.info("End of file: {}", file)
          client.stop(true)
          torrent.close()
          goto(Idle) using NoData
        } else {
          unstashAll()
          stay() using TorrentFileInfo(torrent, client, fileName, fileSize, newOffset)
        }
      } else {
        log.info("Skipping: {}", offset)
        stash()
        stay()
      }

    case Event(StateTimeout, TorrentFileInfo(torrent, client, fileName, fileSize, fileOffset)) ⇒
      client.stop(true)
      torrent.close()
      goto(Idle) using NoData
  }
}
