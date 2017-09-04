package com.karasiq.torrentstream.app

import scala.language.implicitConversions

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.model.headers.{`Content-Disposition`, ContentDispositionTypes, Range}
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives._
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.util.ByteString
import boopickle.Default._
import org.apache.commons.io.FilenameUtils

import com.karasiq.bittorrent.format.Torrent
import com.karasiq.torrentstream.TorrentStream
import com.karasiq.torrentstream.app.AppSerializers.StringInfoHashOps

private[app] class AppHandler(torrentManager: ActorRef, store: TorrentStore)
                             (implicit actorSystem: ActorSystem, actorMaterializer: ActorMaterializer)
  extends AppSerializers.Marshallers {

  private[this] val config = actorSystem.settings.config.getConfig("karasiq.torrentstream.streamer")
  private[this] val bufferSize = config.getInt("buffer-size") // In bytes

  // Extracts `Range` header value
  private[this] def rangesHeaderValue(torrent: Torrent): Directive1[Vector[(Long, Long)]] = {
    def normalizeRange(range: (Long, Long)): (Long, Long) = {
      val end = math.min(torrent.size, range._2)
      val start = math.min(end, math.max(0L, range._1))
      (start, end)
    }

    optionalHeaderValueByType[Range]().map {
      case Some(httpRanges) ⇒
        import akka.http.scaladsl.model.headers.ByteRange
        val resultRanges = httpRanges.ranges.map {
          case ByteRange.Suffix(length) ⇒
            (torrent.size - length, torrent.size)

          case ByteRange.Slice(first, last) ⇒
            (first, last + 1)

          case ByteRange.FromOffset(offset) ⇒
            (offset, torrent.size)
        }

        resultRanges.map(normalizeRange).toVector

      case None ⇒
        Vector.empty[(Long, Long)]
    }
  }

  private[this] def createTorrentStream(torrent: Torrent, fileName: String, ranges: Seq[(Long, Long)]): Directive1[TorrentStream] = {
    provide(TorrentStream.create(torrentManager, torrent, fileName, ranges))
  }

  val route = {
    get {
      path("info") {
        parameter('hash) { hash ⇒
          val torrentId = hash.infoHash
          validate(store.contains(torrentId), "Unknown torrent info hash") {
            complete(store.info(torrentId))
          }
        } ~
        parameters('offset.as[Int], 'count.as[Int]) { (offset, count) ⇒
          complete(store.infoIterator.slice(offset, offset + count).toVector)
        } ~
        complete(store.size) // Torrent count
      } ~
      (path("stream") & parameters('hash, 'file)) { (hash, file) ⇒
        val torrentId = hash.infoHash
        validate(store.contains(torrentId), "Unknown torrent info hash") {
          val torrent = store(torrentId)
          rangesHeaderValue(torrent) { ranges ⇒
            createTorrentStream(torrent, file, ranges) { stream ⇒
              respondWithHeader(`Content-Disposition`(ContentDispositionTypes.attachment, Map("filename" → FilenameUtils.getName(file)))) {
                val buffered = stream.source.buffer(bufferSize / torrent.data.pieceLength, OverflowStrategy.backpressure)
                complete(if (ranges.isEmpty) StatusCodes.OK else StatusCodes.PartialContent, HttpEntity(ContentTypes.NoContentType, stream.size, buffered))
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
            if (store.contains(torrent.infoHash)) {
              complete(StatusCodes.OK, TorrentInfo.fromTorrent(torrent))
            } else {
              extractLog { log ⇒
                store += torrent.infoHash → torrent
                log.info(s"Torrent uploaded: {} {}", torrent.data.name, torrent.infoHashString)
                complete(StatusCodes.OK, TorrentInfo.fromTorrent(torrent))
              }
            }

          case None ⇒
            complete(StatusCodes.BadRequest, "Invalid torrent file")
        }
      }
    } ~
    delete {
      (path("torrent") & parameter('hash)) { hash ⇒
        val torrentId = hash.infoHash
        validate(store.contains(torrentId), "Unknown torrent info hash") {
          extractLog { log ⇒
            store -= torrentId
            log.info("Torrent removed: {}", hash)
            complete(StatusCodes.OK, hash)
          }
        }
      }
    }
  }
}
