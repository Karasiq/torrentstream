package com.karasiq.bittorrent.protocol

import java.nio.ByteBuffer

import akka.util.ByteString
import com.karasiq.bittorrent.protocol.extensions.PeerExtensions

import scala.collection.BitSet
import scala.util.Try

private[protocol] object BitTorrentTcpProtocol {
  implicit class ByteBufferOps(val bb: ByteBuffer) extends AnyVal {
    def getByteString(size: Int): ByteString = {
      val array = new Array[Byte](Seq(size, bb.remaining()).min)
      bb.get(array)
      ByteString(array)
    }

    def getByteInt: Int = {
      val byte = bb.get()
      BigInt(ByteString(0, 0, 0, byte).toArray).intValue()
    }
  }
}

trait BitTorrentTcpProtocol { self: BitTorrentMessages ⇒
  import BitTorrentTcpProtocol._

  implicit object PeerHandshakeTcpProtocol extends TcpMessageProtocol[PeerHandshake] {
    override def toBytes(ph: PeerHandshake): ByteString = {
      val protocolBytes = ph.protocol.toCharArray.map(_.toByte)
      assert(protocolBytes.length <= Byte.MaxValue)
      val byteBuffer = ByteBuffer.allocate(1 + 8 + protocolBytes.length + 20 + 20)
      byteBuffer.put(protocolBytes.length.toByte)
      byteBuffer.put(protocolBytes)
      byteBuffer.put(ph.extensions.toBytes)
      byteBuffer.put(ph.infoHash.toByteBuffer)
      byteBuffer.put(ph.peerId.toByteBuffer)
      byteBuffer.flip()
      ByteString(byteBuffer)
    }

    override def fromBytes(bs: ByteString): Option[PeerHandshake] = {
      Try {
        val buffer = bs.toByteBuffer
        val length = buffer.getByteInt
        assert(length <= buffer.remaining() - 48)
        val protocol = buffer.getByteString(length)
        val reserved = buffer.getByteString(8)
        val infoHash = buffer.getByteString(20)
        val id = buffer.getByteString(20)
        PeerHandshake(new String(protocol.toArray, "ASCII"), infoHash, id, PeerExtensions.fromBytes(reserved.toArray))
      }.toOption
    }
  }

  implicit object PeerMessageTcpProtocol extends TcpMessageProtocol[PeerMessage] {
    override def toBytes(pm: PeerMessage): ByteString = {
      require(pm.length == pm.payload.length + 1)
      val byteBuffer = ByteBuffer.allocate(4 + 1 + pm.payload.length)
      byteBuffer.putInt(pm.payload.length + 1)
      byteBuffer.put(pm.id.toByte)
      byteBuffer.put(pm.payload.toByteBuffer)
      byteBuffer.flip()
      ByteString(byteBuffer)
    }

    override def fromBytes(bs: ByteString): Option[PeerMessage] = {
      Try {
        val buffer = bs.toByteBuffer
        val length = buffer.getInt
        assert(length > 0 && buffer.remaining() >= length, "Buffer underflow")
        val id = buffer.getByteInt
        PeerMessage(id, length, ByteString(buffer).take(length - 1))
      }.toOption
    }
  }

  implicit object HaveMessageTcpProtocol extends TcpMessageProtocol[PieceIndex] {
    override def toBytes(hp: PieceIndex): ByteString = {
      val buffer = ByteBuffer.allocate(4)
      buffer.putInt(hp.index)
      buffer.flip()
      ByteString(buffer)
    }

    override def fromBytes(bs: ByteString): Option[PieceIndex] = {
      Try {
        val buffer = bs.asByteBuffer
        PieceIndex(buffer.getInt)
      }.toOption
    }
  }

  implicit object BitFieldMessageTcpProtocol extends TcpMessageProtocol[BitField] {
    private def readBitField(values: ByteString): (Int, BitSet) = {
      val buffer = values.toByteBuffer
      val length = buffer.remaining()*8
      val bitSet = new scala.collection.mutable.BitSet(length)
      (0 until buffer.remaining()*8).foreach { i ⇒
        bitSet.update(i, (buffer.get(i/8) & (1 << (7 -(i % 8)))) > 0)
      }
      length → bitSet
    }

    private def writeBitField(size: Int, values: BitSet): ByteString = {
      val bitfield = new Array[Byte](math.ceil(size.toDouble / 8.0).toInt)
      for (i <- values) {
        bitfield.update(i/8, (bitfield(i/8) | 1 << (7 - (i % 8))).toByte)
      }
      ByteString(bitfield)
    }

    override def toBytes(bf: BitField): ByteString = {
      writeBitField(bf.pieces, bf.completed)
    }

    override def fromBytes(bs: ByteString): Option[BitField] = {
      Try {
        val (size, completed) = readBitField(bs)
        BitField(size, completed)
      }.toOption
    }
  }

  implicit object PieceBlockRequestTcpProtocol extends TcpMessageProtocol[PieceBlockRequest] {
    override def toBytes(pbr: PieceBlockRequest): ByteString = {
      val byteBuffer = ByteBuffer.allocate(4 * 3)
      byteBuffer.putInt(pbr.index)
      byteBuffer.putInt(pbr.offset)
      byteBuffer.putInt(pbr.length)
      byteBuffer.flip()
      ByteString(byteBuffer)
    }

    override def fromBytes(bs: ByteString): Option[PieceBlockRequest] = {
      Try {
        val buffer = bs.toByteBuffer
        val index = buffer.getInt
        val offset = buffer.getInt
        val length = buffer.getInt
        PieceBlockRequest(index, offset, length)
      }.toOption
    }
  }

  implicit object PieceBlockTcpProtocol extends TcpMessageProtocol[PieceBlock] {
    override def toBytes(pb: PieceBlock): ByteString = {
      val byteBuffer = ByteBuffer.allocate(4 * 2 + pb.data.length)
      byteBuffer.putInt(pb.index)
      byteBuffer.putInt(pb.offset)
      byteBuffer.put(pb.data.toByteBuffer)
      byteBuffer.flip()
      ByteString(byteBuffer)
    }

    override def fromBytes(bs: ByteString): Option[PieceBlock] = {
      Try {
        val buffer = bs.toByteBuffer
        val index = buffer.getInt
        val offset = buffer.getInt
        PieceBlock(index, offset, ByteString(buffer))
      }.toOption
    }
  }

  implicit object PortTcpProtocol extends TcpMessageProtocol[Port] {
    override def toBytes(p: Port): ByteString = {
      val buffer = ByteBuffer.allocate(2)
      buffer.putShort(p.port.toShort)
      buffer.flip()
      ByteString(buffer)
    }

    override def fromBytes(bs: ByteString): Option[Port] = {
      Try {
        Port(BigInt((ByteString(0, 0) ++ bs.take(2)).toArray).intValue())
      }.toOption
    }
  }

  implicit object KeepAliveTcpProtocol extends TcpMessageProtocol[KeepAlive.type] {
    override def toBytes(value: KeepAlive.type): ByteString = {
      ByteString(0, 0, 0, 0)
    }

    override def fromBytes(bs: ByteString): Option[KeepAlive.type] = {
      if (bs.take(4) == ByteString(0, 0, 0, 0)) Some(KeepAlive) else None
    }
  }
}
