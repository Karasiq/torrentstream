import akka.util.ByteString
import org.apache.commons.codec.binary.Hex
import org.scalatest.{FlatSpec, Matchers}

import com.karasiq.bittorrent.protocol.PeerStreamEncryption

class DHKeysTest extends FlatSpec with Matchers {
  "DH key" should "be decoded" in {
    val keyHex = "aede541de65079a045134d6302798d8699605a9e030ead5261d5bf952c187963838f2cc2561f686492f6b12af4bc2546d63967399f419b7f6f91865a6deec13f90f7bbbf2ad50c8953b0ecf30a1cdf7df4c2b65f380a6b47697be0015c3a43ca"
    val keyBytes = ByteString(Hex.decodeHex(keyHex.toCharArray))
    val key = PeerStreamEncryption.DHKeys.tryReadKey(keyBytes)
    assert(key.isSuccess, "Key read failed")
    PeerStreamEncryption.DHKeys.toBytes(key.get) shouldBe keyBytes
  }
}
