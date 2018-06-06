import akka.util.ByteString
import org.apache.commons.io.IOUtils

object TestResources {
  def testTorrent(): ByteString = {
    ByteString(IOUtils.toByteArray(getClass.getResource("ubuntu-18.04-live-server-amd64.iso.torrent")))
  }
}
