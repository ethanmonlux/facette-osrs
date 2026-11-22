/*
 * Copyright (c) 2026, Ethan Monlux
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.facette.telemetry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Writes one file in the plugin's managed directory as a complete replacement: stage into a
 * temporary sibling, force it to disk, close it, check commit authority under the lock, then move it
 * over the target, atomically where the filesystem supports it. A reader sees the previous document
 * or the new one, never a partial one. Every file the plugin writes goes through here, and every
 * filesystem operation goes through the {@link SnapshotStore}, which in production is RuneLite's
 * {@code Filepath}.
 */
final class ManagedFileWriter
{
	private static final String TEMP_SUFFIX = ".tmp";

	// Well above the publish interval, so a second client's in-flight file is never deleted.
	private static final long STALE_TEMP_AGE_MILLIS = 60_000L;

	static final class DocumentTooLargeException extends IOException
	{
		private static final long serialVersionUID = 1L;

		DocumentTooLargeException(String fileName, int size, int maxBytes)
		{
			super(fileName + " document of " + size + " bytes exceeds the " + maxBytes
				+ " byte limit");
		}
	}

	private final SnapshotStore store;
	private final String fileName;
	private final String tempPrefix;
	private final int maxBytes;
	private final LongSupplier clock;
	private boolean sweptStaleTemporaryFiles;

	ManagedFileWriter(SnapshotStore store, String fileName, int maxBytes, LongSupplier clock)
	{
		this.store = Objects.requireNonNull(store, "store");
		this.fileName = Objects.requireNonNull(fileName, "fileName");
		if (!fileName.endsWith(".json"))
		{
			throw new IllegalArgumentException("not a JSON file name: " + fileName);
		}
		this.tempPrefix = fileName.substring(0, fileName.length() - ".json".length()) + "-";
		if (maxBytes <= 0)
		{
			throw new IllegalArgumentException("maxBytes must be positive");
		}
		this.maxBytes = maxBytes;
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	String getFileName()
	{
		return fileName;
	}

	String describeTarget()
	{
		return store.describe(fileName);
	}

	/**
	 * Publishes one document and returns its size. Commit authority is checked after staging,
	 * immediately before the replacement, so a slow write that a newer run overtook is abandoned
	 * rather than landing stale.
	 */
	int write(byte[] payload, Lock commitLock, BooleanSupplier commitAuthorized) throws IOException
	{
		Objects.requireNonNull(payload, "payload");
		Objects.requireNonNull(commitLock, "commitLock");
		Objects.requireNonNull(commitAuthorized, "commitAuthorized");
		if (payload.length > maxBytes)
		{
			throw new DocumentTooLargeException(fileName, payload.length, maxBytes);
		}

		store.createDirectories();
		sweepStaleTemporaryFilesOnce();
		SnapshotStore.StagedFile temp = store.createTempFile(tempPrefix, TEMP_SUFFIX);
		try
		{
			try (FileChannel channel = temp.openForWrite())
			{
				ByteBuffer buffer = ByteBuffer.wrap(payload);
				while (buffer.hasRemaining())
				{
					channel.write(buffer);
				}
				channel.force(true);
			}

			commitLock.lock();
			try
			{
				if (!commitAuthorized.getAsBoolean())
				{
					// One exception for every file, so the publisher treats any overtaken write
					// as "did not reach the file".
					throw new TelemetrySnapshotWriter.CommitNotAuthorizedException(
						store.describe(fileName));
				}
				replaceTarget(temp);
			}
			finally
			{
				commitLock.unlock();
			}
			return payload.length;
		}
		finally
		{
			// However the move ended, this attempt leaves no temporary file of ours behind.
			temp.deleteIfExists();
		}
	}

	private void replaceTarget(SnapshotStore.StagedFile temp) throws IOException
	{
		try
		{
			temp.replace(fileName,
				StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException | UnsupportedOperationException e)
		{
			// The only fallback: move the already-complete sibling with replacement. The target is
			// still never streamed into.
			temp.replace(fileName, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private void sweepStaleTemporaryFilesOnce()
	{
		if (sweptStaleTemporaryFiles)
		{
			return;
		}
		sweptStaleTemporaryFiles = true;
		try
		{
			store.deleteStale(tempPrefix, TEMP_SUFFIX, clock.getAsLong() - STALE_TEMP_AGE_MILLIS);
		}
		catch (IOException e)
		{
			// Not worth failing a publication over.
		}
	}
}
