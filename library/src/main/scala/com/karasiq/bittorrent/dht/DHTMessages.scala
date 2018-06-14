package com.karasiq.bittorrent.dht

import java.net.{InetAddress, InetSocketAddress}
import java.nio.ByteOrder

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer
import scala.util.{Random, Try}

import akka.util.ByteString

import com.karasiq.bittorrent.format.{BEncodedArray, BEncodedDictionary, BEncodedNumber, BEncodedString}
import com.karasiq.bittorrent.format.BEncodeImplicits._
import com.karasiq.bittorrent.protocol.{BitTorrentTcpProtocol, TcpMessageProtocol}

object DHTMessages {
  def transactionId(): ByteString = {
    ByteString(Random.nextInt(256).toByte, Random.nextInt(256).toByte)
  }

  sealed trait DHTMessage

  trait DHTMessageFormat[T <: DHTMessage] {
    def toDict(message: T): BEncodedDictionary

    def fromDict(dictionary: BEncodedDictionary): T

    object Encoded {
      def unapply(dictionary: BEncodedDictionary): Option[T] = {
        Try(fromDict(dictionary)).toOption
      }
    }

    implicit class DHTMessageConversion(message: T) {
      def toDict: BEncodedDictionary = DHTMessageFormat.this.toDict(message)
    }
  }

  final case class DHTMessageHeader(txId: ByteString, `type`: Char, version: String) extends DHTMessage

  object DHTMessageHeader extends DHTMessageFormat[DHTMessageHeader] {
    def toDict(message: DHTMessageHeader): BEncodedDictionary = {
      BEncodedDictionary("t" → BEncodedString(message.txId), "y" → BEncodedString("" + message.`type`), "v" → BEncodedString(message.version))
    }

    def fromDict(dictionary: BEncodedDictionary): DHTMessageHeader = {
      val map = dictionary.toMap
      DHTMessageHeader(map.bytes("t").get, map.string("y").get.head, map.string("v").getOrElse(""))
    }
  }

  final case class DHTQuery(`type`: String, arguments: BEncodedDictionary) extends DHTMessage

  object DHTQuery extends DHTMessageFormat[DHTQuery] {
    def toDict(message: DHTQuery): BEncodedDictionary = BEncodedDictionary(
      "q" → BEncodedString(message.`type`),
      "a" → message.arguments
    )

    def fromDict(dictionary: BEncodedDictionary): DHTQuery = {
      DHTQuery(dictionary("q").asString, dictionary("a").asDict)
    }
  }

  object DHTQueries {
    def ping(selfNodeId: NodeId): DHTQuery = {
      DHTQuery("ping", Ping(selfNodeId).toDict)
    }

    def findNode(selfNodeId: NodeId, target: NodeId): DHTQuery = {
      DHTQuery("find_node", FindNode(selfNodeId, target).toDict)
    }

    def getPeers(selfNodeId: NodeId, infoHash: ByteString): DHTQuery = {
      DHTQuery("get_peers", GetPeers(selfNodeId, infoHash).toDict)
    }

    def announcePeer(selfNodeId: NodeId, infoHash: ByteString, port: Int, token: ByteString): DHTQuery = {
      DHTQuery("announce_peer", AnnouncePeer(selfNodeId, infoHash, port, token).toDict)
    }
  }

  final case class DHTError(code: Int, message: String) extends Exception(s"DHT error #$code: $message") with DHTMessage

  object DHTError extends DHTMessageFormat[DHTError] {
    def toDict(message: DHTError): BEncodedDictionary = BEncodedDictionary(
      "e" → BEncodedArray(BEncodedNumber(message.code), BEncodedString(message.message))
    )

    def fromDict(dictionary: BEncodedDictionary): DHTError = {
      dictionary.toMap.array("e") match {
        case BEncodedNumber(code) +: BEncodedString(message) +: Nil ⇒
          DHTError(code.toInt, message.utf8String)

        case _ ⇒
          DHTError(0, "")
      }
    }
  }

  final case class PeerAddress(address: InetSocketAddress) extends AnyVal

  object PeerAddress extends TcpMessageProtocol[PeerAddress] {
    def toBytes(pa: PeerAddress): ByteString = {
      implicit val byteOrder = ByteOrder.BIG_ENDIAN
      val builder = ByteString.newBuilder
      builder.sizeHint(6)
      builder.putBytes(pa.address.getAddress.getAddress)
      builder.putShort(pa.address.getPort)
      builder.result()
    }

