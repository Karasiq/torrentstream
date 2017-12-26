package com.karasiq.torrentstream.app

import java.io.Closeable
import java.nio.ByteBuffer

import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.language.implicitConversions

import akka.util.ByteString
import com.typesafe.config.{Config, ConfigFactory}
import io.getquill.{H2JdbcContext, SnakeCase}

import com.karasiq.bittorrent.format.Torrent
import com.karasiq.torrentstream.shared.TorrentInfo

trait TorrentStore extends mutable.Map[ByteString, Torrent] with Closeable {
  def info(key: ByteString): TorrentInfo = {
    TorrentUtils.toTorrentInfo(apply(key))
  }

  def infoIterator: Iterator[TorrentInfo] = {
    iterator.map(v ⇒ TorrentUtils.toTorrentInfo(v._2))
  }
}

object TorrentStore {
  def apply(config: Config): TorrentStore = {
    new H2TorrentStore(config)
  }
}

final class H2TorrentStore(config: Config) extends TorrentStore {
  private[this] val dbConfig: Config = createH2Config()
  private[this] val context = new H2JdbcContext[SnakeCase](dbConfig)
  import context.{lift ⇒ liftQ, _}

  private[this] def createH2Config(): Config = {
    val path = config.getString("path")
    val script = config.getString("init-script")
    ConfigFactory.parseMap(Map(
      "dataSourceClassName" → "org.h2.jdbcx.JdbcDataSource",
      "dataSource.url" → s"jdbc:h2:file:$path;INIT=RUNSCRIPT FROM '$script'",
      "dataSource.user" → "sa",
      "dataSource.password" → "sa"
    ).asJava)
  }

  private[this] object Model {
    import boopickle.Default._

    import AppSerializers.Picklers._
    implicit val encodeByteString = MappedEncoding[ByteString, Array[Byte]](_.toArray)
    implicit val decodeByteString = MappedEncoding[Array[Byte], ByteString](ByteString.fromArray)
    implicit val encodeTorrent = MappedEncoding[Torrent, Array[Byte]](torrent ⇒ Pickle.intoBytes(torrent).array())
    implicit val decodeTorrent = MappedEncoding[Array[Byte], Torrent](bs ⇒ Unpickle[Torrent].fromBytes(ByteBuffer.wrap(bs)))

    final case class DBTorrent(infoHash: ByteString, timestamp: Long, data: Torrent)
    implicit val torrentsSchemaMeta = schemaMeta[DBTorrent]("torrents")
  }

  import Model._

  def +=(kv: (ByteString, Torrent)): this.type = {
    val timestamp = System.currentTimeMillis()
    val q = quote(query[DBTorrent].insert(liftQ(DBTorrent(kv._1, timestamp, kv._2))))
    context.run(q)
    this
  }

  def -=(key: ByteString): this.type = {
    val q = quote(query[DBTorrent].filter(_.infoHash == liftQ(key)).delete)
    context.run(q)
    this
  }

  def get(key: ByteString): Option[Torrent] = {
    val q = quote(query[DBTorrent].filter(_.infoHash == liftQ(key)).map(_.data))
    context.run(q).headOption
  }

  def iterator: Iterator[(ByteString, Torrent)] = {
    val q = quote(query[DBTorrent].sortBy(_.timestamp)(Ord.desc).map(dt ⇒ (dt.infoHash, dt.data)))
    context.run(q).iterator
  }

  def close(): Unit = {
    context.close()
  }
}