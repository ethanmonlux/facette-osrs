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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

/**
 * The account document's observation rules: unopened is unknown, delivered-and-empty is empty, a
 * closed interface keeps its original observation time, nothing outlives its character session,
 * and a document is written only after a change and no more often than its interval.
 */
public class TelemetryAccountStateTest
{
	private static final String SESSION = "session-1";
	private static final long INTERVAL_MILLIS = 2_000L;

	private long now;
	private long elapsedNanos;
	private TelemetryAccountState account;

	@Before
	public void setUp()
	{
		now = 1_770_000_000_000L;
		elapsedNanos = 0L;
		account = new TelemetryAccountState("instance-1", 3, () -> now, () -> elapsedNanos);
		account.beginSession(SESSION);
	}

	private static TelemetryAccountState.StoredItem item(int id, int quantity)
	{
		return new TelemetryAccountState.StoredItem(id, quantity, TelemetryAccountState.KIND_ITEM);
	}

	private String json()
	{
		return account.toJson(account.getCommittedRevision() + 1);
	}

	private void commit()
	{
		TelemetryAccountState.Document document = account.prepareDocument(INTERVAL_MILLIS);
		assertNotNull("a document was due", document);
		account.markCommitted(document);
	}

	@Test
	public void anUnopenedBankIsNotObservedRatherThanEmpty()
	{
		assertTrue(json().contains(
			"\"bank\":{\"availability\":\"not_observed\",\"observedAt\":null,\"items\":null}"));
		assertNull("nothing observed, so nothing to write", account.prepareDocument(INTERVAL_MILLIS));
	}

	@Test
	public void aDeliveredEmptyBankIsAConfirmedEmptyBank()
	{
		account.observeBank(SESSION, Collections.emptyList(), now);
		assertTrue(json().contains(
			"\"bank\":{\"availability\":\"observed\",\"observedAt\":" + now + ",\"items\":[]}"));
	}

	@Test
	public void placeholdersAndFillersKeepTheirRawIdentifiersAndAreLabelled()
	{
		account.observeBank(SESSION, Arrays.asList(
			item(995, 10),
			new TelemetryAccountState.StoredItem(14402, 0, TelemetryAccountState.KIND_PLACEHOLDER),
			new TelemetryAccountState.StoredItem(20594, 1, TelemetryAccountState.KIND_FILLER)), now);
		assertTrue(json().contains("[{\"itemId\":995,\"quantity\":10,\"kind\":\"item\"},"
			+ "{\"itemId\":14402,\"quantity\":0,\"kind\":\"placeholder\"},"
			+ "{\"itemId\":20594,\"quantity\":1,\"kind\":\"filler\"}]"));
	}

	@Test
	public void aContainerOverItsBoundIsUnavailableNeverTruncated()
	{
		List<TelemetryAccountState.StoredItem> tooMany = new ArrayList<>(Collections.nCopies(
			TelemetryAccountState.MAX_SEED_VAULT_ITEMS + 1, item(5318, 1)));
		account.observeSeedVault(SESSION, tooMany, now);
		assertTrue(json().contains(
			"\"seedVault\":{\"availability\":\"unavailable\",\"observedAt\":null,\"items\":null}"));
		account.observeSeedVault(SESSION, null, now);
		assertTrue(json().contains("\"seedVault\":{\"availability\":\"unavailable\""));
	}

	@Test
	public void closingKeepsTheOriginalTimeAndReopeningRecordsANewObservation()
	{
		long opened = now;
		account.observeBank(SESSION, Collections.singletonList(item(995, 10)), opened);
		commit();
		now += 60_000L;
		elapsedNanos += 60_000_000_000L;
		assertNull("a closed bank produces no new document", account.prepareDocument(INTERVAL_MILLIS));
		assertTrue(json().contains("\"observedAt\":" + opened));

		account.observeBank(SESSION, Collections.singletonList(item(995, 10)), now);
		assertTrue(json().contains("\"bank\":{\"availability\":\"observed\",\"observedAt\":" + now));
	}

	@Test
	public void anObservationForAnotherOrEndedSessionIsRefused()
	{
		assertFalse(account.observeBank("session-2", Collections.emptyList(), now));
		account.endSession();
		assertFalse(account.observeBank(SESSION, Collections.emptyList(), now));
	}

	/** A game tick racing the shutdown cannot begin a session again or refill the final document. */
	@Test
	public void aClosedAccountStateNeverBeginsASessionAgain()
	{
		account.observeBank(SESSION, Collections.singletonList(item(995, 1)), now);
		commit();
		account.close();
		account.beginSession("session-2");
		assertNull(account.getCharacterSessionId());
		assertFalse(account.observeBank("session-2", Collections.singletonList(item(995, 1)), now));
		TelemetryAccountState.Document last = account.prepareDocument(INTERVAL_MILLIS);
		assertNotNull("the clearing document is due", last);
		assertNull(last.getCharacterSessionId());
		assertFalse(new String(last.getBytes(), java.nio.charset.StandardCharsets.UTF_8).contains("995"));
	}

