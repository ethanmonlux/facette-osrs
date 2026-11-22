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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The managed-directory writer keeps the legacy writer's guarantees: complete replacement, a size
 * bound that refuses rather than truncates, authority checked at commit, and no temporary file left
 * behind.
 */
public class ManagedFileWriterTest
{
	private static final long NOW = 1_770_000_000_000L;

	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	private Path directory;
	private PathSnapshotStore store;
	private final ReentrantLock lock = new ReentrantLock();

	@Before
	public void setUp() throws IOException
	{
		// A directory that does not exist yet, as the managed one does not on a first start.
		directory = temporaryFolder.getRoot().toPath().resolve("plugin-data").resolve("facette-companion");
		store = new PathSnapshotStore(directory);
	}

	private ManagedFileWriter writer(int maxBytes)
	{
		return new ManagedFileWriter(store, "state-v5.json", maxBytes, () -> NOW);
	}

	@Test
	public void writesTheWholeDocumentIntoADirectoryItCreates() throws IOException
	{
		byte[] payload = "{\"schema\":5}".getBytes(StandardCharsets.UTF_8);
		assertEquals(payload.length, writer(1_024).write(payload, lock, () -> true));
		assertArrayEquals(payload, Files.readAllBytes(directory.resolve("state-v5.json")));
		assertEquals("exactly one replacement, from a staged sibling", 1, store.replacements.size());
		assertTrue(store.replacements.get(0).startsWith("state-v5-"));
		assertOnlyTargetRemains("state-v5.json");
	}

	@Test
	public void replacesAnExistingDocumentRatherThanAppending() throws IOException
	{
		ManagedFileWriter writer = writer(1_024);
		writer.write("{\"seq\":1,\"padding\":\"xxxxxxxxxxxx\"}".getBytes(StandardCharsets.UTF_8),
			lock, () -> true);
		byte[] second = "{\"seq\":2}".getBytes(StandardCharsets.UTF_8);
		writer.write(second, lock, () -> true);
		assertArrayEquals(second, Files.readAllBytes(directory.resolve("state-v5.json")));
	}

	@Test
	public void aDocumentOverTheBoundIsRefusedAndNothingIsWritten() throws IOException
	{
		try
		{
			writer(8).write("{\"schema\":5}".getBytes(StandardCharsets.UTF_8), lock, () -> true);
			fail("an oversized document must be refused");
		}
		catch (ManagedFileWriter.DocumentTooLargeException expected)
		{
			// Refused before anything is staged.
		}
		assertFalse(Files.exists(directory.resolve("state-v5.json")));
	}

	@Test
	public void aWriteOvertakenByANewerRunLeavesTheTargetAndNoTemporaryFile() throws IOException
	{
		ManagedFileWriter writer = writer(1_024);
		byte[] first = "{\"seq\":1}".getBytes(StandardCharsets.UTF_8);
		writer.write(first, lock, () -> true);
		try
		{
			writer.write("{\"seq\":2}".getBytes(StandardCharsets.UTF_8), lock, () -> false);
			fail("an unauthorized commit must not land");
		}
		catch (TelemetrySnapshotWriter.CommitNotAuthorizedException expected)
		{
			// The publisher treats this as "did not reach the file".
		}
		assertArrayEquals(first, Files.readAllBytes(directory.resolve("state-v5.json")));
		assertOnlyTargetRemains("state-v5.json");
	}

	@Test
	public void fallsBackToAReplacingMoveWhenAtomicMovesAreUnsupported() throws IOException
	{
		store.refuseNextAtomicMove = true;
		byte[] payload = "{\"seq\":3}".getBytes(StandardCharsets.UTF_8);
		writer(1_024).write(payload, lock, () -> true);
		assertArrayEquals(payload, Files.readAllBytes(directory.resolve("state-v5.json")));
		assertOnlyTargetRemains("state-v5.json");
	}

	@Test
	public void sweepsOnlyItsOwnAbandonedTemporaryFilesAndOnlyOnce() throws IOException
	{
		Files.createDirectories(directory);
		Path abandoned = directory.resolve("state-v5-123.tmp");
		Path recent = directory.resolve("state-v5-456.tmp");
		Path otherWriters = directory.resolve("other-v5-789.tmp");
		for (Path path : new Path[]{abandoned, recent, otherWriters})
		{
			Files.write(path, new byte[]{1});
		}
		Files.setLastModifiedTime(abandoned, FileTime.fromMillis(NOW - 120_000L));
		Files.setLastModifiedTime(recent, FileTime.fromMillis(NOW - 1_000L));
		Files.setLastModifiedTime(otherWriters, FileTime.fromMillis(NOW - 120_000L));

		writer(1_024).write("{}".getBytes(StandardCharsets.UTF_8), lock, () -> true);

		assertFalse("an abandoned file of this writer's own is swept", Files.exists(abandoned));
		assertTrue("a recent one may be another client's write in flight", Files.exists(recent));
		assertTrue("another writer's file is never touched", Files.exists(otherWriters));
	}

	private void assertOnlyTargetRemains(String target) throws IOException
	{
		try (Stream<Path> entries = Files.list(directory))
		{
			entries.forEach(entry -> assertEquals("unexpected file " + entry, target,
				entry.getFileName().toString()));
		}
	}
}
