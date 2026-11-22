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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;

/**
 * Holds the committed schema-5 state fixtures and the serializer to the same contract, measures the
 * state document's worst case against its bound, and pins that turning schema 5 on changes nothing
 * in schemas 3 and 4. The account document's own fixtures are in {@link TelemetryAccountSchemaTest}.
 */
public class TelemetrySchema5Test
{
	static final String SESSION_ID = "6a1f0c2e-9b3d-4e57-8c11-3d2a5e7f9b40";

	static final long SAMPLE_AT = 1_769_999_999_400L;

	private static final String READY_FIXTURE = "src/test/resources/facette-osrs-state-v5.json";
	private static final String PARTIAL_FIXTURE =
		"src/test/resources/facette-osrs-state-v5-partial.json";
	private static final String LOGGED_OUT_FIXTURE =
		"src/test/resources/facette-osrs-state-v5-logged-out.json";

	private static final String[] ALL_FIXTURES = {READY_FIXTURE, PARTIAL_FIXTURE, LOGGED_OUT_FIXTURE};

	// --- the canonical documents ---------------------------------------------------------------

	static List<TelemetryGroupObservation> allGroups(TelemetryGroupObservation observation)
	{
		return new ArrayList<>(Collections.nCopies(TelemetrySnapshot.SCHEMA_5_GROUPS.size(), observation));
	}

	static TelemetrySnapshot readyFixture()
	{
		return TelemetrySnapshotTest.populatedFixtureBuilder()
			.slayer(12, TelemetrySnapshot.SLAYER_BOSS_TASK_ID, 23, null)
			.schema5(SESSION_ID, TelemetrySnapshot.READY, Collections.emptyList(),
				allGroups(TelemetryGroupObservation.current(SAMPLE_AT)), 3L)
			.build();
	}

	/**
	 * The live login reproduction as schema 5 carries it: a full backpack, nothing worn, no
	 * experience gained, no target and no prayers, and a client that has not provided the worn-items
	 * container. The character is recognized; the worn items are not observed rather than empty.
	 */
	static TelemetrySnapshot partialFixture()
	{
		List<TelemetryItemSlot> inventory = new ArrayList<>();
		for (int i = 0; i < 27; i++)
		{
			inventory.add(TelemetryItemSlot.of(3101 + i, 1, "Sample item " + i));
		}
		inventory.add(TelemetryItemSlot.of(995, 10_000, "Sample coin pile"));
		List<TelemetryGroupObservation> groups = allGroups(TelemetryGroupObservation.current(SAMPLE_AT));
		groups.set(TelemetrySnapshot.SCHEMA_5_GROUPS.indexOf("equipment"),
			TelemetryGroupObservation.NONE_NOT_OBSERVED);
		return TelemetrySnapshotTest.populatedFixtureBuilder()
			.envelope(TelemetrySnapshotTest.INSTANCE_ID, 3L, TelemetrySnapshotTest.EMITTED_AT)
			.session(true, "LOGGED_IN", true, 302, 87, 1_769_999_998_800L)
			.combat("accurate", Collections.emptyList(), null)
			.equipment(null)
			.inventory(28, 0, inventory)
			.xp(null, null, null, Collections.emptyList())
			.slayer(0, null, null, null)
			.schema5(SESSION_ID, TelemetrySnapshot.PARTIAL, Collections.singletonList("equipment"),
				groups, null)
			.build();
	}

	static TelemetrySnapshot loggedOutFixture()
	{
		return TelemetrySnapshot.builder()
			.envelope(TelemetrySnapshotTest.INSTANCE_ID, 42L, TelemetrySnapshotTest.EMITTED_AT)
			.session(true, "LOGIN_SCREEN", false, null, null, null)
			.vitals(null, null, null, null, null, null, null)
			.combat(null, null, null)
			.equipment(null)
			.inventory(null, null, null)
			.xp(null, null, null, null)
			.skills(null)
			.account(null)
			.quests(null)
			.slayer(null, null)
			.grandExchange(null)
			.schema5(null, TelemetrySnapshot.LOGGED_OUT, Collections.emptyList(),
				allGroups(TelemetryGroupObservation.NONE_UNAVAILABLE), null)
			.build();
	}

