package com.karasiq.torrentstream.app

import java.io.Closeable
import java.nio.ByteBuffer

import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.language.implicitConversions

import akka.util.ByteString
import boopickle.Default._
import com.typesafe.config.{Config, ConfigFactory}
import io.getquill.{H2JdbcContext, SnakeCase}

import com.karasiq.bittorrent.format.Torrent

trait TorrentStore extends mutable.Map[ByteString, Torrent] with Closeable

object TorrentStore {
  def apply(config: Config): TorrentStore = {
    new H2TorrentStore(config)
  }
}

final class H2TorrentStore(config: Config) extends TorrentStore {
  private[this] val context: H2JdbcContext[SnakeCase] = new H2JdbcContext[SnakeCase](createH2Config())
  import context._

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
    import AppSerializers.Picklers._
    implicit val encodeByteString = MappedEncoding[ByteString, Array[Byte]](_.toArray)
    implicit val decodeByteString = MappedEncoding[Array[Byte], ByteString](ByteString.fromArray)
    implicit val encodeTorrent = MappedEncoding[Torrent, Array[Byte]](torrent ⇒ Pickle.intoBytes(torrent).array())
    implicit val decodeTorrent = MappedEncoding[Array[Byte], Torrent](bs ⇒ Unpickle[Torrent].fromBytes(ByteBuffer.wrap(bs)))

    final case class DBTorrent(infoHash: ByteString, timestamp: Long, data: Torrent)
    object DBTorrent {
      implicit def toTorrent(dt: DBTorrent): Torrent = dt.data
    }
    
    implicit val torrentsSchemaMeta = schemaMeta[DBTorrent]("torrents")
}

  import Model._

  def +=(kv: (ByteString, Torrent)) = {
    val timestamp = System.currentTimeMillis()
    val q = quote(query[DBTorrent].insert(lift(DBTorrent(kv._1, timestamp, kv._2))))
    context.run(q)
    this
  }

  def -=(key: ByteString) = {
    val q = quote(query[DBTorrent].filter(_.infoHash == lift(key)).delete)
    context.run(q)
    this
  }

  def get(key: ByteString) = {
    val q = quote(query[DBTorrent].filter(_.infoHash == lift(key))).map(_.data)
    context.run(q).headOption
  }

  def iterator = {
    val q = quote(query[DBTorrent]).sortBy(_.timestamp)(Ord.desc).map(_.data)
    context.run(q).iterator
  }

  def close() = {
    context.close()
  }
}