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
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Writes the schema-3 or schema-4 snapshot, {@code state-v3.json} or {@code state-v4.json}, as one
 * complete replacement in the plugin's own RuneLite-managed directory. The file rules are
 * {@link ManagedFileWriter}'s: stage into a temporary sibling, force it to disk, check commit
 * authority under the lock, then move it over the target, atomically where the filesystem supports
 * it. A reader sees either the previous snapshot or the new one, never a partial document.
 *
 * This class owns only what is particular to these two files: which schema each serializes, its
 * file name, and its size ceiling.
 */
final class TelemetrySnapshotWriter
{
	// A backstop rather than the schema's bound: every exported string is length-bounded and every
	// exported collection is fixed-size or bounded by a game enumeration, so no document the plugin
	// can build comes near this. Schema 3's measured worst case is pinned in TelemetrySnapshotTest,
	// and this number is chosen to sit above it with room to spare.
	static final int MAX_SNAPSHOT_BYTES = 24_576;

	// Versioned, so each schema lands beside the others rather than on top of them. The writer sweeps
	// only its own temporary files, so an older state-v1.json or state-v2.json in the directory is
	// never touched: it simply stops being updated and goes stale.
	static final String TARGET_FILE_NAME = "state-v3.json";

	// Schema 4's own file, written beside schema 3's rather than over it, so a schema-3 reader keeps
	// reading exactly what it read before.
	static final String SCHEMA_4_TARGET_FILE_NAME = "state-v4.json";

	/**
	 * Thrown when a staged document is abandoned because a newer plugin run started while this one
	 * was writing. An IOException on purpose: every caller already treats a failed write as "did not
	 * reach the file", so the sequence and heartbeat bookkeeping are left untouched.
	 */
	static final class CommitNotAuthorizedException extends IOException
	{
		private static final long serialVersionUID = 1L;

		CommitNotAuthorizedException(String target)
		{
			super("Publication is no longer authorized to replace " + target);
		}
	}

	private final int schema;
	private final ManagedFileWriter file;

	TelemetrySnapshotWriter(SnapshotStore store)
	{
		this(store, TelemetrySnapshot.SCHEMA, System::currentTimeMillis);
	}

	TelemetrySnapshotWriter(SnapshotStore store, int schema, LongSupplier clock)
	{
		Objects.requireNonNull(store, "store");
		if (schema != TelemetrySnapshot.SCHEMA && schema != TelemetrySnapshot.SCHEMA_4)
		{
			throw new IllegalArgumentException("no such schema: " + schema);
		}
		this.schema = schema;
		this.file = new ManagedFileWriter(store,
			schema == TelemetrySnapshot.SCHEMA ? TARGET_FILE_NAME : SCHEMA_4_TARGET_FILE_NAME,
			MAX_SNAPSHOT_BYTES, clock);
	}

	/** The target's file name. */
	String getFileName()
	{
		return file.getFileName();
	}

	/** Where the target is, for a log line. Never parsed. */
	String getTarget()
	{
		return file.describeTarget();
	}

	/**
	 * Publishes one snapshot and returns the number of UTF-8 bytes written. Commit authority is
	 * checked after staging, immediately before the replacement, so a slow publication finds out a
	 * newer run took over before it does damage.
	 */
	int write(TelemetrySnapshot snapshot, Lock commitLock, BooleanSupplier commitAuthorized)
		throws IOException
	{
		return file.write(snapshot.toJsonBytes(schema), commitLock, commitAuthorized);
	}
}
