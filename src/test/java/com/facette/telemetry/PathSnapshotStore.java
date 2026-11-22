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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link SnapshotStore} over a plain directory, for tests. Production schema-5 output goes through
 * RuneLite's managed file API instead; this stands in for it so the writer's rules can be exercised
 * against a real filesystem without touching the user's RuneLite directory.
 */
final class PathSnapshotStore implements SnapshotStore
{
	private final Path directory;

	/** Every replacement made, as "temp name -> target name", in order. */
	final List<String> replacements = new ArrayList<>();

	/** The options of every replacement attempted, in order, including refused or failed ones. */
	final List<List<CopyOption>> replaceOptions = new ArrayList<>();

	/** When set, the next atomic replacement fails as unsupported, once. */
	boolean refuseNextAtomicMove;

	/** When set, the next replacement fails with this, once. */
	IOException failNextReplace;

	PathSnapshotStore(Path directory)
	{
		this.directory = directory;
	}

	Path resolve(String fileName)
	{
		return directory.resolve(fileName);
	}

	@Override
	public String describe(String fileName)
	{
		return directory.resolve(fileName).toString();
	}

	@Override
	public void createDirectories() throws IOException
	{
		Files.createDirectories(directory);
	}

	@Override
	public StagedFile createTempFile(String prefix, String suffix) throws IOException
	{
		Path temp = Files.createTempFile(directory, prefix, suffix);
		return new StagedFile()
		{
			@Override
			public FileChannel openForWrite() throws IOException
			{
				return FileChannel.open(temp, StandardOpenOption.WRITE,
					StandardOpenOption.TRUNCATE_EXISTING);
			}

			@Override
			public void replace(String targetName, CopyOption... options) throws IOException
			{
				replaceOptions.add(java.util.Arrays.asList(options));
				if (failNextReplace != null)
				{
					IOException failure = failNextReplace;
					failNextReplace = null;
					throw failure;
				}
				for (CopyOption option : options)
				{
					if (option == java.nio.file.StandardCopyOption.ATOMIC_MOVE && refuseNextAtomicMove)
					{
						refuseNextAtomicMove = false;
						throw new java.nio.file.AtomicMoveNotSupportedException(
							temp.toString(), targetName, "simulated");
					}
				}
				Files.move(temp, directory.resolve(targetName), options);
				replacements.add(temp.getFileName() + " -> " + targetName);
			}

			@Override
			public void deleteIfExists() throws IOException
			{
				Files.deleteIfExists(temp);
			}
		};
	}

	@Override
	public void deleteStale(String prefix, String suffix, long cutoffMillis) throws IOException
	{
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, prefix + "*" + suffix))
		{
			for (Path entry : entries)
			{
				if (Files.getLastModifiedTime(entry).toMillis() < cutoffMillis)
				{
					Files.deleteIfExists(entry);
				}
			}
		}
	}
}
