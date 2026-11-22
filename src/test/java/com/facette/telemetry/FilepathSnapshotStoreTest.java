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
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import net.runelite.client.util.Filepath;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The production store, over RuneLite's own {@link Filepath}, rooted the way RuneLite roots a
 * plugin's managed directory. Every other test writes through a plain-directory stand-in; this one
 * proves the real one creates the directory, replaces a file whole, and sweeps only the writer's
 * own abandoned temporary files.
 */
public class FilepathSnapshotStoreTest
{
	private static final long NOW = 1_770_000_000_000L;

	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private Path directory;
	private FilepathSnapshotStore store;

	@Before
	public void setUp()
	{
		// As Plugin.getPluginDirectory() does: plugin-data rooted, then the internal name, rooted.
		Path pluginData = folder.getRoot().toPath().resolve("plugin-data");
		directory = pluginData.resolve("facette-companion");
		store = new FilepathSnapshotStore(
			Filepath.Unchecked.getRooted(pluginData).joinSegment("facette-companion").rooted());
	}

	private int write(ManagedFileWriter writer, String text) throws IOException
	{
		return writer.write(text.getBytes(StandardCharsets.UTF_8), new ReentrantLock(), () -> true);
	}

	@Test
	public void createsTheDirectoryAndReplacesTheFileWhole() throws IOException
	{
		ManagedFileWriter writer = new ManagedFileWriter(store, "state-v3.json", 1_024, () -> NOW);
		assertFalse(Files.exists(directory));
		write(writer, "{\"first\":true}");
		write(writer, "{\"second\":true}");

		assertArrayEquals("{\"second\":true}".getBytes(StandardCharsets.UTF_8),
			Files.readAllBytes(directory.resolve("state-v3.json")));
		try (java.util.stream.Stream<Path> files = Files.list(directory))
		{
			assertTrue("no temporary file is left", files.noneMatch(p -> p.toString().endsWith(".tmp")));
		}
	}

	@Test
	public void sweepsOnlyTheWritersOwnAbandonedTemporaryFiles() throws IOException
	{
		Files.createDirectories(directory);
		Path abandoned = directory.resolve("state-v4-123.tmp");
		Path otherWriter = directory.resolve("state-v3-456.tmp");
		Path neighbour = directory.resolve("state-v2.json");
		for (Path path : new Path[]{abandoned, otherWriter, neighbour})
		{
			Files.write(path, new byte[]{1});
			Files.setLastModifiedTime(path, FileTime.fromMillis(NOW - 600_000L));
		}

		write(new ManagedFileWriter(store, "state-v4.json", 1_024, () -> NOW), "{}");

		assertFalse("its own abandoned file is swept", Files.exists(abandoned));
		assertTrue("another writer's file is not its to sweep", Files.exists(otherWriter));
		assertTrue("an earlier schema's file is left alone", Files.exists(neighbour));
		assertTrue(Files.exists(directory.resolve("state-v4.json")));
	}
}
