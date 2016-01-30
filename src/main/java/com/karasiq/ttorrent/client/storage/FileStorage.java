/**
 * Copyright (C) 2011-2012 Turn, Inc.
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
package com.karasiq.ttorrent.client.storage;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;

import akka.actor.ActorRef;
import akka.util.ByteString;
import com.karasiq.torrentstream.TorrentChunk;
import com.karasiq.torrentstream.TorrentFileEnd;
import com.karasiq.torrentstream.TorrentFileStart;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Single-file torrent byte data storage.
 *
 * <p>
 * This implementation of TorrentByteStorageFile provides a torrent byte data
 * storage relying on a single underlying file and uses a RandomAccessFile
 * FileChannel to expose thread-safe read/write methods.
 * </p>
 *
 * @author mpetazzoni
 */
public class FileStorage implements TorrentByteStorage {

	private static final Logger logger =
		LoggerFactory.getLogger(FileStorage.class);

	private final File target;
//	private final File partial;
	private final long offset;
	private final long size;

	private RandomAccessFile raf;
	private FileChannel channel;
	private final ActorRef writer;

	public FileStorage(File file, long offset, long size, ActorRef writer)
		throws IOException {
		this.writer = writer;
		this.target = file;
		this.offset = offset;
		this.size = size;

		writer.tell(TorrentFileStart.apply(file.toString(), size), ActorRef.noSender());
		raf = new RandomAccessFile(File.createTempFile("torrentstream", file.getName()), "rw");
		raf.setLength(size);
		channel = raf.getChannel();
	}

	protected long offset() {
		return this.offset;
	}

	@Override
	public long size() {
		return this.size;
	}


	private long getFileOffset(long offset, long size) {
		while (offset + size >= 524288000) {
			offset -= 524288000;
			if (offset < 0) {
				return 0;
			}
		}
		return offset;
	}

	@Override
	public int read(ByteBuffer buffer, long offset) throws IOException {
		final int requested = buffer.remaining();
		final long fileOffset = getFileOffset(offset, requested);

		if (offset + requested > this.size) {
			throw new IllegalArgumentException("Invalid storage read request!");
		}

		int bytes = this.channel.read(buffer, fileOffset);
		if (bytes < requested) {
			throw new IOException("Storage underrun!");
		}

		return bytes;
	}

	@Override
	public int write(ByteBuffer buffer, long offset) throws IOException {
		final ByteString data = ByteString.fromByteBuffer(buffer);
		writer.tell(TorrentChunk.apply(target.toString(), offset, data), ActorRef.noSender());

		final int requested = data.length();
		final long fileOffset = getFileOffset(offset, requested);
		if (offset + requested > this.size) {
			throw new IllegalArgumentException("Invalid storage write request!");
		}
		return this.channel.write(data.asByteBuffer(), fileOffset);
	}

	@Override
	public synchronized void close() throws IOException {
		//logger.debug("Closing file channel to " + this.current.getName() + "...");
		if (this.channel.isOpen()) {
			this.channel.force(true);
		}
		this.raf.close();
		writer.tell(TorrentFileEnd.apply(target.toString()), ActorRef.noSender());
	}

	/** Move the partial file to its final location.
	 */
	@Override
	public synchronized void finish() throws IOException {
//		logger.debug("Closing file channel to " + this.current.getName() +
//			" (download complete).");
//		if (this.channel.isOpen()) {
//			this.channel.force(true);
//		}

		// Nothing more to do if we're already on the target file.
//		if (this.isFinished()) {
//			return;
//		}

//		this.raf.close();
//		FileUtils.deleteQuietly(this.target);
//		FileUtils.moveFile(this.current, this.target);
//
//		logger.debug("Re-opening torrent byte storage at {}.",
//				this.target.getAbsolutePath());
//
//		this.raf = new RandomAccessFile(this.target, "rw");
//		this.raf.setLength(this.size);
//		this.channel = this.raf.getChannel();
//		this.current = this.target;
//
//		FileUtils.deleteQuietly(this.partial);
//		logger.info("Moved torrent data from {} to {}.",
//			this.partial.getName(),
//			this.target.getName());
	}

	@Override
	public boolean isFinished() {
		return false;
//		return this.current.equals(this.target);
	}
}
