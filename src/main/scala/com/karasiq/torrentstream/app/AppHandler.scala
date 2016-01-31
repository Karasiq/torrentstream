package com.karasiq.torrentstream.app

import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.model.headers.Range
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.stream.ActorMaterializer
import akka.stream.actor.ActorPublisher
import akka.stream.scaladsl.Source
import akka.util.ByteString
import boopickle.Default._
import com.karasiq.torrentstream.{DownloadTorrent, TorrentChunk, TorrentWriter}
import com.karasiq.ttorrent.common.Torrent

import scala.collection.JavaConversions._
import scala.util.{Failure, Success, Try}

case class TorrentEntry(id: Long, data: Torrent)

class AppHandler(store: TorrentStore)(implicit actorSystem: ActorSystem, actorMaterializer: ActorMaterializer) extends AppSerializers {
  // Extracts `Range` header value
  private def offsetValue: Directive1[Long] = {
    optionalHeaderValueByType[Range]().map {
      case Some(range) ⇒
        range.ranges.head.getOffset().getOrElse(0).asInstanceOf[Long]
      case None ⇒
        0L
    }
  }

  private def createTorrentStream(torrent: ByteString, file: String, offset: Long): Directive[(Long, Source[ByteString, Unit])] = {
    val writer = actorSystem.actorOf(Props[TorrentWriter])
    val parsed = new Torrent(torrent.toArray, false)
    val torrentFile = parsed.getFiles.find(_.file.getPath == file).getOrElse(parsed.getFiles.head)
    writer ! DownloadTorrent(torrent, torrentFile.file.getPath, offset)
    tprovide(torrentFile.size, Source.fromPublisher(ActorPublisher[TorrentChunk](writer)).map(_.data))
  }

  val route = {
    get {
      (pathSingleSlash & offsetValue & parameters('torrentId.as[Long], 'file)) { (offset, torrentId, file) ⇒
        validate(store.contains(torrentId), "Invalid torrent id") {
          createTorrentStream(ByteString(store(torrentId)), file, offset) { (size, stream) ⇒
            complete(if (offset == 0) StatusCodes.OK else StatusCodes.PartialContent, HttpEntity(ContentTypes.NoContentType, size - offset, stream))
          }
        }
      }
    } ~
      post {
        (path("upload") & entity(as[Array[Byte]])) { data ⇒
          Try(new Torrent(data, false)) match {
            case Success(torrent) ⇒
              extractLog { log ⇒
                val id = store.add(torrent.getEncoded)
                log.info(s"Torrent uploaded: {} {} (id = {})", torrent.getName, torrent.getHexInfoHash, id)
                complete(StatusCodes.OK, TorrentEntry(id, torrent))
              }

            case Failure(_) ⇒
              complete(StatusCodes.BadRequest, "Invalid torrent file")
          }
        }
      }
  }
}
