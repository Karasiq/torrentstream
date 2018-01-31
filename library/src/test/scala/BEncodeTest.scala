import java.time.Instant

import org.apache.commons.codec.binary.Hex
import org.scalatest.{FlatSpec, Matchers}

import com.karasiq.bittorrent.format.{Torrent, TorrentFile, TorrentPiece}

class BEncodeTest extends FlatSpec with Matchers  {
  val torrent = Torrent(TestResources.testTorrent())
  
  "BEncode parser" should "parse torrent file" in {
    Hex.encodeHexString(torrent.infoHash.toArray).toUpperCase shouldBe "1488D454915D860529903B61ADB537012A0FE7C8"
    torrent.announce shouldBe "http://torrent.ubuntu.com:6969/announce"
    torrent.announceList shouldBe Vector(Vector("http://torrent.ubuntu.com:6969/announce"), Vector("http://ipv6.torrent.ubuntu.com:6969/announce"))
    torrent.comment shouldBe Some("Ubuntu CD releases.ubuntu.com")
    torrent.date shouldBe Some(Instant.parse("2017-08-03T13:14:57Z"))
    torrent.content.pieceSize shouldBe 524288L
    torrent.content.pieces.length shouldBe 60580
    torrent.content.files.headOption shouldBe Some(TorrentFile("ubuntu-16.04.3-desktop-amd64.iso", 1587609600L))
  }

  "Torrent pieces" should "be constructed" in {
    val pieces = TorrentPiece.pieces(torrent.content)
    pieces.length shouldBe (torrent.content.pieces.length / 20)
    pieces.map(_.size).sum shouldBe torrent.size
    pieces.head.sha1.length shouldBe 20
  }

  "Torrent piece blocks" should "be constructed" in {
    val pieces = TorrentPiece.pieces(torrent.content)
    val blocks = TorrentPiece.blocks(pieces.head, 10000)
    blocks.map(_.size).sum shouldBe pieces.head.size
  }
}
