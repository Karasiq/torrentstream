package com.karasiq.bittorrent.protocol

import com.karasiq.bittorrent.protocol.extensions.{BitTorrentFastMessageIds, ExtensionProtocolMessageIds}

sealed trait BitTorrentMessageIds {
  final val CHOKE = 0
  final val UNCHOKE = 1
  final val INTERESTED = 2
  final val NOT_INTERESTED = 3
  final val HAVE = 4
  final val BITFIELD = 5
  final val REQUEST = 6
  final val PIECE = 7
  final val CANCEL = 8
  final val PORT = 9
}

object PeerMessageId extends BitTorrentMessageIds with BitTorrentFastMessageIds with ExtensionProtocolMessageIds
