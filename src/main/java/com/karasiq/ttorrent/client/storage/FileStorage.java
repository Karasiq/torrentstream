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

import akka.actor.ActorRef;
import akka.util.ByteString;
import com.karasiq.torrentstream.TorrentChunk;
import com.karasiq.torrentstream.TorrentFileStart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;


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
	private final String file;
	private final long offset;
	private final long size;

	private RandomAccessFile raf;
	private FileChannel channel;
	private final ActorRef writer;

	public FileStorage(String file, long offset, long size, ActorRef writer)
		throws IOException {
		this.writer = writer;
		this.target = File.createTempFile("torrentstream", String.valueOf(file.hashCode()));
		this.file = file;
		this.offset = offset;
		this.size = size;
	}

	private void initFile() throws IOException {
		if (this.channel == null) {
			writer.tell(TorrentFileStart.apply(this.file, size), ActorRef.noSender());
			raf = new RandomAccessFile(this.target, "rw");
			if (size > 524288000) {
				raf.setLength(524288000);
			} else {
				raf.setLength(size);
			}
			channel = raf.getChannel();
		}
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
		if (this.channel != null) {
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
		} else {
			final int size = buffer.remaining();
			buffer.position(buffer.position() + size);
			return size;
		}
	}

	@Override
	public int write(ByteBuffer buffer, long offset) throws IOException {
		this.initFile();
		final ByteString data = ByteString.fromByteBuffer(buffer);
		writer.tell(TorrentChunk.apply(this.file, offset, data), ActorRef.noSender());

		final int requested = data.length();
		final long fileOffset = getFileOffset(offset, requested);
		if (offset + requested > this.size) {
			throw new IllegalArgumentException("Invalid storage write request!");
		}
		return this.channel.write(data.asByteBuffer(), fileOffset);
	}

	@SuppressWarnings("ResultOfMethodCallIgnored")
	@Override
	public synchronized void close() throws IOException {
		//logger.debug("Closing file channel to " + this.current.getName() + "...");
		if (this.channel != null) {
			if (this.channel.isOpen()) {
				this.channel.force(true);
			}
			this.raf.close();
			if (this.target.exists()) {
				this.target.delete();
			}
			this.channel = null;
		}
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
