package com.karasiq.torrentstream.frontend

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js.typedarray.{ArrayBuffer, TypedArrayBuffer}

import boopickle.Default._
import org.scalajs.dom.ext.Ajax
import org.scalajs.dom.raw.XMLHttpRequest

import com.karasiq.torrentstream.shared.TorrentInfo

object TorrentStreamApi {
  def upload(data: Ajax.InputData): Future[TorrentInfo] = {
    Ajax.post("/upload", data, responseType = "arraybuffer")
      .map(readBinary[TorrentInfo])
  }

  def uploaded(): Future[Int] = {
    Ajax.get("/info", responseType = "arraybuffer")
      .map(readBinary[Int])
  }

  def info(offset: Int, count: Int): Future[Seq[TorrentInfo]] = {
    Ajax.get(s"/info?offset=$offset&count=$count", responseType = "arraybuffer")
      .map(readBinary[Seq[TorrentInfo]])
  }

  def remove(infoHash: String): Future[Unit] = {
    Ajax.delete(s"/torrent?hash=$infoHash").map(_ ⇒ ())
  }

  private[this] def readBinary[T: Pickler](xhr: XMLHttpRequest): T = {
    Unpickle[T].fromBytes(TypedArrayBuffer.wrap(xhr.response.asInstanceOf[ArrayBuffer]))
  }
}
