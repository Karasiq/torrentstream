package com.karasiq.torrentstream

import java.io.{Closeable, File}
import java.net.InetAddress

import akka.actor.{FSM, Stash}
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}
import com.karasiq.torrentstream.TorrentWriterData._
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
sealed trait TorrentWriterContext extends Closeable {
  val torrent: SharedTorrent
  val client: Client

  def close(): Unit = {
    client.stop(true)
    torrent.close()
  }
}
object TorrentWriterData {
  case object NoData extends TorrentWriterData
  case class TorrentInfo(torrent: SharedTorrent, client: Client, file: String) extends TorrentWriterData with TorrentWriterContext
  case class TorrentFileInfo(torrent: SharedTorrent, client: Client, file: String, fileSize: Long, currentOffset: Long) extends TorrentWriterData with TorrentWriterContext
}

class TorrentWriter extends FSM[TorrentWriterState, TorrentWriterData] with Stash with ActorPublisher[TorrentChunk] {
  val maxBufferSize = 200

  var buffer = Vector.empty[TorrentChunk]

  private def deliverBuffer(): Unit = {
    if (totalDemand > 0) {
      val (use, keep) = buffer.splitAt(totalDemand.toInt)
      buffer = keep
      use.foreach(this.onNext)
    }
  }

  startWith(Idle, NoData)

  when(Idle) {
    case Event(DownloadTorrent(data, file), _) ⇒
      log.info("Starting torrent download: {}", file)
      val torrent = new SharedTorrent(self, data.toArray, new File(sys.props("java.io.tmpdir")), false)
      torrent.setWantedFile(file)
      val client = new Client(InetAddress.getLocalHost, torrent)
      client.download()
      unstashAll()
      sender() ! TorrentStarted
      goto(AwaitingMetadata) using TorrentInfo(torrent, client, file)

    case Event(Request(_), _) ⇒
      stash()
      stay()

    case Event(Cancel, _) ⇒
      buffer = Vector.empty
      stay()
  }

  when(AwaitingMetadata, stateTimeout = 5 minutes) {
    case Event(TorrentFileStart(file, size), TorrentInfo(torrent, client, fileName)) if file == fileName ⇒
      log.info("Torrent downloading started: {} ({} bytes)", file, size)
      unstashAll()
      goto(Loading) using TorrentFileInfo(torrent, client, file, size, 0)

    case Event(Request(_), _) ⇒
      stash()
      stay()

    case Event(Cancel | InterruptTorrentDownload | StateTimeout, ctx: TorrentWriterContext) ⇒
      buffer = Vector.empty
      log.warning("Torrent downloading interrupted")
      sender() ! TorrentStopped
      ctx.close()
      goto(Idle) using NoData
  }

  when(Loading, stateTimeout = 5 minutes) {
    case Event(chunk @ TorrentChunk(file, offset, data), ctx @ TorrentFileInfo(torrent, client, fileName, fileSize, fileOffset)) if file == fileName ⇒
      if (offset == fileOffset) {
        log.info("Chunk: {} at {} with size {} of total {}", file, offset, data.length, fileSize)
        if (buffer.isEmpty && totalDemand > 0) {
          onNext(chunk)
        } else {
          if (buffer.length < maxBufferSize) {
            buffer :+= chunk
          } else {
            // Overflow
            buffer = buffer.drop(1) :+ chunk
          }
          deliverBuffer()
        }
        val newOffset = fileOffset + data.length
        if (newOffset == fileSize) {
          log.info("End of file: {}", file)
          ctx.close()
          goto(Idle) using NoData
        } else {
          unstashAll()
          stay() using TorrentFileInfo(torrent, client, fileName, fileSize, newOffset)
        }
      } else {
        log.warning("Chunk stashed: {}", offset)
        stash()
        stay()
      }

    case Event(Request(_), _) ⇒
      log.info("Streaming torrent")
      deliverBuffer()
      stay()

    case Event(Cancel | InterruptTorrentDownload | StateTimeout, ctx: TorrentWriterContext) ⇒
      buffer = Vector.empty
      log.warning("Torrent downloading interrupted")
      sender() ! TorrentStopped
      ctx.close()
      goto(Idle) using NoData
  }
}