    def fromBytes(bytes: ByteString): Option[PeerAddress] = {
      // require(bytes.length == 6, "Invalid address length")
      if (bytes.length == 6) parseList(bytes).headOption else None
    }

    def parseList(bytes: ByteString): Seq[PeerAddress] = {
      def readPort(bytes: ByteString): Int = {
        require(bytes.length == 2)
        BitTorrentTcpProtocol.int32FromBytes(bytes)
      }

      @tailrec
      def parsePeerString(peers: ArrayBuffer[PeerAddress], ps: ByteString): Seq[PeerAddress] = {
        if (ps.isEmpty) {
          peers.result()
        } else {
          val address = new InetSocketAddress(InetAddress.getByAddress(ps.take(4).toArray), readPort(ps.drop(4).take(2)))
          parsePeerString(peers :+ PeerAddress(address), ps.drop(6))
        }
      }
      parsePeerString(new ArrayBuffer[PeerAddress](bytes.length / 6), bytes)
    }

    def encodeList(addresses: Seq[PeerAddress]): ByteString = {
      addresses.map(_.toBytes).fold(ByteString.empty)(_ ++ _)
    }
  }

  final case class DHTNodeAddress(nodeId: NodeId, address: InetSocketAddress)

  object DHTNodeAddress extends TcpMessageProtocol[DHTNodeAddress] {
    def toBytes(na: DHTNodeAddress): ByteString = {
      implicit val byteOrder = ByteOrder.BIG_ENDIAN
      val builder = ByteString.newBuilder
      builder.sizeHint(26)
      builder.append(na.nodeId.bytes)
      builder.putBytes(na.address.getAddress.getAddress)
      builder.putShort(na.address.getPort)
      builder.result()
    }

    def fromBytes(bs: ByteString): Option[DHTNodeAddress] = {
      if (bs.length == 26) parseList(bs).headOption else None
    }

    def parseList(bytes: ByteString): Seq[DHTNodeAddress] = {
      def readPort(bytes: ByteString): Int = {
        require(bytes.length == 2)
        BitTorrentTcpProtocol.int32FromBytes(bytes)
      }

      @tailrec
      def parsePeerString(peers: ArrayBuffer[DHTNodeAddress], ps: ByteString): Seq[DHTNodeAddress] = {
        if (ps.isEmpty) {
          peers.result()
        } else {
          val nodeId = NodeId(ps.take(20))
          val address = new InetSocketAddress(InetAddress.getByAddress(ps.drop(20).take(4).toArray), readPort(ps.drop(24).take(2)))
          parsePeerString(peers :+ DHTNodeAddress(nodeId, address), ps.drop(26))
        }
      }
      parsePeerString(new ArrayBuffer[DHTNodeAddress](bytes.length / 26), bytes)
    }

    def encodeList(addresses: Seq[DHTNodeAddress]): ByteString = {
      addresses.map(_.toBytes).fold(ByteString.empty)(_ ++ _)
    }
  }

  final case class Ping(nodeId: NodeId) extends DHTMessage

  object Ping extends DHTMessageFormat[Ping] {
    def toDict(message: Ping): BEncodedDictionary = BEncodedDictionary(
      "id" → message.nodeId.encoded
    )

    def fromDict(dictionary: BEncodedDictionary): Ping = {
      val nodeId = NodeId(dictionary.toMap("id").asByteString)
      Ping(nodeId)
    }
  }

  final case class PingResponse(nodeId: NodeId) extends DHTMessage

  object PingResponse extends DHTMessageFormat[PingResponse] {
    def toDict(message: PingResponse): BEncodedDictionary = BEncodedDictionary(
      "id" → message.nodeId.encoded
    )

    def fromDict(dictionary: BEncodedDictionary): PingResponse = {
      val nodeId = NodeId(dictionary.toMap("id").asByteString)
      PingResponse(nodeId)
    }
  }

  final case class FindNode(nodeId: NodeId, target: NodeId) extends DHTMessage

  object FindNode extends DHTMessageFormat[FindNode] {
    def toDict(message: FindNode): BEncodedDictionary = BEncodedDictionary(
      "id" → message.nodeId.encoded,
      "target" → message.target.encoded
    )

