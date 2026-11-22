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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The schema-5 account document, {@code account-v5.json}: its committed fixtures are exactly what
 * the account state produces, its worst case fits its bound, the fixtures carry no identity, path or
 * network content, the state file references exactly the revision it belongs to, and
 * {@code SCHEMA.md} links to both fixtures.
 */
public class TelemetryAccountSchemaTest
{
	private static final String ACCOUNT_FIXTURE = "src/test/resources/facette-osrs-account-v5.json";
	private static final String ACCOUNT_CLEARED_FIXTURE =
		"src/test/resources/facette-osrs-account-v5-cleared.json";
	private static final String SPECIFICATION = "SCHEMA.md";

	private static final String[] ACCOUNT_FIXTURES = {ACCOUNT_FIXTURE, ACCOUNT_CLEARED_FIXTURE};

	/** Revision 3 of the ready fixture's character session. */
	static TelemetryAccountState accountFixture(long[] wallClock)
	{
		String session = TelemetrySchema5Test.SESSION_ID;
		TelemetryAccountState account = new TelemetryAccountState(TelemetrySnapshotTest.INSTANCE_ID,
			4, () -> wallClock[0], () -> 0L);
		account.beginSession(session);
		account.observeBank(session, Arrays.asList(
			new TelemetryAccountState.StoredItem(995, 1_250_000, TelemetryAccountState.KIND_ITEM),
			new TelemetryAccountState.StoredItem(4151, 1, TelemetryAccountState.KIND_ITEM),
			new TelemetryAccountState.StoredItem(14402, 0, TelemetryAccountState.KIND_PLACEHOLDER),
			new TelemetryAccountState.StoredItem(20594, 1, TelemetryAccountState.KIND_FILLER),
			new TelemetryAccountState.StoredItem(0, 3, TelemetryAccountState.KIND_ITEM)),
			1_769_999_950_000L);
		account.observeSeedVault(session, Collections.emptyList(), 1_769_999_960_000L);
		long questsAt = 1_769_999_990_000L;
		account.observeQuest(session, 0, new TelemetryAccountState.QuestEntry(
			0, "Animal Magnetism", TelemetryAccountState.QUEST_FINISHED), questsAt);
		account.observeQuest(session, 1, new TelemetryAccountState.QuestEntry(
			1, "Another Slice of H.A.M.", TelemetryAccountState.QUEST_IN_PROGRESS), questsAt);
		account.observeQuest(session, 2, new TelemetryAccountState.QuestEntry(
			3, "The Ascent of Arceuus", TelemetryAccountState.QUEST_NOT_STARTED), questsAt);
		account.observeQuest(session, 3, new TelemetryAccountState.QuestEntry(
			4, "Alfred Grimhand's Barcrawl", TelemetryAccountState.QUEST_UNAVAILABLE), questsAt);
		boolean[][] diaries = new boolean[12][4];
		for (int i = 0; i < 12; i++)
		{
			diaries[i][0] = i != 9;
			diaries[i][1] = i % 2 == 0;
			diaries[i][2] = i == 0 || i == 5;
			diaries[i][3] = false;
		}
		account.observeDiaries(session, diaries, questsAt);
		account.observeCombatAchievements(session,
			new boolean[]{true, true, false, false, false, false}, questsAt);
		return account;
	}

	@Test
	public void theCommittedAccountFixturesAreExactlyWhatTheAccountStateProduces() throws IOException
	{
		long[] wallClock = {1_769_999_999_500L};
		TelemetryAccountState account = accountFixture(wallClock);
		assertArrayEquals(ACCOUNT_FIXTURE + " no longer matches the account document",
			account.toJson(3L).getBytes(StandardCharsets.UTF_8),
			TelemetrySchema5Test.readProjectFile(ACCOUNT_FIXTURE));

		account.endSession();
		wallClock[0] = 1_770_000_005_000L;
		assertArrayEquals(ACCOUNT_CLEARED_FIXTURE + " no longer matches the clearing document",
			account.toJson(4L).getBytes(StandardCharsets.UTF_8),
			TelemetrySchema5Test.readProjectFile(ACCOUNT_CLEARED_FIXTURE));
	}

	/** The ready fixture's snapshot, had revision 3 of its account document been committed. */
	@Test
	public void aCommittedRevisionIsTheStateFilesExactReference()
	{
		TelemetrySnapshot ready = TelemetrySnapshotTest.populatedFixtureBuilder()
			.slayer(12, TelemetrySnapshot.SLAYER_BOSS_TASK_ID, 23, null)
			.schema5(TelemetrySchema5Test.SESSION_ID, TelemetrySnapshot.READY, Collections.emptyList(),
				TelemetrySchema5Test.allGroups(
					TelemetryGroupObservation.current(TelemetrySchema5Test.SAMPLE_AT)), 3L)
			.build();
		assertTrue(ready.toJson(TelemetrySnapshot.SCHEMA_5).endsWith(
			",\"accountData\":{\"file\":\"account-v5.json\",\"revision\":3}}"));
	}

