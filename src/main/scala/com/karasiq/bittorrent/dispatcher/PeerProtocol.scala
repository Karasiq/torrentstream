package com.karasiq.bittorrent.dispatcher

import java.nio.ByteBuffer

import akka.util.ByteString

import scala.collection.{BitSet, mutable}
import scala.language.implicitConversions
import scala.util.Try

/**
  * @see [[https://wiki.theory.org/BitTorrentSpecification#Peer_wire_protocol_.28TCP.29]]
  */
object PeerProtocol {
  trait PeerTcpMessage {
    def toBytes: ByteString
  }

  implicit def toBytesTraitToByteString(t: PeerTcpMessage): ByteString = {
    t.toBytes
  }

  case class PeerHandshake(protocol: String, infoHash: ByteString, peerId: ByteString) extends PeerTcpMessage {
    require(infoHash.length == 20)
    require(peerId.length == 20)

    def toBytes: ByteString = {
      val protocolBytes = protocol.toCharArray.map(_.toByte)
      assert(protocolBytes.length <= Byte.MaxValue)
      val byteBuffer = ByteBuffer.allocate(1 + 8 + protocolBytes.length + 20 + 20)
      byteBuffer.put(protocolBytes.length.toByte)
      byteBuffer.put(protocolBytes)
      byteBuffer.put(ByteString(0, 0, 0, 0, 0, 0, 0, 0).toByteBuffer)
      byteBuffer.put(infoHash.toByteBuffer)
      byteBuffer.put(peerId.toByteBuffer)
      byteBuffer.flip()
      ByteString(byteBuffer)
    }
  }

  case class PeerMessage(id: Int, length: Int, payload: ByteString) extends PeerTcpMessage {
    def toBytes: ByteString = {
      require(length == payload.length + 1)
      val byteBuffer = ByteBuffer.allocate(4 + 1 + payload.length)
      byteBuffer.putInt(payload.length + 1)
      byteBuffer.put(id.toByte)
      byteBuffer.put(payload.toByteBuffer)
      byteBuffer.flip()
      ByteString(byteBuffer)
    }
  }

  object PeerMessage {
    def apply(id: Int, payload: ByteString): PeerMessage = {
      PeerMessage(id, payload.length + 1, payload)
    }
  }

  case class PieceBlockRequest(index: Int, offset: Int, length: Int) extends PeerTcpMessage {
    def toBytes: ByteString = {
      val byteBuffer = ByteBuffer.allocate(4 * 3)
      byteBuffer.putInt(index)
      byteBuffer.putInt(offset)
      byteBuffer.putInt(length)
      byteBuffer.flip()
      ByteString(byteBuffer)
    }
  }

  case class PieceBlock(index: Int, offset: Int, data: ByteString) extends PeerTcpMessage {
    def toBytes: ByteString = {
      val byteBuffer = ByteBuffer.allocate(4 * 2 + data.length)
      byteBuffer.putInt(index)
      byteBuffer.putInt(offset)
      byteBuffer.put(data.toByteBuffer)
      byteBuffer.flip()
      ByteString(byteBuffer)
    }
  }

  sealed class EmptyMessage(id: Int) {
    def unapply(m: PeerMessage): Option[Int] = {
      Option(m).collect {
        case PeerMessage(`id`, length, data) if data.isEmpty ⇒
          id
      }
    }

    def apply(): PeerMessage = {
      PeerMessage(id, ByteString.empty)
    }
  }

  object Msg {
    def unapply(v: ByteString): Option[PeerMessage] = {
      Try {
        val buffer = v.toByteBuffer
        val length = buffer.getInt
        require(length > 0 && buffer.remaining() > 0, "Empty message")
        val id = buffer.get().toInt
        PeerMessage(id, length, ByteString(buffer).take(length - 1))
      }.toOption
    }

    object Handshake {
      def unapply(v: ByteString): Option[PeerHandshake] = {
        Try {
          val buffer = v.toByteBuffer
          val length = buffer.get().toInt
          assert(length == buffer.remaining() - 48)
          val protocol = new Array[Byte](length)
          buffer.get(protocol)
          buffer.position(buffer.position() + 8)
          val infoHash = new Array[Byte](20)
          buffer.get(infoHash)
          val id = new Array[Byte](20)
          buffer.get(id)
          PeerHandshake(new String(protocol, "ASCII"), ByteString(infoHash), ByteString(id))
        }.toOption
      }
    }

