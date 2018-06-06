package com.karasiq.bittorrent.dht

import java.net.InetSocketAddress
import java.security.MessageDigest

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

import akka.actor.{Actor, ActorLogging, PossiblyHarmful, Props}
import akka.pattern.{ask, pipe}
import akka.util.{ByteString, Timeout}

import com.karasiq.bittorrent.dht.DHTBootstrapQueue.PingNode
import com.karasiq.bittorrent.dht.DHTBucket.FindNodes
import com.karasiq.bittorrent.dht.DHTMessageDispatcher.{RequestResponse, SendQuery}
import com.karasiq.bittorrent.dht.DHTMessages._
import com.karasiq.bittorrent.utils.Utils

object DHTRoutingTable {
  // Messages
  sealed trait Message
  final case class AddNode(address: InetSocketAddress) extends Message
  final case class FindPeers(infoHash: ByteString, selfPort: Int = 0) extends Message
  object FindPeers {
    sealed trait Status
    final case class Success(peers: Seq[InetSocketAddress]) extends Status
  }

  // Internal messages
  private sealed trait InternalMessage extends Message with PossiblyHarmful
  private case object UpdateSecret extends InternalMessage
  private final case class AddPeer(infoHash: ByteString, address: InetSocketAddress) extends InternalMessage

  // Events
  sealed trait Event

  // Props
  def props: Props = {
    Props(new DHTRoutingTable)
  }
}

class DHTRoutingTable extends Actor with ActorLogging {
  import context.dispatcher

  import DHTRoutingTable._
  private[this] implicit val timeout = Timeout(3 seconds)

  private[this] object dhtSettings {
    private[this] val config = context.system.settings.config.getConfig("karasiq.bittorrent.dht")

    val port = config.getInt("port")
    val bootstrapNodes = config.getStringList("bootstrap-nodes").asScala
      .map(_.split(":", 2))
      .collect { case Array(host, port) ⇒ new InetSocketAddress(host, port.toInt) }
  }

  val peersTable = DHTPeersTable()
  val messageDispatcher = context.actorOf(DHTMessageDispatcher.props(self, dhtSettings.port), "dhtMessageDispatcher")
  val dhtContext = DHTContext(NodeId.generate(), self, messageDispatcher)
  val rootBucket = context.actorOf(DHTBucket.props(dhtContext, 0, BigInt(2) pow 160))
  val bootstrapQueue = context.actorOf(DHTBootstrapQueue.props(dhtContext, rootBucket), "dhtBootstrapQueue")

  override def receive: Receive = {
    case AddNode(address) ⇒
      log.debug("Adding DHT node: {}", address)
      bootstrapQueue ! PingNode(address)

    case FindPeers(infoHash, selfPort) ⇒
      val localPeers = peersTable.getPeers(infoHash)
      (rootBucket ? FindNodes(NodeId(infoHash)))
        .mapTo[FindNodes.Success]
        .flatMap { case FindNodes.Success(addresses) ⇒
          if (log.isDebugEnabled)
            log.debug("Finding peers for {} from {}", Utils.toHexString(infoHash), addresses)

          val futures = addresses.map { address ⇒
            (messageDispatcher ? SendQuery(address.address, DHTQueries.getPeers(dhtContext.selfNodeId, infoHash)))
              .mapTo[SendQuery.Status]
              .recover { case exc ⇒ SendQuery.Failure(exc) }
              .map((address, _))
          }

          Future.sequence(futures)
        }
        .map { responses ⇒
          val peersBuffer = new ArrayBuffer[InetSocketAddress]()
          responses.foreach {
            case (address, SendQuery.Success(GetPeersResponse.Encoded(GetPeersResponse(_, token, peers, nodes)))) ⇒
              if (selfPort != 0) messageDispatcher ! SendQuery(address.address, DHTQueries.announcePeer(dhtContext.selfNodeId, infoHash, selfPort, token))
              peersBuffer ++= peers.map(_.address)
              nodes.foreach(na ⇒ bootstrapQueue ! PingNode(na.address))
          }

          peersBuffer.foreach(self ! AddPeer(infoHash, _))
          peersBuffer.toVector
        }
        .map(peers ⇒ FindPeers.Success(localPeers ++ peers))
        .pipeTo(sender())

    case RequestResponse(address, txId, message) ⇒
      def failure() = {
        sender() ! RequestResponse.Failure(new Exception("Invalid request"))
      }

      message.`type` match {
        case "ping" ⇒
          sender() ! RequestResponse.Success(PingResponse(dhtContext.selfNodeId).toDict)

        case "find_node" ⇒
          message.arguments match {
            case FindNode.Encoded(FindNode(_, target)) ⇒
              (rootBucket ? FindNodes(target)).mapTo[FindNodes.Success]
                .map(fns ⇒ RequestResponse.Success(FindNodeResponse(dhtContext.selfNodeId, fns.nodes).toDict))
                .pipeTo(sender())

            case _ ⇒ failure()
          }

        case "get_peers" ⇒
          message.arguments match {
            case GetPeers.Encoded(GetPeers(_, infoHash)) ⇒
              val token = TokenSecret.generate(address)
              val peers = peersTable.getPeers(infoHash).toVector.map(PeerAddress(_))
              if (peers.nonEmpty) {
                sender() ! RequestResponse.Success(GetPeersResponse(dhtContext.selfNodeId, token, peers, Nil).toDict)
              } else {
                (rootBucket ? FindNodes(NodeId(infoHash))).mapTo[FindNodes.Success]
                  .map(fns ⇒ RequestResponse.Success(GetPeersResponse(dhtContext.selfNodeId, token, Nil, fns.nodes).toDict))
                  .pipeTo(sender())
              }

            case _ ⇒ failure()
          }

        case "announce_peer" ⇒
          val token = TokenSecret.generate(address)
          message.arguments match {
            case AnnouncePeer.Encoded(AnnouncePeer(_, infoHash, port, `token`)) ⇒
              val torrentPort = if (port <= 0) address.getPort else port
              val peerAddress = new InetSocketAddress(address.getAddress, torrentPort)
              peersTable.addPeer(infoHash, peerAddress)
              sender() ! RequestResponse.Success(AnnouncePeerResponse(dhtContext.selfNodeId).toDict)
              log.info("Peer added for {}: {}", Utils.toHexString(infoHash), peerAddress)

            case _ ⇒ failure()
          }
      }

    case UpdateSecret ⇒
      TokenSecret.updateSecret()
  }

  override def preStart(): Unit = {
    super.preStart()
    context.system.scheduler.schedule(0 nanos, 5 minutes, self, UpdateSecret)
    dhtSettings.bootstrapNodes.foreach { node ⇒
      bootstrapQueue ! DHTBootstrapQueue.PingNode(node)
    }
  }

  object TokenSecret {
    private[this] val secret = new Array[Byte](32)

    def generate(address: InetSocketAddress): ByteString = {
      val digest = MessageDigest.getInstance("SHA-1")
      digest.update(secret)
      digest.update(address.getAddress.getAddress)
      ByteString(digest.digest())
    }

    def updateSecret(): Unit = {
      Random.nextBytes(secret)
    }
  }
}
