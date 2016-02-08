package com.karasiq.bittorrent.protocol

import akka.util.ByteString

import scala.language.implicitConversions

/**
  * @see [[https://wiki.theory.org/BitTorrentSpecification#Peer_wire_protocol_.28TCP.29]]
  */
object PeerMessages extends TcpMessageSpecification with BitTorrentMessages with BitTorrentTcpProtocol {
  implicit def peerTcpMessageAsByteString[T](value: T)(implicit ev: TcpMessageWriter[T]): ByteString = {
    ev.toBytes(value)
  }

  trait PeerMessageMatcher {
    object Msg {
      def unapply(msg: ByteString): Option[PeerMessage] = {
        payload[PeerMessage](msg)
      }

      def payload[T](msg: ByteString)(implicit ev: TcpMessageReader[T]): Option[T] = {
        ev.fromBytes(msg)
      }
    }

    object Handshake {
      def unapply(msg: ByteString): Option[PeerHandshake] = {
        Msg.payload[PeerHandshake](msg)
      }
    }

    sealed class PayloadMatcher[T: TcpMessageReader](id: Int) {
      def unapply(pm: PeerMessage): Option[T] = {
        for (payload <- Msg.payload[T](pm.payload) if pm.id == id) yield {
          payload
        }
      }
    }

    object RequestMsg extends PayloadMatcher[PieceBlockRequest](PeerMessageId.REQUEST)
    object PieceMsg extends PayloadMatcher[PieceBlock](PeerMessageId.PIECE)
    object HaveMsg extends PayloadMatcher[HavePiece](PeerMessageId.HAVE)
    object BitFieldMsg extends PayloadMatcher[BitField](PeerMessageId.BITFIELD)

    object EmptyMsg {
      def unapply(msg: PeerMessage): Option[Int] = {
        if (msg.payload.isEmpty) Some(msg.id) else None
      }
    }
  }
}
























