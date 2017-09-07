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
import com.karasiq.torrentstream.app.AppSerializers.StringConversions

private[app] class AppHandler(torrentManager: ActorRef, store: TorrentStore)
                             (implicit actorSystem: ActorSystem, actorMaterializer: ActorMaterializer)
  extends AppSerializers.Marshallers {

  // -----------------------------------------------------------------------
  // Config
  // -----------------------------------------------------------------------
  protected object settings {
    private[this] val config = actorSystem.settings.config.getConfig("karasiq.torrentstream.streamer")
    val bufferSize = config.getInt("buffer-size") // In bytes
  }

  // -----------------------------------------------------------------------
  // Routes
  // -----------------------------------------------------------------------
  val route = {
    get {
      path("info") {
        parameter('hash) { hashString ⇒
          val infoHash = hashString.decodeHexString
          validate(store.contains(infoHash), "Unknown torrent info hash") {
            complete(store.info(infoHash))
          }
        } ~
        parameters('offset.as[Int], 'count.as[Int]) { (offset, count) ⇒
          complete(store.infoIterator.slice(offset, offset + count).toList)
        } ~
        complete(store.size) // Torrent count
      } ~
      (path("stream") & parameters('hash, 'file)) { (hashString, file) ⇒
        val infoHash = hashString.decodeHexString
        validate(store.contains(infoHash), "Unknown torrent info hash") {
          val torrent = store(infoHash)
          directives.rangesHeaderValue(torrent) { ranges ⇒
            directives.createTorrentStream(torrent, file, ranges) { stream ⇒
              val fileName = FilenameUtils.getName(file)
              respondWithHeader(`Content-Disposition`(ContentDispositionTypes.attachment, Map("filename" → fileName))) {
                val bufferCount = settings.bufferSize / torrent.content.pieceSize
                val bufferedStream = stream.source.buffer(bufferCount, OverflowStrategy.backpressure)
                complete(if (ranges.isEmpty) StatusCodes.OK else StatusCodes.PartialContent,
                  HttpEntity(ContentTypes.NoContentType, stream.size, bufferedStream))
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
        Torrent.tryDecode(data) match {
          case Some(torrent) ⇒
            if (store.contains(torrent.infoHash)) {
              complete(StatusCodes.OK, TorrentUtils.toTorrentInfo(torrent))
            } else {
              extractLog { log ⇒
                store += torrent.infoHash → torrent
                log.info(s"Torrent uploaded: {} {}", torrent.content.name, torrent.infoHashString)
                complete(StatusCodes.OK, TorrentUtils.toTorrentInfo(torrent))
              }
            }

          case None ⇒
            complete(StatusCodes.BadRequest, "Invalid torrent file")
        }
      }
    } ~
    delete {
      (path("torrent") & parameter('hash)) { hashString ⇒
        val infoHash = hashString.decodeHexString
        validate(store.contains(infoHash), "Unknown torrent info hash") {
          extractLog { log ⇒
            store -= infoHash
            log.info("Torrent removed: {}", hashString)
            complete(StatusCodes.OK, hashString)
          }
        }
      }
    }
  }

  // -----------------------------------------------------------------------
  // Directives
  // -----------------------------------------------------------------------
  protected object directives {
    type RangeT = (Long, Long)
    type RangesT = Vector[RangeT]

    // Extracts `Range` header value
    def rangesHeaderValue(torrent: Torrent): Directive1[RangesT] = {
      def normalizeRange(range: RangeT): RangeT = {
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
          Vector.empty[RangeT]
      }
    }

    def createTorrentStream(torrent: Torrent, fileName: String, ranges: RangesT): Directive1[TorrentStream] = {
      provide(TorrentStream.create(torrentManager, torrent, fileName, ranges))
    }
  }
}
