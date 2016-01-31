package com.karasiq.torrentstream

import java.io.{Closeable, File}
import java.net.InetAddress

import akka.actor.{FSM, Stash}
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}
import com.karasiq.torrentstream.TorrentWriterData._
import com.karasiq.torrentstream.TorrentWriterState._
import com.karasiq.ttorrent.client.{Client, SharedTorrent}

import scala.annotation.tailrec
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
  case class TorrentFileInfo(torrent: SharedTorrent, client: Client, file: String, fileSize: Long, currentOffset: Long) extends TorrentWriterData with TorrentWriterContext
}

class TorrentWriter(completeAfterFirstFile: Boolean) extends FSM[TorrentWriterState, TorrentWriterData] with Stash with ActorPublisher[TorrentChunk] {
  def this() = {
    this(completeAfterFirstFile = true)
  }

  val maxBufferSize = 200

  var buffer = Vector.empty[TorrentChunk]

  val minRate = 2.5 // In kilobytes per second

  private def autoDownloadRate(): Double = {
    if (buffer.isEmpty && totalDemand > 0) {
      0 // Unlimited
    } else {
      minRate * (maxBufferSize - buffer.length)
    }
  }

  @tailrec
  private def deliverBuffer(): Unit = {
    if (totalDemand > 0) {
      if (totalDemand <= Int.MaxValue) {
        val (use, keep) = buffer.splitAt(totalDemand.toInt)
        buffer = keep
        use foreach onNext
      } else {
        val (use, keep) = buffer.splitAt(Int.MaxValue)
        buffer = keep
        use foreach onNext
        deliverBuffer()
      }
    }
  }

  private def deliver(chunk: TorrentChunk): Unit = {
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
  }

  startWith(Idle, NoData)

  when(Idle) {
    case Event(DownloadTorrent(data, file, startOffset), _) ⇒
      log.info("Starting torrent download: {} from position {}", file, startOffset)
      val torrent = new SharedTorrent(self, data.toArray, new File(sys.props("java.io.tmpdir")), false)
      torrent.setWantedFile(file, startOffset)
      val client = new Client(InetAddress.getLocalHost, torrent)
      client.setMaxDownloadRate(autoDownloadRate())
      client.download()
      unstashAll()
      sender() ! TorrentStarted
      goto(AwaitingMetadata) using TorrentFileInfo(torrent, client, file, 0, startOffset)

    case Event(Request(_), _) ⇒
      stash()
      stay()

    case Event(InterruptTorrentDownload, _) ⇒
      sender() ! TorrentStopped
      buffer = Vector.empty
      stay()

    case Event(Cancel, _) ⇒
      log.warning("Torrent streaming stopped")
      stop()
  }

  when(AwaitingMetadata, stateTimeout = 5 minutes) {
    case Event(TorrentFileStart(file, size), TorrentFileInfo(torrent, client, fileName, _, startOffset)) if file == fileName ⇒
      log.info("Torrent downloading started: {} ({} bytes)", file, size)
      unstashAll()
      goto(Loading) using TorrentFileInfo(torrent, client, file, size, startOffset)

    case Event(_: DownloadTorrent | Request(_), _) ⇒
      stash()
      stay()

    case Event(InterruptTorrentDownload | StateTimeout, ctx: TorrentWriterContext) ⇒
      log.warning("Torrent downloading interrupted")
      ctx.close()
      buffer = Vector.empty
      sender() ! TorrentStopped
      unstashAll()
      goto(Idle) using NoData

    case Event(Cancel, ctx: TorrentWriterContext) ⇒
      log.warning("Torrent streaming stopped")
      ctx.close()
      stop()
  }

  when(Loading, stateTimeout = 30 minutes) {
    case Event(chunk @ TorrentChunk(file, offset, data), ctx @ TorrentFileInfo(torrent, client, fileName, fileSize, fileOffset)) if file == fileName ⇒
      if (offset <= fileOffset) {
        log.info("Max download rate set to {} kb/sec", this.autoDownloadRate())
        client.setMaxDownloadRate(this.autoDownloadRate())
        log.info("Chunk: {} at {} with size {} of total {}", file, offset, data.length, fileSize)
        deliver(chunk.copy(data = data.drop((fileOffset - offset).toInt)))
        val newOffset = offset + data.length
        if (newOffset == fileSize) {
          log.info("End of file: {}", file)
          ctx.close()
          if (completeAfterFirstFile) {
            onComplete()
            stop()
          } else {
            unstashAll()
            goto(Idle) using NoData
          }
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
      deliverBuffer()
      stay()

    case Event(InterruptTorrentDownload | StateTimeout, ctx: TorrentWriterContext) ⇒
      log.warning("Torrent downloading interrupted")
      ctx.close()
      buffer = Vector.empty
      sender() ! TorrentStopped
      unstashAll()
      goto(Idle) using NoData

    case Event(Cancel, ctx: TorrentWriterContext) ⇒
      log.warning("Torrent streaming stopped")
      ctx.close()
      stop()

    case Event(_: DownloadTorrent, _) ⇒
      stash()
      stay()
  }
}
