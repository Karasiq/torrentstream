package com.karasiq.bittorrent.announce

import java.net.InetAddress

import akka.util.ByteString

case class TrackerRequest(announce: String, infoHash: ByteString, peerId: ByteString, port: Int, uploaded: Long, downloaded: Long, left: Long, compact: Boolean = true, noPeerId: Boolean = true, event: Option[String] = None, ip: Option[InetAddress] = None, numWant: Int = 50, key: Option[String] = None, trackerId: Option[String] = None)