	@Test
	public void anAccountRevisionIsReferencedOnlyByTheSessionItDescribes()
	{
		TelemetryState state = TelemetryStateSchema5Test.loggedInState(true);
		TelemetryStateSchema5Test.sample(state, TelemetryStateSchema5Test.emptyEquipment(),
			TelemetryStateSchema5Test.emptyInventory());
		String session = TelemetryStateSchema5Test.view(state).getCharacterSessionId();

		state.recordAccountCommitted("another-session", 1L);
		assertNull(TelemetryStateSchema5Test.view(state).getAccountRevision());
		state.recordAccountCommitted(session, 2L);
		assertEquals(Long.valueOf(2L), TelemetryStateSchema5Test.view(state).getAccountRevision());
		assertTrue(TelemetryStateSchema5Test.json(state).endsWith(
			"\"accountData\":{\"file\":\"account-v5.json\",\"revision\":2}}"));

		state.updateSession("LOGIN_SCREEN", false, true);
		TelemetryStateSchema5Test.sample(state, TelemetryStateSchema5Test.emptyEquipment(),
			TelemetryStateSchema5Test.emptyInventory());
		assertNull("a new session starts with no account document",
			TelemetryStateSchema5Test.view(state).getAccountRevision());
	}

	/**
	 * The widest account document: every container at its bound with the widest numbers and the
	 * longest kind, and every quest at the longest name. Measured, so the bound is demonstrated.
	 */
	@Test
	public void theLargestReachableAccountDocumentFitsItsBound()
	{
		TelemetryAccountState account = new TelemetryAccountState(
			TelemetrySnapshotTest.repeat('I', TelemetrySnapshot.MAX_INSTANCE_ID_CHARS),
			TelemetryAccountState.MAX_QUESTS, () -> 9_999_999_999_999L, () -> 0L);
		String session = TelemetrySnapshotTest.repeat('C', TelemetrySnapshot.MAX_INSTANCE_ID_CHARS);
		account.beginSession(session);
		List<TelemetryAccountState.StoredItem> bank = new ArrayList<>();
		for (int i = 0; i < TelemetryAccountState.MAX_BANK_ITEMS; i++)
		{
			bank.add(new TelemetryAccountState.StoredItem(Integer.MAX_VALUE, Integer.MAX_VALUE,
				TelemetryAccountState.KIND_PLACEHOLDER));
		}
		List<TelemetryAccountState.StoredItem> vault = new ArrayList<>(
			bank.subList(0, TelemetryAccountState.MAX_SEED_VAULT_ITEMS));
		assertTrue(account.observeBank(session, bank, 9_999_999_999_999L));
		assertTrue(account.observeSeedVault(session, vault, 9_999_999_999_999L));
		for (int i = 0; i < TelemetryAccountState.MAX_QUESTS; i++)
		{
			account.observeQuest(session, i, new TelemetryAccountState.QuestEntry(Integer.MIN_VALUE,
				TelemetrySnapshotTest.repeat('"', TelemetryAccountState.MAX_QUEST_NAME_CHARS),
				TelemetryAccountState.QUEST_IN_PROGRESS), 9_999_999_999_999L);
		}
		boolean[][] diaries = new boolean[12][4];
		for (boolean[] region : diaries)
		{
			Arrays.fill(region, false);
		}
		account.observeDiaries(session, diaries, 9_999_999_999_999L);
		account.observeCombatAchievements(session, new boolean[6], 9_999_999_999_999L);

		int size = account.toJson(Long.MAX_VALUE).getBytes(StandardCharsets.UTF_8).length;
		assertTrue("account worst case " + size + " bytes exceeds "
				+ TelemetryAccountState.MAX_DOCUMENT_BYTES,
			size <= TelemetryAccountState.MAX_DOCUMENT_BYTES);
	}

	@Test
	public void theAccountFixturesCarryNoIdentityPathOrNetworkContentAndNoTrailingNewline()
		throws IOException
	{
		for (String path : ACCOUNT_FIXTURES)
		{
			TelemetrySchema5Test.assertNoIdentityPathOrNetworkContent(path);
		}
	}

	@Test
	public void theSpecificationLinksToEveryAccountFixture() throws IOException
	{
		String text = new String(TelemetrySchema5Test.readProjectFile(SPECIFICATION),
			StandardCharsets.UTF_8);
		for (String fixture : ACCOUNT_FIXTURES)
		{
			assertTrue(SPECIFICATION + " must link to " + fixture, text.contains(fixture));
		}
	}
}
