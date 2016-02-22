package com.karasiq.torrentstream.frontend

import boopickle.Default._
import org.scalajs.dom.ext.Ajax
import org.scalajs.dom.raw.XMLHttpRequest

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js.typedarray.{ArrayBuffer, TypedArrayBuffer}

object TorrentStreamApi {
  private def readBinary[T: Pickler](xhr: XMLHttpRequest): T = {
    Unpickle[T].fromBytes(TypedArrayBuffer.wrap(xhr.response.asInstanceOf[ArrayBuffer]))
  }

  def upload(data: Ajax.InputData): Future[TorrentInfo] = {
    Ajax.post("/upload", data, responseType = "arraybuffer")
      .map(readBinary[TorrentInfo])
  }

  def uploaded(): Future[Int] = {
    Ajax.get("/info", responseType = "arraybuffer")
      .map(readBinary[Int])
  }

  def info(offset: Int, count: Int): Future[Vector[TorrentInfo]] = {
    Ajax.get(s"/info?offset=$offset&count=$count", responseType = "arraybuffer")
      .map(readBinary[Vector[TorrentInfo]])
  }

  def remove(infoHash: String): Future[Unit] = {
    Ajax.delete(s"/torrent?hash=$infoHash").map(_ ⇒ ())
  }
}
