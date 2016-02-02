import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.util.{ByteString, Timeout}
import com.karasiq.bittorrent.announce.{HttpTracker, TrackerRequest, TrackerResponse}
import com.karasiq.bittorrent.format.TorrentMetadata
import org.apache.commons.io.IOUtils
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

class TrackerTest extends FlatSpec with Matchers with BeforeAndAfterAll {
  val actorSystem = ActorSystem("torrent-tracker-test")
  val tracker = actorSystem.actorOf(Props[HttpTracker])

  override protected def afterAll(): Unit = {
    actorSystem.terminate()
    super.afterAll()
  }

  val torrent = TorrentMetadata(ByteString(IOUtils.toByteArray(getClass.getResourceAsStream("ubuntu-15.10-desktop-amd64.iso.torrent")))).get

  "Torrent tracker" should "provide peers" in {
    implicit val timeout = Timeout(30 seconds)
    val response = tracker ? TrackerRequest(torrent.announce, torrent.infoHash, ByteString('A' * 20), 8901, 0, 0, torrent.size)
    val result = Await.result(response, Duration.Inf).asInstanceOf[TrackerResponse]
    result.interval shouldBe 1800
    result.complete should be > result.incomplete
    result.peers should not be empty
  }
}
