package com.karasiq.bittorrent.protocol

import java.io.IOException

import scala.annotation.tailrec

import akka.NotUsed
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import akka.stream.scaladsl.Flow
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.util.ByteString

import com.karasiq.bittorrent.protocol.PeerMessages._

object PeerConnectionStage {
  private val KeepAliveBytes = ByteString(0, 0, 0, 0)

  def apply(maxBufferSize: Int): Flow[ByteString, TopLevelMessage, NotUsed] = {
    Flow.fromGraph(new PeerConnectionStage(maxBufferSize))
  }
}

private final class PeerConnectionStage(maxBufferSize: Int)
  extends GraphStage[FlowShape[ByteString, TopLevelMessage]] with PeerMessageMatcher {

  val inlet = Inlet[ByteString]("PeerConnectionStage.in")
  val outlet = Outlet[TopLevelMessage]("PeerConnectionStage.out")
  val shape = FlowShape(inlet, outlet)

  def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with InHandler with OutHandler {
    private[this] var handshake = true
    private[this] var buffer = ByteString.empty

    def onPull(): Unit = {
      deliverMessage()
    }

    def onPush(): Unit = {
      val element = grab(inlet)
      if (buffer.length > maxBufferSize) {
        failStage(new IOException("Buffer overflow"))
      } else {
        buffer ++= element
        deliverMessage()
      }
    }

    override def onUpstreamFinish(): Unit = {
      if (buffer.isEmpty) super.onUpstreamFinish()
    }

    @tailrec
    private[this] def readMessage(): Option[TopLevelMessage] = {
      if (handshake) {
        buffer match {
          case Handshake(hs: PeerHandshake) ⇒
            buffer = buffer.drop(hs.length)
            handshake = false
            Some(hs)

          case _ ⇒
            None
        }
      } else {
        buffer match {
          case Msg(message) if message.length - 1 == message.payload.length ⇒
            buffer = buffer.drop(message.length + 4)
            Some(message)

          case bs if bs.take(4) == PeerConnectionStage.KeepAliveBytes ⇒
            // Keep-alive
            buffer = buffer.drop(4)
            readMessage()

          case _ ⇒
            None
        }
      }
    }

    private[this] def deliverMessage(): Unit = {
      readMessage() match {
        case Some(message) ⇒
          push(outlet, message)

        case None ⇒
          if (!isClosed(inlet)) pull(inlet) else complete(outlet)
      }
    }

    setHandlers(inlet, outlet, this)
  }
}
