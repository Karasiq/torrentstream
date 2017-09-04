package com.karasiq.torrentstream

import akka.NotUsed
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.scaladsl.Flow
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.util.ByteString

import com.karasiq.bittorrent.dispatcher.DownloadedPiece

object TorrentStreamingStage {
  def apply(pieceLength: Int, ranges: Seq[TorrentFileOffset]): Flow[DownloadedPiece, ByteString, NotUsed] = {
    Flow.fromGraph(new TorrentStreamingStage(pieceLength, ranges))
  }
}

private final class TorrentStreamingStage(pieceLength: Int, _ranges: Seq[TorrentFileOffset]) extends GraphStage[FlowShape[DownloadedPiece, ByteString]] {
  val inlet = Inlet[DownloadedPiece]("TorrentStreamingStage.in")
  val outlet = Outlet[ByteString]("TorrentStreamingStage.out")
  val shape = FlowShape(inlet, outlet)

  def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with InHandler with OutHandler {
    private[this] var ranges = _ranges
    private[this] var currentRange: TorrentFileOffset = ranges.head
    private[this] var currentOffset: Long = currentRange.start
    private[this] var buffer: Seq[DownloadedPiece] = Nil

    def onPull(): Unit = {
      deliverBuffer()
    }

    def onPush(): Unit = {
      val element = grab(inlet)
      buffer = (buffer :+ element).sortBy(_.pieceIndex)
      deliverBuffer()
    }

    override def onUpstreamFinish(): Unit = {
      if (buffer.isEmpty) super.onUpstreamFinish()
    }

    private def deliverBuffer(): Unit = buffer match {
      case DownloadedPiece(index, data) +: bufferTail if (index.toLong * pieceLength) <= currentOffset ⇒
        val pieceOffset = (currentOffset - (index * pieceLength)).toInt
        val chunkLength = Array(data.length.toLong - pieceOffset, currentRange.end - currentOffset).min.toInt
        require(chunkLength > 0)

        buffer = bufferTail
        currentOffset += chunkLength

        val chunk = data.slice(pieceOffset, pieceOffset + chunkLength)
        if (currentOffset >= currentRange.end) {
          if (ranges.tail.nonEmpty) {
            ranges = ranges.tail
            currentRange = ranges.head
            currentOffset = currentRange.start
            push(outlet, chunk)
          } else {
            push(outlet, chunk)
            complete(outlet)
          }
        } else {
          push(outlet, chunk)
        }

      case _ ⇒
        if (isClosed(inlet)) complete(outlet) else pull(inlet)
    }

    setHandlers(inlet, outlet, this)
  }
}