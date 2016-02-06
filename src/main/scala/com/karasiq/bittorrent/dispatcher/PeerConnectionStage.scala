package com.karasiq.bittorrent.dispatcher

import java.io.IOException

import akka.stream.stage.{Context, PushStage, SyncDirective}
import akka.util.ByteString
import com.karasiq.bittorrent.dispatcher.PeerProtocol.{Msg, PeerHandshake, PeerTcpMessage}

class PeerConnectionStage(maxBufferSize: Int) extends PushStage[ByteString, PeerTcpMessage] {
  private var handshake = true
  private var buffer = ByteString.empty

  private def tryParseMessage(ctx: Context[PeerTcpMessage]): SyncDirective = {
    if (handshake) {
      buffer match {
        case Msg.Handshake(hs: PeerHandshake) ⇒
          buffer = buffer.drop(hs.toBytes.length)
          handshake = false
          ctx.push(hs)

        case _ ⇒
          ctx.pull()
      }
    } else {
      buffer match {
        case Msg(message) if message.length - 1 == message.payload.length ⇒
          buffer = buffer.drop(message.length + 4)
          ctx.push(message)

        case bs if bs.take(4).forall(_ == 0) ⇒
          // Keep-alive
          buffer = buffer.drop(4)
          ctx.pull()

        case _ ⇒
          ctx.pull()
      }
    }
  }

  override def onPush(elem: ByteString, ctx: Context[PeerTcpMessage]): SyncDirective = {
    if (buffer.length > maxBufferSize) {
      ctx.fail(new IOException("Buffer overflow"))
    } else {
      buffer ++= elem
      tryParseMessage(ctx)
    }
  }
}
