package com.karasiq.torrentstream.app

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.headers.{ContentDispositionTypes, Range, `Content-Disposition`}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.stream.ActorMaterializer
import akka.util.ByteString
import boopickle.Default._
import com.karasiq.bittorrent.format.Torrent
import com.karasiq.torrentstream.TorrentStream
import org.apache.commons.codec.binary.Hex
import org.apache.commons.io.FilenameUtils

private[app] class AppHandler(torrentManager: ActorRef, store: TorrentStore)(implicit actorSystem: ActorSystem, actorMaterializer: ActorMaterializer) extends AppSerializers {
  // Extracts `Range` header value
  private def rangesHeaderValue(torrent: Torrent): Directive1[Vector[(Long, Long)]] = {
    optionalHeaderValueByType[Range]().map {
      case Some(ranges) ⇒
        ranges.ranges.map(r ⇒ r.getSliceFirst().orElse(r.getOffset()).getOrElse(0L).asInstanceOf[Long] → r.getSliceLast().getOrElse(torrent.size).asInstanceOf[Long]).toVector
      case None ⇒
        Vector.empty[(Long, Long)]
    }
  }

  private def createTorrentStream(torrent: Torrent, fileName: String, ranges: Seq[(Long, Long)]): Directive1[TorrentStream] = {
    provide(TorrentStream.create(torrentManager, torrent, fileName, ranges))
  }

  val route = {
    get {
      (path("stream") & parameters('hash, 'file)) { (hash, file) ⇒
        val torrentId = ByteString(Hex.decodeHex(hash.toCharArray))
        validate(store.contains(torrentId), "Invalid torrent id") {
          val torrent = store(torrentId)
          rangesHeaderValue(torrent) { ranges ⇒
            createTorrentStream(torrent, file, ranges) { stream ⇒
              respondWithHeader(`Content-Disposition`(ContentDispositionTypes.attachment, Map("filename" → FilenameUtils.getName(file)))) {
                complete(if (ranges.isEmpty) StatusCodes.OK else StatusCodes.PartialContent, HttpEntity(ContentTypes.NoContentType, stream.size, stream.source))
              }
            }
          }
        }
      } ~
      pathEndOrSingleSlash {
        getFromResource("webapp/index.html")
      } ~
      getFromResourceDirectory("webapp")
    } ~
      post {
        (path("upload") & entity(as[ByteString])) { data ⇒
          Torrent.decode(data) match {
            case Some(torrent) ⇒
              onSuccess(store.add(torrent)) {
                extractLog { log ⇒
                  log.info(s"Torrent uploaded: {} {}", torrent.data.name, torrent.infoHashString)
                  complete(StatusCodes.OK, TorrentInfo.fromTorrent(torrent))
                }
              }

            case None ⇒
              complete(StatusCodes.BadRequest, "Invalid torrent file")
          }
        }
      }
  }
}
