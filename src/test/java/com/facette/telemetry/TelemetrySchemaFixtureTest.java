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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;
import net.runelite.client.plugins.PluginDescriptor;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;

/**
 * Holds the committed schema-2 fixtures and the serializer to the same contract. The fixtures are
 * the cross-repository consumer contract: the Facette adapter commits the same bytes, so if the
 * serializer changes shape without them, the contract has silently forked and this says so.
 *
 * They are read from the committed files rather than the processed classpath copy, so the bytes
 * checked here are the bytes in the repository. Each file holds the document and nothing else, with
 * no trailing newline.
 */
public class TelemetrySchemaFixtureTest
{
	private static final String POPULATED_FIXTURE = "src/test/resources/facette-osrs-state-v2.json";

	private static final String LOGGED_OUT_FIXTURE =
		"src/test/resources/facette-osrs-state-v2-logged-out.json";

	private static final String MANIFEST = "runelite-plugin.properties";

	@Test
	public void theCommittedPopulatedFixtureIsExactlyWhatTheSerializerProduces() throws IOException
	{
		assertArrayEquals(
			"src/test/resources/facette-osrs-state-v2.json no longer matches the serializer",
			TelemetrySnapshotTest.populatedFixture().toJsonBytes(),
			readProjectFile(POPULATED_FIXTURE));
	}

	@Test
	public void theCommittedLoggedOutFixtureIsExactlyWhatTheSerializerProduces() throws IOException
	{
		assertArrayEquals(
			"src/test/resources/facette-osrs-state-v2-logged-out.json no longer matches the"
				+ " serializer",
			TelemetrySnapshotTest.loggedOutFixture().toJsonBytes(),
			readProjectFile(LOGGED_OUT_FIXTURE));
	}

	/**
	 * The processed classpath copy has to be the same bytes too, so a consumer or a reviewer
	 * reading either one is reading the same contract.
	 */
	@Test
	public void bothFixturesAreOnTheTestClasspathUnchanged() throws IOException
	{
		assertArrayEquals(readProjectFile(POPULATED_FIXTURE),
			readResource("/facette-osrs-state-v2.json"));
		assertArrayEquals(readProjectFile(LOGGED_OUT_FIXTURE),
			readResource("/facette-osrs-state-v2-logged-out.json"));
	}

	/**
	 * The committed fixture files are the canonical byte examples, so the documentation links to
	 * them rather than embedding a copy that can drift. This pins both halves of that: the links
	 * resolve to files that exist, and no document re-embeds a fixture.
	 */
	@Test
	public void theDocumentationLinksToBothFixturesInsteadOfEmbeddingThem() throws IOException
	{
		String populated = new String(readProjectFile(POPULATED_FIXTURE), StandardCharsets.UTF_8);
		String loggedOut = new String(readProjectFile(LOGGED_OUT_FIXTURE), StandardCharsets.UTF_8);

		for (String document : new String[]{"README.md", "SCHEMA.md"})
		{
			String text = new String(readProjectFile(document), StandardCharsets.UTF_8);
			assertTrue(document + " must link to the committed populated fixture",
				text.contains(POPULATED_FIXTURE));
			assertTrue(document + " must link to the committed logged-out fixture",
				text.contains(LOGGED_OUT_FIXTURE));
			assertFalse(document + " must not embed the populated fixture; it would drift from the"
					+ " committed file, which is the canonical example",
				text.contains(populated));
			assertFalse(document + " must not embed the logged-out fixture",
				text.contains(loggedOut));
		}
	}

	/**
	 * The README hands the exhaustive contract to SCHEMA.md and the contribution boundaries to
	 * CONTRIBUTING.md, so both links have to resolve to files that are actually committed.
	 */
	@Test
	public void theReadmeLinksResolveToCommittedDocuments() throws IOException
	{
		String readme = new String(readProjectFile("README.md"), StandardCharsets.UTF_8);
		for (String document : new String[]{"SCHEMA.md", "CONTRIBUTING.md", "LICENSE"})
		{
			assertTrue("the README must link to " + document, readme.contains(document));
			assertNotNull(document + " must exist to be linked to", readProjectFile(document));
		}
	}

