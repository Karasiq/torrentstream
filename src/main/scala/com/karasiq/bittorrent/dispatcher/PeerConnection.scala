package com.karasiq.bittorrent.dispatcher

import java.net.InetSocketAddress

import akka.actor.{ActorRef, FSM}
import akka.io.Tcp.{CommandFailed, Connect, Connected, ConnectionClosed}
import akka.io.{IO, Tcp}
import com.karasiq.bittorrent.dispatcher.PeerConnectionContext._
import com.karasiq.bittorrent.dispatcher.PeerConnectionState._

import scala.concurrent.duration._
import scala.language.postfixOps

case class ConnectPeer(address: InetSocketAddress, ownData: PeerData)
case class PeerConnected(address: InetSocketAddress, data: PeerData)
case class PeerDisconnected(address: InetSocketAddress)
case class PeerList(addresses: InetSocketAddress)

sealed trait PeerConnectionState
object PeerConnectionState {
  case object Idle extends PeerConnectionState
  case object Connecting extends PeerConnectionState
  case object ConnectionEstablished extends PeerConnectionState
  case object Downloading extends PeerConnectionState
}

sealed trait PeerConnectionContext
object PeerConnectionContext {
  case object NoData extends PeerConnectionContext
  case class ConnectingContext(ownData: PeerData) extends PeerConnectionContext
  case class PeerContext(ownData: PeerData, peerData: PeerData) extends PeerConnectionContext
}

class PeerConnection(dispatcher: ActorRef) extends FSM[PeerConnectionState, PeerConnectionContext] {
  import context.system
  startWith(Idle, NoData)

  when(Idle) {
    case Event(ConnectPeer(address, ownData), _) ⇒
      IO(Tcp) ! Connect(address)
      goto(Connecting) using ConnectingContext(ownData)

    case Event(StateTimeout, _) ⇒
      stop()
  }

  when(Connecting, 3 minutes) {
    case Event(Connected(remote, local), ConnectingContext(ownData)) ⇒
      goto(ConnectionEstablished) using ConnectingContext(ownData)

    case Event(_: ConnectionClosed | _: CommandFailed | StateTimeout, _) ⇒
      stop()
  }

  when(ConnectionEstablished) {
          ???
  }
}
