/**
 * Copyright (C) 2012 Turn, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.karasiq.ttorrent.common.protocol.udp;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;

/**
 * The announce response message for the UDP tracker protocol.
 *
 * @author mpetazzoni
 */
public class UDPAnnounceResponseMessage
	extends com.karasiq.ttorrent.common.protocol.udp.UDPTrackerMessage.UDPTrackerResponseMessage
	implements com.karasiq.ttorrent.common.protocol.TrackerMessage.AnnounceResponseMessage {

	private static final int UDP_ANNOUNCE_RESPONSE_MIN_MESSAGE_SIZE = 20;

	private final int actionId = com.karasiq.ttorrent.common.protocol.TrackerMessage.Type.ANNOUNCE_RESPONSE.getId();
	private final int transactionId;
	private final int interval;
	private final int complete;
	private final int incomplete;
	private final List<com.karasiq.ttorrent.common.Peer> peers;

	private UDPAnnounceResponseMessage(ByteBuffer data, int transactionId,
		int interval, int complete, int incomplete, List<com.karasiq.ttorrent.common.Peer> peers) {
		super(com.karasiq.ttorrent.common.protocol.TrackerMessage.Type.ANNOUNCE_REQUEST, data);
		this.transactionId = transactionId;
		this.interval = interval;
		this.complete = complete;
		this.incomplete = incomplete;
		this.peers = peers;
	}

	@Override
	public int getActionId() {
		return this.actionId;
	}

	@Override
	public int getTransactionId() {
		return this.transactionId;
	}

	@Override
	public int getInterval() {
		return this.interval;
	}

	@Override
	public int getComplete() {
		return this.complete;
	}

	@Override
	public int getIncomplete() {
		return this.incomplete;
	}

	@Override
	public List<com.karasiq.ttorrent.common.Peer> getPeers() {
		return this.peers;
	}

	public static UDPAnnounceResponseMessage parse(ByteBuffer data)
		throws com.karasiq.ttorrent.common.protocol.TrackerMessage.MessageValidationException {
		if (data.remaining() < UDP_ANNOUNCE_RESPONSE_MIN_MESSAGE_SIZE ||
			(data.remaining() - UDP_ANNOUNCE_RESPONSE_MIN_MESSAGE_SIZE) % 6 != 0) {
			throw new com.karasiq.ttorrent.common.protocol.TrackerMessage.MessageValidationException(
				"Invalid announce response message size!");
		}

		if (data.getInt() != com.karasiq.ttorrent.common.protocol.TrackerMessage.Type.ANNOUNCE_RESPONSE.getId()) {
			throw new com.karasiq.ttorrent.common.protocol.TrackerMessage.MessageValidationException(
				"Invalid action code for announce response!");
		}

		int transactionId = data.getInt();
		int interval = data.getInt();
		int incomplete = data.getInt();
		int complete = data.getInt();

		List<com.karasiq.ttorrent.common.Peer> peers = new LinkedList<com.karasiq.ttorrent.common.Peer>();
		for (int i=0; i < data.remaining() / 6; i++) {
			try {
				byte[] ipBytes = new byte[4];
				data.get(ipBytes);
				InetAddress ip = InetAddress.getByAddress(ipBytes);
				int port =
					(0xFF & (int)data.get()) << 8 |
					(0xFF & (int)data.get());
				peers.add(new com.karasiq.ttorrent.common.Peer(new InetSocketAddress(ip, port)));
			} catch (UnknownHostException uhe) {
				throw new com.karasiq.ttorrent.common.protocol.TrackerMessage.MessageValidationException(
					"Invalid IP address in announce request!");
			}
		}

		return new UDPAnnounceResponseMessage(data,
			transactionId,
			interval,
			complete,
			incomplete,
			peers);
	}

	public static UDPAnnounceResponseMessage craft(int transactionId,
		int interval, int complete, int incomplete, List<com.karasiq.ttorrent.common.Peer> peers) {
		ByteBuffer data = ByteBuffer
			.allocate(UDP_ANNOUNCE_RESPONSE_MIN_MESSAGE_SIZE + 6*peers.size());
		data.putInt(com.karasiq.ttorrent.common.protocol.TrackerMessage.Type.ANNOUNCE_RESPONSE.getId());
		data.putInt(transactionId);
		data.putInt(interval);

		/**
		 * Leechers (incomplete) are first, before seeders (complete) in the packet.
		 */
		data.putInt(incomplete);
		data.putInt(complete);

		for (com.karasiq.ttorrent.common.Peer peer : peers) {
			byte[] ip = peer.getRawIp();
			if (ip == null || ip.length != 4) {
				continue;
			}

			data.put(ip);
			data.putShort((short)peer.getPort());
		}

		return new UDPAnnounceResponseMessage(data,
			transactionId,
			interval,
			complete,
			incomplete,
			peers);
	}
}

