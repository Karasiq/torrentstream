package com.karasiq.bittorrent.dispatcher

import java.net.InetSocketAddress

import akka.util.ByteString
import com.karasiq.bittorrent.protocol.extensions.PeerExtensions

import scala.collection.BitSet

trait TorrentPeerInfo {
  def id: ByteString
  def infoHash: ByteString
  def completed: BitSet
}

case class PeerData(address: InetSocketAddress, id: ByteString, infoHash: ByteString, extensions: PeerExtensions, choking: Boolean = true, interesting: Boolean = false, chokedBy: Boolean = true, interestedBy: Boolean = false, completed: BitSet = BitSet.empty, latency: Long = Long.MaxValue) extends TorrentPeerInfo {
  require(id.length == 20, s"Invalid peer id: $id")
  require(infoHash.length == 20, s"Invalid info hash: $infoHash")
}

case class SeedData(id: ByteString, infoHash: ByteString, completed: BitSet = BitSet.empty)