    object KeepAlive {
      def unapply(v: ByteString): Option[ByteString] = {
        if (v == this.apply()) Some(v) else None
      }

      def apply(): ByteString = {
        ByteString(0, 0, 0, 0)
      }
    }

    object Choke extends EmptyMessage(0)
    object Unchoke extends EmptyMessage(1)
    object Interested extends EmptyMessage(2)
    object NotInterested extends EmptyMessage(3)
    object Have {
      def unapply(m: PeerMessage): Option[Int] = {
        if (m.id == 4 && m.length == 5) {
          Try {
            val buffer = m.payload.asByteBuffer
            buffer.getInt
          }.toOption
        } else {
          None
        }
      }

      def apply(v: Int): PeerMessage = {
        val buffer = ByteBuffer.allocate(4)
        buffer.putInt(v)
        buffer.flip()
        val bytes = ByteString(buffer)
        require(bytes.length == 4)
        PeerMessage(4, bytes)
      }
    }

    private def readBitField(values: ByteString): BitSet = {
      val buffer = values.toByteBuffer
      val bitSet = new mutable.BitSet(buffer.remaining()*8)
      (0 until buffer.remaining()*8).foreach { i ⇒
        bitSet.update(i, (buffer.get(i/8) & (1 << (7 -(i % 8)))) > 0)
      }
      bitSet
    }

    private def writeBitField(size: Int, values: BitSet): ByteString = {
      val bitfield = new Array[Byte](math.ceil(size.toDouble / 8.0).toInt)
      for (i <- values) {
        bitfield.update(i/8, (bitfield(i/8) | 1 << (7 - (i % 8))).toByte)
      }
      ByteString(bitfield)
    }

    object BitField {
      def unapply(m: PeerMessage): Option[BitSet] = {
        if (m.id == 5) {
          Try(readBitField(m.payload)).toOption
        } else {
          None
        }
      }

      def apply(size: Int, v: BitSet): PeerMessage = {
        PeerMessage(5, writeBitField(size, v))
      }
    }

    object Request {
      def unapply(m: PeerMessage): Option[PieceBlockRequest] = {
        if (m.id == 6 && m.length == 13) {
          Try {
            val buffer = m.payload.toByteBuffer
            val index = buffer.getInt
            val offset = buffer.getInt
            val length = buffer.getInt
            PieceBlockRequest(index, offset, length)
          }.toOption
        } else {
          None
        }
      }

      def apply(index: Int, offset: Int, length: Int): PeerMessage = {
        PeerMessage(6, PieceBlockRequest(index, offset, length).toBytes)
      }
    }

    object Piece {
      def unapply(m: PeerMessage): Option[PieceBlock] = {
        if (m.id == 7) {
          Try {
            val buffer = m.payload.toByteBuffer
            val index = buffer.getInt
            val offset = buffer.getInt
            PieceBlock(index, offset, ByteString(buffer))
          }.toOption
        } else {
          None
        }
      }

      def apply(index: Int, offset: Int, data: ByteString): PeerMessage = {
        PeerMessage(7, PieceBlock(index, offset, data).toBytes)
      }
    }

    object Cancel {
      def unapply(m: PeerMessage): Option[PieceBlockRequest] = {
        if (m.id == 8) {
          Try {
            val buffer = m.payload.toByteBuffer
            val index = buffer.getInt
            val offset = buffer.getInt
            val length = buffer.getInt
            PieceBlockRequest(index, offset, length)
          }.toOption
        } else {
          None
        }
      }

      def apply(index: Int, offset: Int, length: Int): PeerMessage = {
        PeerMessage(8, PieceBlockRequest(index, offset, length).toBytes)
      }
    }

    object Port {
      def unapply(m: PeerMessage): Option[Int] = {
        if (m.id == 9) {
          Try {
            BigInt((ByteString(0, 0) ++ m.payload.take(2)).toArray).intValue()
          }.toOption
        } else {
          None
        }
      }

      def apply(port: Int): PeerMessage = {
        val buffer = ByteBuffer.allocate(2)
        buffer.putShort(port.toShort)
        buffer.flip()
        PeerMessage(9, ByteString(buffer))
      }
    }
  }
}
























