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
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.Objects;
import java.util.stream.Stream;
import net.runelite.client.util.Filepath;

/**
 * The plugin's own managed directory, reached only through RuneLite's {@link Filepath}. Every path
 * here is a single segment joined onto the directory RuneLite handed the plugin, so nothing written
 * through this store can land outside it.
 */
final class FilepathSnapshotStore implements SnapshotStore
{
	private final Filepath directory;

	FilepathSnapshotStore(Filepath directory)
	{
		this.directory = Objects.requireNonNull(directory, "directory");
	}

	@Override
	public String describe(String fileName)
	{
		return directory.joinSegment(fileName).toString();
	}

	@Override
	public void createDirectories() throws IOException
	{
		directory.createDirectories();
	}

	@Override
	public StagedFile createTempFile(String prefix, String suffix) throws IOException
	{
		Filepath temp = directory.createTempFile(prefix, suffix);
		return new StagedFile()
		{
			@Override
			public FileChannel openForWrite() throws IOException
			{
				return temp.openFileChannel(StandardOpenOption.WRITE,
					StandardOpenOption.TRUNCATE_EXISTING);
			}

			@Override
			public void replace(String targetName, CopyOption... options) throws IOException
			{
				temp.moveTo(directory.joinSegment(targetName), options);
			}

			@Override
			public void deleteIfExists() throws IOException
			{
				temp.deleteIfExists();
			}
		};
	}

	@Override
	public void deleteStale(String prefix, String suffix, long cutoffMillis) throws IOException
	{
		try (Stream<Filepath> entries = directory.walk(1))
		{
			Iterator<Filepath> it = entries.iterator();
			while (it.hasNext())
			{
				Filepath entry = it.next();
				if (entry.isRoot())
				{
					continue;
				}
				String name = entry.getFileName();
				if (!name.startsWith(prefix) || !name.endsWith(suffix))
				{
					continue;
				}
				try
				{
					if (entry.getLastModifiedTime().toMillis() < cutoffMillis)
					{
						entry.deleteIfExists();
					}
				}
				catch (IOException e)
				{
					// Not worth failing a publication over.
				}
			}
		}
	}
}
