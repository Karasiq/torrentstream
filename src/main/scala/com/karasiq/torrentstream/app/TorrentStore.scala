package com.karasiq.torrentstream.app

import java.io.{Closeable, DataInput, DataOutput}
import java.nio.file.Paths

import akka.util.ByteString
import com.karasiq.bittorrent.format.TorrentMetadata
import com.karasiq.mapdb.serialization.MapDbSerializer
import com.karasiq.mapdb.serialization.MapDbSerializer.Default._
import com.karasiq.mapdb.transaction.TxCtx
import com.karasiq.mapdb.{MapDbSingleFileProducer, MapDbWrapper}
import com.typesafe.config.Config
import org.mapdb.DBMaker.Maker
import org.mapdb.{BTreeKeySerializer, Serializer}

import scala.concurrent.Future

final class TorrentStore(config: Config) extends Map[ByteString, TorrentMetadata] with Closeable {
  private implicit val byteStringSerializer = new Serializer[ByteString] {
    private val arraySerializer = MapDbSerializer[Array[Byte]]

    override def serialize(out: DataOutput, value: ByteString): Unit = {
      arraySerializer.serialize(out, value.toArray)
    }

    override def deserialize(in: DataInput, available: Int): ByteString = {
      ByteString(arraySerializer.deserialize(in, available))
    }
  }

  private object DbProvider extends MapDbSingleFileProducer(Paths.get(config.getString("karasiq.torrentstream.store.path"))) {
    override protected def setSettings(dbMaker: Maker): Maker = {
      dbMaker
        .executorEnable()
        .compressionEnable()
        .asyncWriteEnable()
        .asyncWriteFlushDelay(1000)
    }
  }

  private val db = DbProvider()

  private val map = MapDbWrapper(db).createTreeMap[Array[Byte], TorrentMetadata]("torrents")(_
    .keySerializer(BTreeKeySerializer.BYTE_ARRAY)
    .valueSerializer(MapDbSerializer[TorrentMetadata])
    .nodeSize(32)
    .valuesOutsideNodesEnable()
  )

  override def +[B1 >: TorrentMetadata](kv: (ByteString, B1)): Map[ByteString, B1] = {
    throw new IllegalArgumentException
  }

  override def -(key: ByteString): Map[ByteString, TorrentMetadata] = {
    throw new IllegalArgumentException
  }

  override def get(key: ByteString): Option[TorrentMetadata] = {
    map.get(key.toArray)
  }

  override def iterator: Iterator[(ByteString, TorrentMetadata)] = {
    map.iterator.map(kv ⇒ ByteString(kv._1) → kv._2)
  }

  override def contains(key: ByteString): Boolean = {
    map.contains(key.toArray)
  }

  def add(torrent: TorrentMetadata)(implicit tx: TxCtx = db.newTransaction): Future[Unit] = {
    db.scheduleTransaction { implicit tx ⇒
      map += torrent.infoHash.toArray[Byte] → torrent
    }
  }

  override def close(): Unit = {
    DbProvider.close()
  }
}