	@Test
	public void theCommittedStateFixturesAreExactlyWhatTheSerializerProduces() throws IOException
	{
		assertArrayEquals(READY_FIXTURE + " no longer matches the serializer",
			readyFixture().toJsonBytes(TelemetrySnapshot.SCHEMA_5), readProjectFile(READY_FIXTURE));
		assertArrayEquals(PARTIAL_FIXTURE + " no longer matches the serializer",
			partialFixture().toJsonBytes(TelemetrySnapshot.SCHEMA_5), readProjectFile(PARTIAL_FIXTURE));
		assertArrayEquals(LOGGED_OUT_FIXTURE + " no longer matches the serializer",
			loggedOutFixture().toJsonBytes(TelemetrySnapshot.SCHEMA_5),
			readProjectFile(LOGGED_OUT_FIXTURE));
	}

	/** Turning schema 5 on must not move a byte of the legacy documents. */
	@Test
	public void theSchemaFiveViewLeavesSchemasThreeAndFourByteIdentical()
	{
		for (boolean emptyWorn : new boolean[]{false, true})
		{
			TelemetryState legacy = TelemetryStateSchema5Test.loggedInState(false);
			TelemetryState withFive = TelemetryStateSchema5Test.loggedInState(true);
			for (TelemetryState state : new TelemetryState[]{legacy, withFive})
			{
				TelemetryStateSchema5Test.sample(state, emptyWorn ? null
					: TelemetryStateSchema5Test.emptyEquipment(),
					TelemetryStateSchema5Test.emptyInventory());
			}
			TelemetrySnapshot a = legacy.nextSnapshot(true);
			TelemetrySnapshot b = withFive.nextSnapshot(true);
			assertEquals(a.toJson(TelemetrySnapshot.SCHEMA), b.toJson(TelemetrySnapshot.SCHEMA));
			assertEquals(a.toJson(TelemetrySnapshot.SCHEMA_4), b.toJson(TelemetrySnapshot.SCHEMA_4));
			assertEquals("only the schema-5 state carries a view", null, a.getSchema5View());
			assertTrue(b.getSchema5View() != null);
		}
	}

	@Test
	public void aSnapshotWithoutSchemaFiveFieldsRefusesToSerializeAsSchemaFive()
	{
		try
		{
			TelemetrySnapshotTest.populatedFixture().toJson(TelemetrySnapshot.SCHEMA_5);
			fail("a gated snapshot is not a schema-5 document");
		}
		catch (IllegalStateException expected)
		{
			// The schema-5 view is a separate snapshot.
		}
	}

	@Test
	public void readinessNamesOnlyTheClosedListInItsOrder()
	{
		for (List<String> bad : Arrays.asList(
			Arrays.asList("equipment", "vitals"),
			Collections.singletonList("bank"),
			Arrays.asList("inventory", "inventory")))
		{
			try
			{
				TelemetrySnapshotTest.populatedFixtureBuilder()
					.schema5(SESSION_ID, TelemetrySnapshot.PARTIAL, bad,
						allGroups(TelemetryGroupObservation.current(SAMPLE_AT)), null)
					.build();
				fail("readiness must refuse " + bad);
			}
			catch (IllegalArgumentException expected)
			{
				// A diagnostic can only ever name a fixed group.
			}
		}
	}

	@Test
	public void anObservationIsNeverLaterThanTheDocument()
	{
		TelemetrySnapshot snapshot = TelemetrySnapshotTest.populatedFixtureBuilder()
			.schema5(SESSION_ID, TelemetrySnapshot.READY, Collections.emptyList(),
				allGroups(TelemetryGroupObservation.current(TelemetrySnapshotTest.EMITTED_AT + 5_000L)),
				null)
			.build();
		String json = snapshot.toJson(TelemetrySnapshot.SCHEMA_5);
		assertFalse(json.contains(Long.toString(TelemetrySnapshotTest.EMITTED_AT + 5_000L)));
		assertTrue(json.contains("\"observedAt\":" + TelemetrySnapshotTest.EMITTED_AT));
	}

