import akka.util.ByteString
import org.apache.commons.io.IOUtils

object TestResources {
  def testTorrent(): ByteString = {
    ByteString(IOUtils.toByteArray(getClass.getResource("ubuntu-16.04.3-desktop-amd64.iso.torrent")))
  }
}
