package com.karasiq.bittorrent.dispatcher

import java.net.InetSocketAddress

import scala.collection.BitSet

case class PeerData(address: InetSocketAddress, choking: Boolean, interesting: Boolean, chokingBy: Boolean, interestingBy: Boolean, completed: BitSet)
