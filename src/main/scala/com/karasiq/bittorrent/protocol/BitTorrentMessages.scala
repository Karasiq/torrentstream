package com.karasiq.bittorrent.protocol

import akka.util.ByteString

import scala.collection.BitSet

trait BitTorrentMessages { self: TcpMessageSpecification ⇒
  case class PeerHandshake(protocol: String, infoHash: ByteString, peerId: ByteString) extends TopLevelMessage {
    require(infoHash.length == 20)
    require(peerId.length == 20)
  }

  case class PeerMessage(id: Int, length: Int, payload: ByteString) extends TopLevelMessage

  object PeerMessage {
    def apply(id: Int, payload: ByteString): PeerMessage = {
      apply(id, payload.length + 1, payload)
    }

    def apply(id: Int): PeerMessage = {
      apply(id, ByteString.empty)
    }
  }

  case class PieceBlockRequest(index: Int, offset: Int, length: Int)

  case class PieceBlock(index: Int, offset: Int, data: ByteString)

  case class HavePiece(index: Int)

  case class BitField(pieces: Int, completed: BitSet)

  case class Port(port: Int)

  case object KeepAlive
}
