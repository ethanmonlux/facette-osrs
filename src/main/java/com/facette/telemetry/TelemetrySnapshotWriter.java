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
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Writes a snapshot to its local target file as one complete replacement. The target is never
 * streamed into and never partially overwritten: each publication is serialized into a temporary
 * sibling, forced to disk, closed, and only then moved over the target, atomically where the
 * filesystem supports it. A reader that opens the target sees either the previous snapshot or the
 * new one, never a partial document.
 */
final class TelemetrySnapshotWriter
{
	static final int MAX_SNAPSHOT_BYTES = 16_384;

	/**
	 * Versioned in the name, so schema 2 lands beside any schema-1 file rather than on top of it. This
	 * writer names one target and sweeps only its own versioned temporary prefix, so a
	 * {@code state-v1.json} in the same directory is never opened, read, migrated, or deleted.
	 */
	static final String TARGET_FILE_NAME = "state-v2.json";

	private static final String TEMP_PREFIX = "state-v2-";
	private static final String TEMP_SUFFIX = ".tmp";

	/**
	 * Kept well above the publish interval so a concurrently running client's in-flight file is never
	 * deleted out from under it.
	 */
	private static final long STALE_TEMP_AGE_MILLIS = 60_000L;

	/**
	 * Injectable so the atomic path and the non-atomic fallback can both be exercised, since no
	 * portable filesystem refuses {@code ATOMIC_MOVE} on demand.
	 */
	@FunctionalInterface
	interface Mover
	{
		void move(Path source, Path target, CopyOption... options) throws IOException;
	}

	static final class SnapshotTooLargeException extends IOException
	{
		private static final long serialVersionUID = 1L;

		SnapshotTooLargeException(int size)
		{
			super("Snapshot of " + size + " bytes exceeds the " + MAX_SNAPSHOT_BYTES + " byte limit");
		}
	}

	/**
	 * Thrown when a fully staged snapshot is abandoned because a newer plugin run started while this
	 * one was writing. An {@link IOException} on purpose: every caller already treats a failed write
	 * as "did not reach the file", so the sequence and heartbeat bookkeeping are left untouched.
	 */
	static final class CommitNotAuthorizedException extends IOException
	{
		private static final long serialVersionUID = 1L;

		CommitNotAuthorizedException(Path target)
		{
			super("Publication is no longer authorized to replace " + target);
		}
	}

	private final Path directory;
	private final Path target;
	private final Mover mover;
	private final LongSupplier clock;

	private boolean sweptStaleTemporaryFiles;

	TelemetrySnapshotWriter(Path directory)
	{
		this(directory, Files::move, System::currentTimeMillis);
	}

	TelemetrySnapshotWriter(Path directory, Mover mover, LongSupplier clock)
	{
		this.directory = Objects.requireNonNull(directory, "directory");
		this.target = directory.resolve(TARGET_FILE_NAME);
		this.mover = Objects.requireNonNull(mover, "mover");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	Path getTarget()
	{
		return target;
	}

	/**
	 * Publishes one snapshot, replacing the target with a complete document, and returns the number of
	 * UTF-8 bytes written. Everything slow happens first and touches only the temporary file; only
	 * then is {@code commitAuthorized} consulted and the target replaced, so a publication that staged
	 * slowly discovers a newer run has taken over before it does any damage. When it is false the
	 * staged file is deleted and the target left alone.
	 *
	 * {@code commitLock} is held across the authorization check and the replacement only, never across
	 * staging, so a stalled write cannot block a newly enabled run from publishing.
	 */
	int write(TelemetrySnapshot snapshot, Lock commitLock, BooleanSupplier commitAuthorized)
		throws IOException
	{
		Objects.requireNonNull(commitLock, "commitLock");
		Objects.requireNonNull(commitAuthorized, "commitAuthorized");
		byte[] payload = snapshot.toJsonBytes();
		if (payload.length > MAX_SNAPSHOT_BYTES)
		{
			throw new SnapshotTooLargeException(payload.length);
		}

		Files.createDirectories(directory);
		sweepStaleTemporaryFilesOnce();

		Path temp = Files.createTempFile(directory, TEMP_PREFIX, TEMP_SUFFIX);
		try
		{
			try (FileChannel channel = FileChannel.open(temp,
				StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))
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
				// The last moment at which abandoning costs nothing. The lock is held from here
				// through the move, so the answer cannot go stale before the replacement.
				if (!commitAuthorized.getAsBoolean())
				{
					throw new CommitNotAuthorizedException(target);
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
			// Whether the move succeeded, failed, was refused, or never ran, no temporary file of
			// ours is left behind by this attempt.
			Files.deleteIfExists(temp);
		}
	}

	private void replaceTarget(Path temp) throws IOException
	{
		try
		{
			mover.move(temp, target,
				StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException | UnsupportedOperationException e)
		{
			// The only permitted fallback: move the already-complete sibling with replacement. The
			// target is still never streamed into or partially written.
			mover.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private void sweepStaleTemporaryFilesOnce() throws IOException
	{
		if (sweptStaleTemporaryFiles)
		{
			return;
		}
		sweptStaleTemporaryFiles = true;
		sweepStaleTemporaryFiles();
	}

	/**
	 * Removes temporary files this writer owns that a previous run abandoned, which only happens when
	 * a client was killed mid-write. Only this writer's own naming is considered, and only once older
	 * than {@link #STALE_TEMP_AGE_MILLIS}, so a second client's in-flight file is never removed.
	 */
	void sweepStaleTemporaryFiles() throws IOException
	{
		long cutoff = clock.getAsLong() - STALE_TEMP_AGE_MILLIS;
		try (DirectoryStream<Path> entries =
				 Files.newDirectoryStream(directory, TEMP_PREFIX + "*" + TEMP_SUFFIX))
		{
			for (Path entry : entries)
			{
				try
				{
					FileTime modified = Files.getLastModifiedTime(entry);
					if (modified.toMillis() < cutoff)
					{
						Files.deleteIfExists(entry);
					}
				}
				catch (IOException e)
				{
					// A temporary file we cannot stat or delete is left alone; it is not worth
					// failing a publication over.
				}
			}
		}
	}
}
