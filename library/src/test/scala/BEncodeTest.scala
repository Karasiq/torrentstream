import java.time.Instant

import akka.util.ByteString
import org.apache.commons.codec.binary.Hex
import org.apache.commons.io.IOUtils
import org.scalatest.{FlatSpec, Matchers}

import com.karasiq.bittorrent.format.{Torrent, TorrentFile, TorrentPiece}

object BEncodeTest {
  def readTestTorrent(): ByteString = {
    ByteString(IOUtils.toByteArray(getClass.getResource("ubuntu-15.10-desktop-amd64.iso.torrent")))
  }
}

class BEncodeTest extends FlatSpec with Matchers  {
  val torrent = Torrent(BEncodeTest.readTestTorrent())
  
  "BEncode parser" should "parse torrent file" in {
    Hex.encodeHexString(torrent.infoHash.toArray).toUpperCase shouldBe "3F19B149F53A50E14FC0B79926A391896EABAB6F"
    torrent.announce shouldBe "http://torrent.ubuntu.com:6969/announce"
    torrent.announceList shouldBe Vector(Vector("http://torrent.ubuntu.com:6969/announce"), Vector("http://ipv6.torrent.ubuntu.com:6969/announce"))
    torrent.comment shouldBe Some("Ubuntu CD releases.ubuntu.com")
    torrent.date shouldBe Some(Instant.parse("2015-10-22T09:48:19Z"))
    torrent.data.pieceSize shouldBe 524288L
    torrent.data.pieces.length shouldBe 44960
    torrent.data.files.headOption shouldBe Some(TorrentFile("ubuntu-15.10-desktop-amd64.iso", 1178386432L))
  }

  "Torrent pieces" should "be constructed" in {
    val pieces = TorrentPiece.pieces(torrent.data)
    pieces.length shouldBe (torrent.data.pieces.length / 20)
    pieces.map(_.size).sum shouldBe torrent.size
    pieces.head.sha1.length shouldBe 20
  }

  "Torrent piece blocks" should "be constructed" in {
    val pieces = TorrentPiece.pieces(torrent.data)
    val blocks = TorrentPiece.blocks(pieces.head, 10000)
    blocks.map(_.size).sum shouldBe pieces.head.size
  }
}
