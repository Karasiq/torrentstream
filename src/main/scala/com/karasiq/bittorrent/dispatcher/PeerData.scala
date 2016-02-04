package com.karasiq.bittorrent.dispatcher

import java.net.InetSocketAddress

import akka.util.ByteString

import scala.collection.BitSet

case class PeerData(address: InetSocketAddress, id: ByteString, infoHash: ByteString, choking: Boolean = true, interesting: Boolean = false, chokedBy: Boolean = true, interestedBy: Boolean = false, completed: BitSet = BitSet.empty) {
  require(id.length == 20, s"Invalid peer id: $id")
  require(infoHash.length == 20, s"Invalid info hash: $infoHash")
}
