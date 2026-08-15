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
 * Holds the committed schema-2 fixtures, the serializer, and the publication-facing copy to the
 * same contract.
 *
 * <p>The two fixtures are the cross-repository consumer contract: the Facette adapter commits the
 * same bytes, and the two are only allowed to agree. If the serializer changes shape without the
 * fixtures changing with it, the contract has silently forked and this is what says so.
 *
 * <p>The fixtures are read from the committed files rather than only from the processed
 * classpath copy, so the bytes checked here are exactly the bytes in the repository. Each file
 * holds the document and nothing else — no trailing newline — so a fixture's bytes and an
 * exported snapshot's bytes are comparable directly.
 *
 * <p>Needs no account, credential, network service, Facette installation, or game session.
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
	 * The distribution and affiliation copy has to stay true at every point in the Plugin Hub
	 * lifecycle: before a submission exists, while one is under review, and after it is accepted.
	 *
	 * <p>The earlier wording could not. It pinned the pre-review state itself — "not currently
	 * distributed through the RuneLite Plugin Hub", "no submission has been reviewed", "Nothing
	 * here claims Plugin Hub approval" — so acceptance would have turned the README false without
	 * anything changing in the repository, and the stale copy would have been carried into the
	 * public projection. What is pinned now is the part that does not expire: the intended
	 * installation route, the absence of prebuilt binaries here, and the independence disclaimer.
	 *
	 * <p>The removed phrases stay in this file as negative assertions. Documenting a state that
	 * expires is the failure mode, so it is cheaper to keep the exact strings barred than to
	 * rediscover why they were wrong the next time someone reaches for them.
	 */
	@Test
	public void theReadmeDocumentsTheVersionedTargetAndTheEvergreenDistributionContract()
		throws IOException
	{
		String readme = readmeProse();
		assertTrue("the README must name the schema-2 target file",
			readme.contains(TelemetrySnapshotWriter.TARGET_FILE_NAME));
		assertTrue("the README must still disclaim affiliation, naming Jagex and the RuneLite"
				+ " project as the independent parties they are",
			readme.contains(
				"not affiliated with or endorsed by Jagex Ltd. or the RuneLite project"));
		assertTrue("the README must name the Plugin Hub as the intended installation route,"
				+ " without claiming the submission has been accepted",
			readme.contains(
				"Facette Companion is intended to be installed through the RuneLite Plugin"
					+ " Hub."));
		assertTrue("the README must say this repository ships no prebuilt binary, which is what"
				+ " keeps the contributor build from reading as an installation path",
			readme.contains("This repository does not distribute prebuilt JAR files."));

		assertFalse("the self-expiring no-approval-claimed line must not come back; it becomes"
				+ " false the moment the Plugin Hub accepts the plugin",
			readme.contains("Nothing here claims Plugin Hub approval"));
		assertFalse("the self-expiring approval and endorsement disclaimer must not come back",
			readme.contains(
				"nothing here is approved or endorsed by Jagex, RuneLite, or the RuneLite"
					+ " Plugin Hub"));
		assertFalse("the pre-submission distribution state must not come back",
			readme.contains(
				"This plugin is not currently distributed through the RuneLite Plugin Hub"));
		assertFalse("the pre-review submission state must not come back",
			readme.contains("no submission has been reviewed"));
	}

	/**
	 * The publication-facing copy is part of the submission, so what a Plugin Hub reviewer and an
	 * ordinary reader depend on is pinned here rather than left to whoever edits the README next.
	 */
	@Test
	public void theReadmeDoesNotSendOrdinaryUsersToBuildFromSource() throws IOException
	{
		String readme = readmeProse();
		assertFalse("the README must not tell ordinary users that building from source is the"
				+ " installation path",
			readme.contains("The only way to run it today is to build it"));
		assertFalse("the stale source-only publication state must not come back",
			readme.contains("Technical alpha"));
		assertTrue("build instructions must be labelled as a contributor workflow",
			readme.contains("Contributor workflow"));
		assertTrue("and every Gradle instruction must sit under that heading",
			readme.indexOf("./gradlew") > readme.indexOf("Contributor workflow"));
	}

	@Test
	public void theReadmeKeepsTheSeparatelyInstalledFacetteRelationshipExplicit() throws IOException
	{
		String readme = readmeProse();
		assertTrue("the README must say Facette is installed separately by the user",
			readme.contains("install separately"));
		assertTrue("the README must say the plugin does not launch or communicate with Facette",
			readme.contains(
				"does not launch, download, install, bundle, execute, or communicate with"
					+ " Facette"));
		assertTrue("the README must still state that the plugin makes no network request",
			readme.contains("No network communication"));
		assertTrue("the README must still state that the plugin performs no gameplay action",
			readme.contains("no clicks, no keystrokes, no menu actions, no automation"));
	}

	/**
	 * The README with every run of whitespace collapsed to one space, so these assertions pin what
	 * the document says rather than where its lines happen to wrap.
	 */
	private static String readmeProse() throws IOException
	{
		return new String(readProjectFile("README.md"), StandardCharsets.UTF_8)
			.replaceAll("\\s+", " ");
	}

	/**
	 * The fixtures are the consumer contract, so they must not contain any of the things schema 2
	 * is closed against — including, in a committed file that will be read by people, a real
	 * account name or a filesystem path.
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
	 * <p>Resolved by walking up from the working directory rather than assuming one, because the
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
