package com.karasiq.bittorrent.dispatcher

import java.nio.ByteBuffer

import akka.util.ByteString
import org.parboiled2._

import scala.collection.{BitSet, mutable}
import scala.util.Try

/**
  * @see [[https://wiki.theory.org/BitTorrentSpecification#Peer_wire_protocol_.28TCP.29]]
  */
object PeerProtocol {
  case class PeerHandshake(protocol: String, infoHash: ByteString, peerId: ByteString) {
    require(infoHash.length == 20)
    require(peerId.length == 20)

    def toBytes: ByteString = {
      val protocolBytes = protocol.getBytes("ASCII")
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

  case class PeerMessage(id: Int, payload: ByteString) {
    def toBytes: ByteString = {
      val byteBuffer = ByteBuffer.allocate(4 + 1 + payload.length)
      byteBuffer.putInt(payload.length + 1)
      byteBuffer.put(id.toByte)
      byteBuffer.put(payload.toByteBuffer)
      byteBuffer.flip()
      ByteString(byteBuffer)
    }
  }

  case class PieceRequest(index: Int, offset: Int, length: Int) {
    def toBytes: ByteString = {
      val byteBuffer = ByteBuffer.allocate(4 * 3)
      byteBuffer.putInt(index)
      byteBuffer.putInt(offset)
      byteBuffer.putInt(length)
      byteBuffer.flip()
      ByteString(byteBuffer)
    }
  }

  case class PieceBlock(index: Int, offset: Int, data: ByteString) {
    def toBytes: ByteString = {
      val byteBuffer = ByteBuffer.allocate(4 * 2 + data.length)
      byteBuffer.putInt(index)
      byteBuffer.putInt(offset)
      byteBuffer.put(data.toByteBuffer)
      byteBuffer.flip()
      ByteString(byteBuffer)
    }
  }

  class Reader(val input: ParserInput) extends Parser {
    def ByteNumber: Rule1[Int] = rule { capture(CharPredicate.All) ~>
      { (v: String) ⇒ v.getBytes("ASCII")(0).toInt }
    }

    def ByteFrame(length: Int): Rule1[ByteString] = rule { capture(20.times(CharPredicate.All)) ~>
      { (v: String) ⇒ ByteString(v.getBytes("ASCII")) }
    }

    def SizedString: Rule1[String] = rule { ByteNumber ~>
      { (length: Int) ⇒ capture(length.times(CharPredicate.All)) }
    }

    def Handshake: Rule1[PeerHandshake] = rule { SizedString ~ 8.times(CharPredicate.All) ~ ByteFrame(20) ~ ByteFrame(20) ~> PeerHandshake }

    def FourByteNumber: Rule1[Int] = rule { ByteFrame(4) ~>
      { (b: ByteString) ⇒ b.toByteBuffer.getInt }
    }

    def KeepAlive: Rule0 = rule { FourByteNumber ~> ((i: Int) ⇒ test(i == 0)) }
  }

  sealed class EmptyMessage(id: Int) {
    def unapplySeq(m: PeerMessage): Option[Seq[Unit]] = {
      Option(m).filter(_.id == id).map(_ ⇒ Seq())
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
        val id = buffer.get().toInt
        val array = new Array[Byte](length)
        buffer.get(array)
        PeerMessage(id, ByteString(array))
      }.toOption
    }

    object Handshake {
      def unapply(v: ByteString): Option[PeerHandshake] = {
        new Reader(v.toArray[Byte]).Handshake.run().toOption
      }
    }

    object KeepAlive {
      def unapplySeq(v: ByteString): Option[Seq[Unit]] = {
        if (v == ByteString(0, 0, 0, 0)) Some(Seq()) else None
      }
    }

    object Choke extends EmptyMessage(0)
    object Unchoke extends EmptyMessage(1)
    object Interested extends EmptyMessage(2)
    object NotInterested extends EmptyMessage(3)
    object Have {
      def unapply(m: PeerMessage): Option[Int] = {
        if (m.id == 4) {
          Try(BigInt(m.payload.toArray[Byte]).intValue()).toOption
        } else {
          None
        }
      }

      def apply(v: Int): PeerMessage = {
        PeerMessage(4, ByteString(BigInt(v).toByteArray))
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

    private def writeBitField(values: BitSet): ByteString = {
      val bitfield = new Array[Byte](values.size)
      for (i <- values) {
        bitfield(i/8) |= 1 << (7 -(i % 8))
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

      def apply(v: BitSet): PeerMessage = {
        PeerMessage(5, writeBitField(v))
      }
    }

    object Request {
      def unapply(m: PeerMessage): Option[PieceRequest] = {
        if (m.id == 6) {
          Try {
            val buffer = m.payload.toByteBuffer
            val index = buffer.getInt
            val offset = buffer.getInt
            val length = buffer.getInt
            PieceRequest(index, offset, length)
          }.toOption
        } else {
          None
        }
      }
    }

    object Piece {
      def unapply(m: PeerMessage): Option[PieceBlock] = {
        if (m.id == 7) {
          Try {
            val buffer = m.payload.toByteBuffer
            val index = buffer.getInt
            val offset = buffer.getInt
            val array = new Array[Byte](buffer.remaining())
            buffer.get(array)
            PieceBlock(index, offset, ByteString(array))
          }.toOption
        } else {
          None
        }
      }
    }

    object Cancel {
      def unapply(m: PeerMessage): Option[PieceRequest] = {
        if (m.id == 8) {
          Try {
            val buffer = m.payload.toByteBuffer
            val index = buffer.getInt
            val offset = buffer.getInt
            val length = buffer.getInt
            PieceRequest(index, offset, length)
          }.toOption
        } else {
          None
        }
      }
    }

    object Port {
      def unapply(m: PeerMessage): Option[Int] = {
        if (m.id == 9) {
          Try {
            val buffer = m.payload.toByteBuffer
            val array = new Array[Byte](2)
            buffer.get(array)
            BigInt(array).intValue()
          }.toOption
        } else {
          None
        }
      }
    }
  }
}
























