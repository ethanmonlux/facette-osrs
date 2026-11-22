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
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * One run's output in the plugin's managed directory, which RuneLite provides through
 * {@code Filepath}: {@code state-v3.json}, {@code state-v4.json}, {@code state-v5.json} and, when the
 * run writes one, {@code account-v5.json}. The directory is opened on the publisher thread on first
 * use, so enabling the plugin never waits on the filesystem. Until it can be opened the run writes
 * nothing, and it is asked for again every few seconds.
 *
 * Used from the run's publisher thread only.
 */
@Slf4j
final class ManagedExport
{
	static final String STATE_FILE_NAME = "state-v5.json";

	// Schema 4's worst case plus the schema-5 suffix, with room to spare. Pinned by a test.
	static final int MAX_STATE_BYTES = 32_768;

	// How long after a failed attempt the directory is asked for again. Opening it can fail
	// transiently: RuneLite moves an earlier version's directory into place on first use, and a
	// second client doing the same at the same moment, or a file held open, can make that fail.
	static final long RETRY_INTERVAL_MILLIS = 5_000L;

	private final Supplier<SnapshotStore> stores;
	private final LongSupplier clock;
	private final boolean schema5;
	private final boolean accountDocument;
	private boolean attempted;
	private long lastAttemptAtMillis;
	private boolean failureLogged;
	// Volatile only because a startup log line may describe a target from another thread.
	private volatile SnapshotStore resolvedStore;
	private ManagedFileWriter stateWriter;
	private ManagedFileWriter accountWriter;

	// The directory every file of this run is written through. It resolves the real store on first
	// use; until then nothing has touched the filesystem.
	private final SnapshotStore store = new SnapshotStore()
	{
		@Override
		public String describe(String fileName)
		{
			SnapshotStore target = resolvedStore;
			return target != null ? target.describe(fileName) : fileName;
		}

		@Override
		public void createDirectories() throws IOException
		{
			require().createDirectories();
		}

		@Override
		public StagedFile createTempFile(String prefix, String suffix) throws IOException
		{
			return require().createTempFile(prefix, suffix);
		}

		@Override
		public void deleteStale(String prefix, String suffix, long cutoffMillis) throws IOException
		{
			require().deleteStale(prefix, suffix, cutoffMillis);
		}
	};

	// The readiness last logged, so a transition is logged once rather than on every publication.
	private String loggedReadiness;

	/**
	 * @param schema5 whether this run writes {@code state-v5.json}
	 * @param accountDocument whether this run writes {@code account-v5.json}; only with schema 5
	 */
	ManagedExport(Supplier<SnapshotStore> stores, LongSupplier clock, boolean schema5,
		boolean accountDocument)
	{
		this.stores = Objects.requireNonNull(stores, "stores");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.schema5 = schema5;
		this.accountDocument = schema5 && accountDocument;
	}

	/** The run's directory, for the schema-3 and schema-4 writers. Opened on first use. */
	SnapshotStore getStore()
	{
		return store;
	}

	/**
	 * Whether the managed directory is available to this run. Once opened it stays open for the
	 * run. Until then it is asked for at most once every {@link #RETRY_INTERVAL_MILLIS}, and nothing
	 * is written in between.
	 */
	boolean isAvailable()
	{
		if (resolvedStore != null)
		{
			return true;
		}
		long now = clock.getAsLong();
		if (attempted && now - lastAttemptAtMillis < RETRY_INTERVAL_MILLIS
			&& now >= lastAttemptAtMillis)
		{
			return false;
		}
		attempted = true;
		lastAttemptAtMillis = now;
		SnapshotStore opened = null;
		RuntimeException failure = null;
		try
		{
			opened = stores.get();
		}
		catch (RuntimeException e)
		{
			failure = e;
		}
		if (opened == null)
		{
			if (!failureLogged)
			{
				failureLogged = true;
				log.warn("Facette Telemetry cannot open its managed directory; nothing is written "
					+ "until it can, retried every {} ms", RETRY_INTERVAL_MILLIS, failure);
			}
			return false;
		}
		if (failureLogged)
		{
			log.info("Facette Telemetry opened its managed directory; publishing resumes");
		}
		if (schema5)
		{
			stateWriter = new ManagedFileWriter(store, STATE_FILE_NAME, MAX_STATE_BYTES, clock);
		}
		if (accountDocument)
		{
			accountWriter = new ManagedFileWriter(store, TelemetrySnapshot.ACCOUNT_FILE_NAME,
				TelemetryAccountState.MAX_DOCUMENT_BYTES, clock);
		}
		// Published last, so the writers above exist before anything can use the directory.
		resolvedStore = opened;
		return true;
	}

	private SnapshotStore require() throws IOException
	{
		if (!isAvailable())
		{
			throw new IOException("the plugin's managed directory is unavailable");
		}
		return resolvedStore;
	}

	/** The schema-5 state file's writer, or null when this run writes no schema 5. */
	ManagedFileWriter getStateWriter()
	{
		return stateWriter;
	}

	/** The account document's writer, or null when this run writes no account document. */
	ManagedFileWriter getAccountWriter()
	{
		return accountWriter;
	}

	/**
	 * Logs a change of readiness: the state and the fixed names of what is not current. Never any
	 * part of the payload. This is the diagnostic for a character that is logged in but not yet fully
	 * read, and it says which reading is missing instead of leaving it to be guessed.
	 */
	void logReadiness(String state, List<String> missing)
	{
		String readiness = state + " " + missing;
		if (readiness.equals(loggedReadiness))
		{
			return;
		}
		loggedReadiness = readiness;
		if (missing.isEmpty())
		{
			log.info("Facette Telemetry readiness: {}", state);
		}
		else
		{
			log.info("Facette Telemetry readiness: {}, not yet current: {}", state, missing);
		}
	}
}
