package com.karasiq.bittorrent.dht

import java.net.InetSocketAddress

import scala.collection.mutable
import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, PossiblyHarmful, Props}
import akka.io.{IO, Udp}
import akka.pattern.ask
import akka.util.{ByteString, Timeout}

import com.karasiq.bittorrent.dht.DHTMessages.{DHTError, DHTMessageHeader, DHTQuery}
import com.karasiq.bittorrent.format.{BEncode, BEncodedDictionary}

object DHTMessageDispatcher {
  type TxId = ByteString

  // Messages
  sealed trait Message
  final case class SendQuery(address: InetSocketAddress, message: DHTQuery) extends Message
  object SendQuery {
    sealed trait Status
    final case class Success(data: BEncodedDictionary) extends Status
    final case class Failure(error: Throwable) extends Status
  }

  final case class RequestResponse(address: InetSocketAddress, txId: TxId, message: DHTQuery) extends Message
  object RequestResponse {
    sealed trait Status
    final case class Success(data: BEncodedDictionary) extends Status
    final case class Failure(error: Throwable) extends Status
  }

  // Internal messages
  private sealed trait InternalMessage extends Message with PossiblyHarmful
  private final case class CancelTransaction(id: TxId) extends InternalMessage

  // Events
  sealed trait Event

  // Props
  def props(routingTable: ActorRef, port: Int): Props = {
    Props(new DHTMessageDispatcher(routingTable, port))
  }

  private final case class Transaction(id: TxId, address: InetSocketAddress, message: DHTQuery, receiver: ActorRef, cancelSchedule: Cancellable)
}

//noinspection ActorMutableStateInspection
class DHTMessageDispatcher(routingTable: ActorRef, port: Int) extends Actor with ActorLogging {
  import context.dispatcher

  import DHTMessageDispatcher._
  private[this] implicit val timeout = Timeout(5 seconds)
  private[this] val txMap = mutable.AnyRefMap.empty[TxId, Transaction]

  def receive = {
    case Udp.Bound(local) ⇒
      context.become(receiveReady(local, sender()))
  }

  def receiveReady(local: InetSocketAddress, socket: ActorRef): Receive = {
    case SendQuery(address, message) ⇒
      val txId = DHTMessages.transactionId()
      val header = DHTMessageHeader(txId, 'q', "TS")
      val dict = BEncodedDictionary(header.toDict.values ++ message.toDict.values)

      log.debug("Query to {} (txid = {}): {}", address, txId, message)
      socket ! Udp.Send(dict.toBytes, address)

      val cancelSchedule = context.system.scheduler.scheduleOnce(timeout.duration, self, CancelTransaction(txId))
      val transaction = Transaction(txId, address, message, sender(), cancelSchedule)
      txMap(txId) = transaction

    case CancelTransaction(txId) ⇒
      txMap.remove(txId) match {
        case Some(transaction) ⇒
          log.warning("Query timeout: {}", transaction)
          transaction.receiver ! SendQuery.Failure(new TimeoutException("Query timeout"))
          txMap -= txId

        case _ ⇒
          // Ignore
      }

    case Udp.Received(data, remote) ⇒
      val dict = Try(BEncode.parse(data.toArray[Byte]).head.asInstanceOf[BEncodedDictionary])
      val header = dict.map(DHTMessageHeader.fromDict)
      header match {
        case Success(DHTMessageHeader(txId, 'q', _)) ⇒ // Query
          dict.map(DHTQuery.fromDict) match {
            case Success(query) ⇒
              (routingTable ? RequestResponse(remote, txId, query))
                .mapTo[RequestResponse.Status]
                .foreach {
                  case RequestResponse.Success(response) ⇒
                    val header = DHTMessageHeader(txId, 'r', "TS")
                    val dict = BEncodedDictionary(header.toDict.values ++ response.values)
                    log.debug("Transaction success (txid = {}, address = {}): {}", txId, remote, response)
                    socket ! Udp.Send(dict.toBytes, remote)

                  case RequestResponse.Failure(exc) ⇒
                    log.error(exc, "Transaction failed (txid = {}, address = {}): {}", txId, remote, query)
                    val header = DHTMessageHeader(txId, 'e', "TS")
                    val error = DHTError(201, exc.toString)
                    val dict = BEncodedDictionary(header.toDict.values ++ error.toDict.values)
                    socket ! Udp.Send(dict.toBytes, remote)
                }

            case Failure(_) ⇒
              log.warning("Invalid query: {}", dict.get)
          }

        case Success(DHTMessageHeader(txId, 'r', _)) ⇒ // Response
          txMap.remove(txId) match {
            case Some(Transaction(_, _, _, receiver, cancelSchedule)) ⇒
              log.debug("Query success (txid = {}): {}", txId, dict.get)
              receiver ! SendQuery.Success(dict.get)
              cancelSchedule.cancel()

            case None ⇒
              log.warning("Invalid transaction id, message dropped: {}", dict.get)
          }

        case Success(DHTMessageHeader(txId, 'e', _)) ⇒ // Error
          dict.map(DHTError.fromDict) match {
            case Success(err: DHTError) ⇒
              txMap.remove(txId) match {
                case Some(Transaction(_, _, _, receiver, cancelSchedule)) ⇒
                  log.debug("Query failure (txid = {}): {}", txId, dict.get)
                  receiver ! SendQuery.Failure(err)
                  cancelSchedule.cancel()

                case None ⇒
                  log.warning("Invalid transaction id, message dropped: {}", dict.get)
              }

            case _ ⇒
              // Ignore
          }

        case _ ⇒
          log.warning("Invalid DHT message: {}", data.utf8String)
      }

    case Udp.Unbind  ⇒
      socket ! Udp.Unbind

    case Udp.Unbound ⇒
      context.stop(self)
  }

  override def preStart(): Unit = {
    import context.system
    super.preStart()
    IO(Udp) ! Udp.Bind(self, new InetSocketAddress("0.0.0.0", port))
  }
}
