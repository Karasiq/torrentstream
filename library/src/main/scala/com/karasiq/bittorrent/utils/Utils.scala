package com.karasiq.bittorrent.utils

import akka.util.ByteString
import org.apache.commons.codec.binary.Hex

private[bittorrent] object Utils {
  def toHexString(bytes: Array[Byte]): String = {
    Hex.encodeHexString(bytes)
  }

  def toHexString(bytes: ByteString): String = {
    toHexString(bytes.toArray[Byte])
  }
}
