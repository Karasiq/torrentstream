package com.karasiq.bittorrent.protocol.extensions

trait BitTorrentFastMessageIds {
  val HAVE_ALL = 0x0E
  val HAVE_NONE = 0x0F
  val SUGGEST_PIECE = 0x0D
  val REJECT_REQUEST = 0x10
  val ALLOWED_FAST = 0x11
}