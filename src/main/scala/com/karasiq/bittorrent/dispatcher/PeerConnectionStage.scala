package com.karasiq.bittorrent.dispatcher

import java.io.IOException

import akka.stream.stage._
import akka.util.ByteString
import com.karasiq.bittorrent.protocol.PeerMessages._

import scala.annotation.tailrec

class PeerConnectionStage(maxBufferSize: Int) extends PushPullStage[ByteString, TopLevelMessage] with PeerMessageMatcher {
  private var handshake = true
  private var buffer = ByteString.empty

  override def onUpstreamFinish(ctx: Context[TopLevelMessage]): TerminationDirective = {
    if (buffer.isEmpty) ctx.finish()
    else ctx.absorbTermination()
  }

  @tailrec
  private def readMessage(): Option[TopLevelMessage] = {
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

        case bs if bs.take(4) == ByteString(0, 0, 0, 0) ⇒
          // Keep-alive
          buffer = buffer.drop(4)
          readMessage()

        case _ ⇒
          None
      }
    }
  }

  private def deliverMessage(ctx: Context[TopLevelMessage]): SyncDirective = {
    readMessage() match {
      case Some(message) ⇒
        ctx.push(message)

      case None if ctx.isFinishing ⇒
        ctx.finish()

      case None ⇒
        ctx.pull()
    }
  }

  override def onPush(elem: ByteString, ctx: Context[TopLevelMessage]): SyncDirective = {
    if (buffer.length > maxBufferSize) {
      ctx.fail(new IOException("Buffer overflow"))
    } else {
      buffer ++= elem
      deliverMessage(ctx)
    }
  }

  override def onPull(ctx: Context[TopLevelMessage]): SyncDirective = {
    deliverMessage(ctx)
  }
}