	/** A run's first document goes out at once, before anything is observed, to replace a leftover. */
	@Test
	public void replacingThePreviousDocumentQueuesOneAtOnceWithNothingObserved()
	{
		account.replacePreviousDocument();
		TelemetryAccountState.Document first = account.prepareDocument(INTERVAL_MILLIS);
		assertNotNull(first);
		assertEquals(1L, first.getRevision());
		String json = new String(first.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
		assertTrue(json.contains("\"bank\":{\"availability\":\"not_observed\""));
		account.markCommitted(first);
		assertNull("nothing further is due", account.prepareDocument(INTERVAL_MILLIS));
	}

	@Test
	public void endingASessionThatReachedTheFileQueuesAClearingDocumentAtOnce()
	{
		account.observeBank(SESSION, Collections.singletonList(item(995, 10)), now);
		commit();
		assertEquals(1L, account.getCommittedRevision());

		account.endSession();
		// No time has passed: a clearing document does not wait for the interval.
		TelemetryAccountState.Document clearing = account.prepareDocument(INTERVAL_MILLIS);
		assertNotNull(clearing);
		assertNull(clearing.getCharacterSessionId());
		String json = new String(clearing.getBytes(), StandardCharsets.UTF_8);
		assertTrue(json.contains("\"characterSessionId\":null,\"revision\":2"));
		assertFalse("no previous observation survives", json.contains("995"));
		account.markCommitted(clearing);
		assertNull(account.prepareDocument(INTERVAL_MILLIS));
	}

	@Test
	public void aFirstDocumentThatLandsAfterLogoutQueuesAClearingDocumentAtOnce()
	{
		account.observeBank(SESSION, Collections.singletonList(item(995, 10)), now);
		TelemetryAccountState.Document first = account.prepareDocument(INTERVAL_MILLIS);
		assertNotNull(first);
		// Logout while the session's first document is being written: nothing has committed yet.
		account.endSession();
		account.markCommitted(first);

		// The file now holds the ended session's bank, so a clearing document is due without waiting.
		TelemetryAccountState.Document clearing = account.prepareDocument(INTERVAL_MILLIS);
		assertNotNull("the stale first document is replaced", clearing);
		assertNull(clearing.getCharacterSessionId());
		assertEquals(2L, clearing.getRevision());
		String json = new String(clearing.getBytes(), StandardCharsets.UTF_8);
		assertTrue(json.contains("\"characterSessionId\":null,\"revision\":2"));
		assertFalse("no previous observation survives", json.contains("995"));
		account.markCommitted(clearing);
		assertNull(account.prepareDocument(INTERVAL_MILLIS));
	}

	@Test
	public void aDocumentThatLandsAfterAProfileSwitchIsReplacedByTheNewSession()
	{
		account.observeBank(SESSION, Collections.singletonList(item(995, 10)), now);
		TelemetryAccountState.Document first = account.prepareDocument(INTERVAL_MILLIS);
		// A profile change ends the session and begins another during the write.
		account.beginSession("session-2");
		account.markCommitted(first);

		TelemetryAccountState.Document replacement = account.prepareDocument(INTERVAL_MILLIS);
		assertNotNull("the old character is replaced at once", replacement);
		assertEquals("session-2", replacement.getCharacterSessionId());
		assertFalse(new String(replacement.getBytes(), StandardCharsets.UTF_8).contains("995"));
		account.markCommitted(replacement);
		assertNull(account.prepareDocument(INTERVAL_MILLIS));
	}

	@Test
	public void aLaterDocumentThatLandsAfterLogoutIsStillCleared()
	{
		account.observeBank(SESSION, Collections.singletonList(item(995, 10)), now);
		commit();
		account.observeBank(SESSION, Collections.singletonList(item(995, 11)), now);
		elapsedNanos += 2_000_000_000L;
		TelemetryAccountState.Document second = account.prepareDocument(INTERVAL_MILLIS);
		account.endSession();
		account.markCommitted(second);

		TelemetryAccountState.Document clearing = account.prepareDocument(INTERVAL_MILLIS);
		assertNotNull(clearing);
		assertNull(clearing.getCharacterSessionId());
		assertEquals(3L, clearing.getRevision());
		account.markCommitted(clearing);
		assertNull(account.prepareDocument(INTERVAL_MILLIS));
	}

	@Test
	public void endingASessionThatNeverReachedTheFileWritesNothing()
	{
		account.observeBank(SESSION, Collections.singletonList(item(995, 10)), now);
		account.endSession();
		assertNull(account.prepareDocument(INTERVAL_MILLIS));
	}

	@Test
	public void documentsAreRateLimitedAndARevisionIsConsumedOnlyByACommit()
	{
		account.observeBank(SESSION, Collections.singletonList(item(995, 10)), now);
		TelemetryAccountState.Document first = account.prepareDocument(INTERVAL_MILLIS);
		assertEquals(1L, first.getRevision());
		// The write failed: nothing committed. The retry waits for the interval and reuses the number.
		assertNull(account.prepareDocument(INTERVAL_MILLIS));
		elapsedNanos += 2_000_000_000L;
		TelemetryAccountState.Document retry = account.prepareDocument(INTERVAL_MILLIS);
		assertEquals(1L, retry.getRevision());
		account.markCommitted(retry);

		account.observeBank(SESSION, Collections.singletonList(item(995, 11)), now);
		assertNull("a change inside the interval waits", account.prepareDocument(INTERVAL_MILLIS));
		elapsedNanos += 2_000_000_000L;
		assertEquals(2L, account.prepareDocument(INTERVAL_MILLIS).getRevision());
	}

	@Test
	public void aChangeArrivingWhileADocumentIsBeingWrittenStaysPending()
	{
		account.observeBank(SESSION, Collections.singletonList(item(995, 10)), now);
		TelemetryAccountState.Document document = account.prepareDocument(INTERVAL_MILLIS);
		account.observeSeedVault(SESSION, Collections.emptyList(), now);
		account.markCommitted(document);
		elapsedNanos += 2_000_000_000L;
		assertNotNull(account.prepareDocument(INTERVAL_MILLIS));
	}

	@Test
	public void questsArePartialUntilAWholePassAndAnUnreadableQuestIsNeverNotStarted()
	{
		account.observeQuest(SESSION, 0, new TelemetryAccountState.QuestEntry(0, "First",
			TelemetryAccountState.QUEST_FINISHED), now);
		assertTrue(json().contains("\"questStates\":{\"availability\":\"partial\""));
		assertFalse("an unread quest is absent, not not-started", json().contains("not_started"));

		account.observeQuest(SESSION, 1, new TelemetryAccountState.QuestEntry(1, "Second",
			TelemetryAccountState.QUEST_UNAVAILABLE), now + 600L);
		account.observeQuest(SESSION, 2, new TelemetryAccountState.QuestEntry(2, "Third",
			TelemetryAccountState.QUEST_IN_PROGRESS), now + 1_200L);
		String json = json();
		assertTrue(json.contains("\"questStates\":{\"availability\":\"current\",\"observedAt\":"
			+ (now + 1_200L) + ",\"catalogueSize\":3"));
		assertTrue(json.contains("{\"questId\":1,\"name\":\"Second\",\"state\":\"unavailable\"}"));
	}

	@Test
	public void questBatchesWalkTheCatalogueRoundRobin()
	{
		assertEquals(0, account.claimQuestBatch(2));
		assertEquals(2, account.claimQuestBatch(2));
		assertEquals(1, account.claimQuestBatch(2));
		account.endSession();
		assertEquals("no session, nothing to read", -1, account.claimQuestBatch(2));
	}

	@Test
	public void progressionKeepsItsFirstObservationTimeUntilAValueChanges()
	{
		boolean[][] diaries = new boolean[12][4];
		account.observeDiaries(SESSION, diaries, now);
		account.observeDiaries(SESSION, diaries, now + 6_000L);
		assertTrue(json().contains("\"diaries\":{\"availability\":\"current\",\"observedAt\":" + now));
		diaries[5][0] = true;
		account.observeDiaries(SESSION, diaries, now + 12_000L);
		String json = json();
		assertTrue(json.contains("\"diaries\":{\"availability\":\"current\",\"observedAt\":" + (now + 12_000L)));
		assertTrue(json.contains("{\"region\":\"karamja\",\"easy\":true,\"medium\":false"));

		account.observeCombatAchievements(SESSION, new boolean[]{true, false, false, false, false, false},
			now);
		assertTrue(json().contains("\"tiers\":[{\"tier\":\"easy\",\"completed\":true}"));
		assertTrue("points have no documented source and are never derived",
			json().endsWith("\"points\":null}}"));
	}

	@Test
	public void malformedProgressionReadingsAreRefused()
	{
		assertFalse(account.observeDiaries(SESSION, new boolean[11][4], now));
		assertFalse(account.observeDiaries(SESSION, new boolean[12][3], now));
		assertFalse(account.observeCombatAchievements(SESSION, new boolean[5], now));
		assertFalse(account.observeQuest(SESSION, 3, new TelemetryAccountState.QuestEntry(9, "Out of range",
			TelemetryAccountState.QUEST_FINISHED), now));
	}
}
