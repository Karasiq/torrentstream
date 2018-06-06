import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.{ByteString, Timeout}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

import com.karasiq.bittorrent.announce.{HttpAnnouncer, TrackerRequest, TrackerResponse}
import com.karasiq.bittorrent.dispatcher._
import com.karasiq.bittorrent.format.Torrent
import com.karasiq.bittorrent.streams.TorrentSource

class TrackerTest extends FlatSpec with Matchers with BeforeAndAfterAll {
  implicit val actorSystem = ActorSystem("torrent-tracker-test")
  implicit val materializer = ActorMaterializer()
  val tracker = actorSystem.actorOf(HttpAnnouncer.props, "httpTracker")

  override protected def afterAll(): Unit = {
    actorSystem.terminate()
    super.afterAll()
  }

  implicit val timeout = Timeout(10 minutes)
  val torrent = Torrent(TestResources.testTorrent())

  "Torrent tracker" should "provide peers" in {
    val id = ByteString(Array.fill(20)('A'.toByte))
    val result = {
      val response = tracker ? TrackerRequest(torrent.announce, torrent.infoHash, id, 8901, 0, 0, torrent.size, numWant = 1000)
      Await.result(response, Duration.Inf).asInstanceOf[TrackerResponse]
    }
    result.interval shouldBe 1800
    result.complete should be > result.incomplete
    result.peers should not be empty
  }

  "Torrent pieces" should "be downloaded" in {
    val torrentManager = actorSystem.actorOf(TorrentManager.props, "torrentManager")
    val piece = {
      val response = Source.single(torrent)
        .via(TorrentSource.dispatcher(torrentManager))
        .flatMapConcat(dsp ⇒ TorrentSource.torrent(dsp.actorRef, dsp.torrent))
        .runWith(Sink.head)
      Await.result(response, Duration.Inf)
    }
    piece.data.length shouldBe torrent.content.pieceSize
  }
}
