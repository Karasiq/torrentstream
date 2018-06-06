package com.karasiq.bittorrent.dht

import java.net.InetSocketAddress

import scala.collection.mutable

import akka.util.ByteString

object DHTPeersTable {
  def apply(): DHTPeersTable = {
    new DHTPeersTable()
  }

  private final case class Entry(infoHash: ByteString, address: InetSocketAddress, timeAdded: Long)
}

class DHTPeersTable {
  import DHTPeersTable._
  private[this] val submissions = mutable.AnyRefMap.empty[ByteString, Seq[Entry]]

  def addPeer(infoHash: ByteString, address: InetSocketAddress): Unit = {
    val entries = submissions.getOrElse(infoHash, Nil)
    val newEntries = entries.filterNot(_.address == address) :+ Entry(infoHash, address, System.currentTimeMillis())
    submissions(infoHash) = newEntries
  }

  def getPeers(infoHash: ByteString): Seq[InetSocketAddress] = {
    submissions.getOrElse(infoHash, Nil)
      .map(_.address)
  }
}
