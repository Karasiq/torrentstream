import java.time.Instant

import com.karasiq.bittorrent.format.{BEncode, TorrentFileInfo, TorrentMetadata}
import org.apache.commons.io.IOUtils
import org.scalatest.{FlatSpec, Matchers}

class BEncodeTest extends FlatSpec with Matchers  {
  "BEncode parser" should "parse torrent file" in {
    val testFile = IOUtils.toByteArray(getClass.getResourceAsStream("ubuntu-15.10-desktop-amd64.iso.torrent"))
    val data = BEncode.parse(testFile)
    val torrent = TorrentMetadata.decode(data)
    torrent.announce shouldBe Some("http://torrent.ubuntu.com:6969/announce")
    torrent.announceList shouldBe Vector(Vector("http://torrent.ubuntu.com:6969/announce"), Vector("http://ipv6.torrent.ubuntu.com:6969/announce"))
    torrent.comment shouldBe Some("Ubuntu CD releases.ubuntu.com")
    torrent.date shouldBe Some(Instant.parse("2015-10-22T09:48:19Z"))
    torrent.files.pieceLength shouldBe 524288L
    torrent.files.pieces.length shouldBe 44960
    torrent.files.files.headOption shouldBe Some(TorrentFileInfo("ubuntu-15.10-desktop-amd64.iso", 1178386432L))
  }
}