    def fromDict(dictionary: BEncodedDictionary): FindNode = {
      FindNode(NodeId(dictionary("id").asByteString), NodeId(dictionary("target").asByteString))
    }
  }

  final case class FindNodeResponse(nodeId: NodeId, nodes: Seq[DHTNodeAddress]) extends DHTMessage

  object FindNodeResponse extends DHTMessageFormat[FindNodeResponse] {
    def toDict(message: FindNodeResponse): BEncodedDictionary = BEncodedDictionary(
      "id" → message.nodeId.encoded,
      "nodes" → BEncodedString(DHTNodeAddress.encodeList(message.nodes))
    )

    def fromDict(dictionary: BEncodedDictionary): FindNodeResponse = {
      val id = NodeId(dictionary("id").asByteString)
      val nodes = DHTNodeAddress.parseList(dictionary("nodes").asByteString)
      FindNodeResponse(id, nodes)
    }
  }

  final case class GetPeers(nodeId: NodeId, infoHash: ByteString) extends DHTMessage

  object GetPeers extends DHTMessageFormat[GetPeers] {
    def toDict(message: GetPeers): BEncodedDictionary = BEncodedDictionary(
      "id" → message.nodeId.encoded,
      "info_hash" → BEncodedString(message.infoHash)
    )

    def fromDict(dictionary: BEncodedDictionary): GetPeers = {
      GetPeers(NodeId(dictionary("id").asByteString), dictionary("info_hash").asByteString)
    }
  }

  final case class GetPeersResponse(nodeId: NodeId, token: ByteString, peers: Seq[PeerAddress], nodes: Seq[DHTNodeAddress]) extends DHTMessage

  object GetPeersResponse extends DHTMessageFormat[GetPeersResponse] {
    def toDict(message: GetPeersResponse): BEncodedDictionary = {
      val baseValues = Seq("id" → message.nodeId.encoded, "token" → BEncodedString(message.token))
      val peersValue = if (message.peers.nonEmpty) Seq(
        "values" → BEncodedArray(message.peers.map(pa ⇒ BEncodedString(pa.toBytes)))
      ) else Seq(
        "nodes" → BEncodedString(DHTNodeAddress.encodeList(message.nodes))
      )
      BEncodedDictionary(baseValues ++ peersValue)
    }

    def fromDict(dictionary: BEncodedDictionary): GetPeersResponse = {
      val map = dictionary.toMap
      val id = NodeId(map.bytes("id").get)
      val token = map.bytes("token").getOrElse(ByteString.empty)
      val peers = map.array("values").flatMap(value ⇒ PeerAddress.fromBytes(value.asByteString))
      val nodes = map.bytes("nodes").toVector.flatMap(DHTNodeAddress.parseList)
      GetPeersResponse(id, token, peers, nodes)
    }
  }

  final case class AnnouncePeer(nodeId: NodeId, infoHash: ByteString, port: Int, token: ByteString) extends DHTMessage

  object AnnouncePeer extends DHTMessageFormat[AnnouncePeer] {
    def toDict(message: AnnouncePeer): BEncodedDictionary = BEncodedDictionary(
      "id" → message.nodeId.encoded,
      "info_hash" → BEncodedString(message.infoHash),
      "port" → BEncodedNumber(message.port),
      "implied_port" → (if (message.port == 0) BEncodedNumber(1) else BEncodedNumber(0)),
      "token" → BEncodedString(message.token)
    )

    def fromDict(dictionary: BEncodedDictionary): AnnouncePeer = {
      val map = dictionary.toMap
      val nodeId = NodeId(map.bytes("id").get)
      val port = if (map.int("implied_port").contains(1)) 0 else map.int("port").get
      val infoHash = map.bytes("info_hash").get
      val token = map.bytes("token").get
      AnnouncePeer(nodeId, infoHash, port, token)
    }
  }

  final case class AnnouncePeerResponse(nodeId: NodeId) extends DHTMessage

  object AnnouncePeerResponse extends DHTMessageFormat[AnnouncePeerResponse] {
    def toDict(message: AnnouncePeerResponse): BEncodedDictionary = BEncodedDictionary(
      "id" → message.nodeId.encoded
    )

    def fromDict(dictionary: BEncodedDictionary): AnnouncePeerResponse = {
      val nodeId = NodeId(dictionary("id").asByteString)
      AnnouncePeerResponse(nodeId)
    }
  }
}
