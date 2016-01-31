package com.karasiq.torrentstream.app

import akka.http.scaladsl.marshalling._
import akka.util.ByteString
import boopickle.Default._
import com.karasiq.ttorrent.common.Torrent

import scala.collection.JavaConversions._

trait AppSerializers {
  implicit val torrentInfoPickler = new Pickler[Torrent] {
    override def pickle(obj: Torrent)(implicit state: PickleState): Unit = {
      state.pickle(obj.getAnnounceList.map(_.map(_.toString).toSeq).toSeq)
      state.pickle(obj.getComment)
      state.pickle(obj.getCreatedBy)
      state.pickle(obj.getFiles.map(f ⇒ f.file.getPath → f.size).toSeq)
      state.pickle(obj.getHexInfoHash)
      state.pickle(obj.getName)
      state.pickle(obj.getSize)
    }

    override def unpickle(implicit state: UnpickleState): Torrent = ???
  }

  implicit def binaryMarshaller[A, B](implicit ev: Pickler[A], m: Marshaller[ByteString, B]): Marshaller[A, B] = {
    Marshaller(implicit ec ⇒ value ⇒ m(ByteString(Pickle.intoBytes(value))))
  }
}