	/**
	 * The manifest is what the Plugin Hub reads and the descriptor is what the client shows. They
	 * are written in two places, so nothing but a test stops them disagreeing.
	 */
	@Test
	public void theManifestAndThePluginDescriptorAgree() throws IOException
	{
		Properties manifest = new Properties();
		try (InputStream in = new ByteArrayInputStream(readProjectFile(MANIFEST)))
		{
			manifest.load(in);
		}
		PluginDescriptor descriptor =
			FacetteTelemetryPlugin.class.getAnnotation(PluginDescriptor.class);
		assertNotNull("the plugin class must carry a @PluginDescriptor", descriptor);

		assertEquals("displayName and @PluginDescriptor name must be the public identity",
			"Facette Companion", descriptor.name());
		assertEquals("the manifest displayName must match the descriptor name",
			descriptor.name(), manifest.getProperty("displayName"));
		assertEquals("the manifest description must match the descriptor description",
			descriptor.description(), manifest.getProperty("description"));
		assertEquals("the manifest tags must match the descriptor tags, in the same order",
			String.join(",", descriptor.tags()), manifest.getProperty("tags"));

		assertEquals("the hub build mode is what makes the replacement build file apply",
			"standard", manifest.getProperty("build"));
		assertEquals("the manifest must name this plugin class",
			FacetteTelemetryPlugin.class.getName(), manifest.getProperty("plugins"));
	}

	/**
	 * The fixtures are the consumer contract, so they must not contain any of the things schema 2 is
	 * closed against, including a real account name or a filesystem path in a committed file that
	 * people will read.
	 */
	@Test
	public void neitherFixtureContainsIdentityPathOrNetworkContent() throws IOException
	{
		for (String path : new String[]{POPULATED_FIXTURE, LOGGED_OUT_FIXTURE})
		{
			String json = new String(readProjectFile(path), StandardCharsets.UTF_8);
			for (String forbidden : new String[]{"http", "://", "C:\\", "/home/", "/Users/",
				"USERPROFILE", ".runelite", "@", "password", "token", "accountHash"})
			{
				assertFalse(path + " must not contain " + forbidden, json.contains(forbidden));
			}
		}
	}

	@Test
	public void bothFixturesFitInsideTheSizeCeiling() throws IOException
	{
		int populated = readProjectFile(POPULATED_FIXTURE).length;
		int loggedOut = readProjectFile(LOGGED_OUT_FIXTURE).length;
		assertTrue("populated fixture is " + populated + " bytes",
			populated < TelemetrySnapshotWriter.MAX_SNAPSHOT_BYTES);
		assertTrue("logged-out fixture is " + loggedOut + " bytes",
			loggedOut < TelemetrySnapshotWriter.MAX_SNAPSHOT_BYTES);
		assertTrue("a fixture that shrank this far is probably no longer the full shape",
			populated > 2_048);
	}

	@Test
	public void theFixturesCarryNoTrailingNewlineSoTheirBytesAreTheDocumentsBytes()
		throws IOException
	{
		for (String path : new String[]{POPULATED_FIXTURE, LOGGED_OUT_FIXTURE})
		{
			byte[] bytes = readProjectFile(path);
			assertEquals(path + " must end with the closing brace and nothing else",
				'}', (char) bytes[bytes.length - 1]);
		}
	}

	// --- helpers -------------------------------------------------------------------------------

	/**
	 * Reads a file relative to the repository root.
	 *
	 * Resolved by walking up from the working directory rather than assuming one, because the
	 * working directory a test runner chooses is not part of any contract this project controls.
	 */
	private static byte[] readProjectFile(String relativePath) throws IOException
	{
		File candidate = new File(relativePath).getAbsoluteFile();
		File directory = new File("").getAbsoluteFile();
		for (int up = 0; up < 5 && directory != null; up++)
		{
			File attempt = new File(directory, relativePath);
			if (attempt.isFile())
			{
				return Files.readAllBytes(attempt.toPath());
			}
			candidate = attempt;
			directory = directory.getParentFile();
		}
		fail("could not locate " + relativePath + " from " + candidate);
		return null;
	}

	private static byte[] readResource(String resource) throws IOException
	{
		try (InputStream in = TelemetrySchemaFixtureTest.class.getResourceAsStream(resource))
		{
			assertNotNull(resource + " is not on the test classpath", in);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[4_096];
			int read;
			while ((read = in.read(buffer)) >= 0)
			{
				out.write(buffer, 0, read);
			}
			return out.toByteArray();
		}
	}
}
