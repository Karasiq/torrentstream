package com.karasiq.torrentstream

import java.io.{RandomAccessFile, FileInputStream, FileOutputStream}
import java.nio.file.{Paths, Files}

import akka.actor.Actor.Receive
import akka.actor.{Stash, ActorLogging, Actor}

import scala.concurrent.duration._
import scala.collection.JavaConversions._

class TorrentWriter extends Actor with ActorLogging with Stash {
  var fileSize = 0L

  var currentOffset = 0L

  override def receive: Receive = {
    case TorrentFileStart(file, size) ⇒
      log.info("Start of file: {} ({} bytes)", file, size)
      fileSize = size

    case TorrentChunk(file, offset, data) ⇒
      if (offset == currentOffset) {
        log.info("Chunk: {} at {} with size {}", file, offset, data.length)
        currentOffset += data.length
        unstashAll()
      } else {
        stash()
      }

    case TorrentFileEnd(file) ⇒
      if (currentOffset == fileSize) {
        log.info("End of file: {}", file)
        Files.deleteIfExists(Paths.get(file))
        context.stop(self)
      } else {
        stash()
      }
  }
}
