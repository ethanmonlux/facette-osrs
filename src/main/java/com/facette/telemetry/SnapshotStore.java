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
import java.nio.channels.FileChannel;
import java.nio.file.CopyOption;

/**
 * One directory the plugin may publish into, reduced to the handful of operations an atomic
 * replacement needs. Schema 5 is written through RuneLite's managed file API and its tests through
 * a temporary folder, and this is what lets one writer serve both without either side reaching
 * around the other.
 */
interface SnapshotStore
{
	/** Where a named file in this store is, for a log line. Never parsed. */
	String describe(String fileName);

	void createDirectories() throws IOException;

	/** A new, empty file in this store, named with the given prefix and suffix. */
	StagedFile createTempFile(String prefix, String suffix) throws IOException;

	/**
	 * Deletes files in this store, and only directly in it, whose names start with the prefix and end
	 * with the suffix and that were last modified before the cutoff. A file that cannot be inspected
	 * or deleted is left alone.
	 */
	void deleteStale(String prefix, String suffix, long cutoffMillis) throws IOException;

	interface StagedFile
	{
		FileChannel openForWrite() throws IOException;

		/** Moves this file over the named file in the same store. */
		void replace(String targetName, CopyOption... options) throws IOException;

		void deleteIfExists() throws IOException;
	}
}
