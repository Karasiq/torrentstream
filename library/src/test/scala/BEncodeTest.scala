import java.time.Instant

import org.apache.commons.codec.binary.Hex
import org.scalatest.{FlatSpec, Matchers}

import com.karasiq.bittorrent.format.{Torrent, TorrentFile, TorrentPiece}

class BEncodeTest extends FlatSpec with Matchers  {
  val torrent = Torrent(TestResources.testTorrent())
  
  "BEncode parser" should "parse torrent file" in {
    Hex.encodeHexString(torrent.infoHash.toArray).toUpperCase shouldBe "DDEE5CB75C12F3165EF79A12A5CD6158BEF029AD"
    torrent.announce shouldBe "http://torrent.ubuntu.com:6969/announce"
    torrent.announceList shouldBe Vector(Vector("http://torrent.ubuntu.com:6969/announce"), Vector("http://ipv6.torrent.ubuntu.com:6969/announce"))
    torrent.comment shouldBe Some("Ubuntu CD releases.ubuntu.com")
    torrent.date shouldBe Some(Instant.parse("2018-04-26T20:59:50Z"))
    torrent.content.pieceSize shouldBe 524288L
    torrent.content.pieces.length shouldBe 32240
    torrent.content.files.headOption shouldBe Some(TorrentFile("ubuntu-18.04-live-server-amd64.iso", 845152256L))
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
