package com.karasiq.torrentstream.app

import akka.http.scaladsl.marshalling._
import akka.util.ByteString
import boopickle.Default._

trait AppSerializers {
  implicit def binaryMarshaller[A, B](implicit ev: Pickler[A], m: Marshaller[ByteString, B]): Marshaller[A, B] = {
    Marshaller(implicit ec ⇒ value ⇒ m(ByteString(Pickle.intoBytes(value))))
  }
}
