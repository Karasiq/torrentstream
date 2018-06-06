package com.karasiq.bittorrent.dht

import akka.actor.ActorRef

final case class DHTContext(selfNodeId: NodeId, routingTable: ActorRef, messageDispatcher: ActorRef)
