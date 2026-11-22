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

import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * One run's schema-5 output in the plugin's managed directory: the fast {@code state-v5.json} and,
 * only when the account document is switched on, {@code account-v5.json}. The directory is resolved
 * on the publisher thread on first use, so enabling the plugin never waits on the filesystem. A
 * directory that cannot be opened disables schema 5 for the run and leaves schemas 3 and 4
 * publishing exactly as before.
 *
 * Used from the run's publisher thread only.
 */
@Slf4j
final class ManagedExport
{
	static final String STATE_FILE_NAME = "state-v5.json";

	// Schema 4's worst case plus the schema-5 suffix, with room to spare. Pinned by a test.
	static final int MAX_STATE_BYTES = 32_768;

	private final Supplier<SnapshotStore> stores;
	private final LongSupplier clock;
	private final boolean accountDocument;
	private boolean resolved;
	private ManagedFileWriter stateWriter;
	private ManagedFileWriter accountWriter;

	// The readiness last logged, so a transition is logged once rather than on every publication.
	private String loggedReadiness;

	ManagedExport(Supplier<SnapshotStore> stores, LongSupplier clock, boolean accountDocument)
	{
		this.stores = Objects.requireNonNull(stores, "stores");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.accountDocument = accountDocument;
	}

	/** Whether the managed directory is available to this run. Resolved once. */
	boolean isAvailable()
	{
		if (!resolved)
		{
			resolved = true;
			SnapshotStore store = null;
			try
			{
				store = stores.get();
			}
			catch (RuntimeException e)
			{
				log.warn("Facette Telemetry cannot open its managed directory; schema 5 is not written", e);
			}
			if (store != null)
			{
				stateWriter = new ManagedFileWriter(store, STATE_FILE_NAME, MAX_STATE_BYTES, clock);
				if (accountDocument)
				{
					accountWriter = new ManagedFileWriter(store, TelemetrySnapshot.ACCOUNT_FILE_NAME,
						TelemetryAccountState.MAX_DOCUMENT_BYTES, clock);
				}
			}
		}
		return stateWriter != null;
	}

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