	/** The widest schema-5 state document, against its own bound, with the same 2 KiB headroom. */
	@Test
	public void theLargestReachableStateDocumentFitsItsBound()
	{
		TelemetrySnapshot widest = TelemetrySnapshotTest.widestBuilder()
			.slayer(Integer.MAX_VALUE, TelemetrySnapshot.SLAYER_BOSS_TASK_ID, Integer.MAX_VALUE,
				Integer.MAX_VALUE)
			.schema5(TelemetrySnapshotTest.repeat('C', TelemetrySnapshot.MAX_INSTANCE_ID_CHARS),
				TelemetrySnapshot.LOADING, TelemetrySnapshot.READINESS_ITEMS,
				allGroups(TelemetryGroupObservation.observed(9_999_999_999_999L)), Long.MAX_VALUE)
			.build();
		int size = widest.toJsonBytes(TelemetrySnapshot.SCHEMA_5).length;
		assertTrue("schema-5 worst case " + size + " bytes leaves less than 2 KiB of headroom under "
				+ ManagedExport.MAX_STATE_BYTES,
			ManagedExport.MAX_STATE_BYTES - size >= 2_048);
	}

	@Test
	public void theFixturesCarryNoIdentityPathOrNetworkContentAndNoTrailingNewline()
		throws IOException
	{
		for (String path : ALL_FIXTURES)
		{
			assertNoIdentityPathOrNetworkContent(path);
		}
	}

	static void assertNoIdentityPathOrNetworkContent(String path) throws IOException
	{
		byte[] bytes = readProjectFile(path);
		String json = new String(bytes, StandardCharsets.UTF_8);
		for (String forbidden : new String[]{"http", "://", "C:\\", "/home/", "/Users/",
			"USERPROFILE", ".runelite", "@", "password", "token", "accountHash", "profileKey",
			"username", "displayName"})
		{
			assertFalse(path + " must not contain " + forbidden, json.contains(forbidden));
		}
		assertEquals(path + " must end with the closing brace", '}', (char) bytes[bytes.length - 1]);
	}

	/**
	 * Only the ready fixture references an account document, and exactly revision 3, the committed
	 * account fixture. A character whose worn items are missing has no account revision yet, and a
	 * logged-out snapshot never references one.
	 */
	@Test
	public void onlyTheReadyFixtureReferencesAnAccountDocument() throws IOException
	{
		assertTrue(new String(readProjectFile(READY_FIXTURE), StandardCharsets.UTF_8)
			.endsWith(",\"accountData\":{\"file\":\"account-v5.json\",\"revision\":3}}"));
		for (String path : new String[]{PARTIAL_FIXTURE, LOGGED_OUT_FIXTURE})
		{
			String json = new String(readProjectFile(path), StandardCharsets.UTF_8);
			assertTrue(path + " must carry no account reference", json.endsWith(",\"accountData\":null}"));
		}
	}

	@Test
	public void theSchemaDocumentLinksToEverySchemaFiveFixture() throws IOException
	{
		String text = new String(readProjectFile("SCHEMA.md"), StandardCharsets.UTF_8);
		for (String fixture : ALL_FIXTURES)
		{
			assertTrue("SCHEMA.md must link to " + fixture, text.contains(fixture));
		}
	}

	static byte[] readProjectFile(String relativePath) throws IOException
	{
		File directory = new File("").getAbsoluteFile();
		for (int up = 0; up < 5 && directory != null; up++)
		{
			File attempt = new File(directory, relativePath);
			if (attempt.isFile())
			{
				return Files.readAllBytes(attempt.toPath());
			}
			directory = directory.getParentFile();
		}
		fail("could not locate " + relativePath);
		return null;
	}
}
