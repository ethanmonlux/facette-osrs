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
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The account observations schema 5 writes to {@code account-v5.json}: the bank, the Seed Vault,
 * per-quest state, diary tiers and Combat Achievement tiers. Holds no RuneLite types. Every
 * observation belongs to one character session and is discarded with it; nothing here outlives a
 * logout or survives a plugin restart.
 *
 * These change rarely and can be large, so they are not part of the fast snapshot and are never
 * rewritten by its heartbeat. A document is built only after something here changed, at most once
 * per write interval, and its revision is consumed only when it reaches the file.
 *
 * Mutators run on the client thread and the document is built on the publisher thread, so every
 * method is synchronized. Nothing here calls back into {@link TelemetryState}, which is what lets
 * that class call in here while holding its own monitor.
 */
final class TelemetryAccountState
{
	static final int MAX_DOCUMENT_BYTES = 262_144;

	// Above the game's own capacities, so a real container never reaches them. A container that does
	// is reported unavailable rather than cut short.
	static final int MAX_BANK_ITEMS = 1_600;

	static final int MAX_SEED_VAULT_ITEMS = 1_000;

	static final int MAX_QUESTS = 400;

	static final int MAX_QUEST_NAME_CHARS = 64;

	static final String KIND_ITEM = "item";
	static final String KIND_PLACEHOLDER = "placeholder";
	static final String KIND_FILLER = "filler";

	static final String QUEST_NOT_STARTED = "not_started";
	static final String QUEST_IN_PROGRESS = "in_progress";
	static final String QUEST_FINISHED = "finished";
	static final String QUEST_UNAVAILABLE = "unavailable";

	static final List<String> DIARY_REGIONS = Collections.unmodifiableList(Arrays.asList(
		"ardougne", "desert", "falador", "fremennik", "kandarin", "karamja", "kourend", "lumbridge",
		"morytania", "varrock", "western", "wilderness"));

	static final List<String> DIARY_TIERS = Collections.unmodifiableList(Arrays.asList(
		"easy", "medium", "hard", "elite"));

	static final List<String> COMBAT_ACHIEVEMENT_TIERS = Collections.unmodifiableList(Arrays.asList(
		"easy", "medium", "hard", "elite", "master", "grandmaster"));

	/** One stored item: the raw identifier the container holds, its count, and what kind it is. */
	static final class StoredItem
	{
		private final int itemId;
		private final int quantity;
		private final String kind;

		StoredItem(int itemId, int quantity, String kind)
		{
			if (itemId < 0 || quantity < 0)
			{
				throw new IllegalArgumentException("a stored item has a non-negative id and count");
			}
			if (!KIND_ITEM.equals(kind) && !KIND_PLACEHOLDER.equals(kind) && !KIND_FILLER.equals(kind))
			{
				throw new IllegalArgumentException("no such stored item kind: " + kind);
			}
			this.itemId = itemId;
			this.quantity = quantity;
			this.kind = kind;
		}

		@Override
		public boolean equals(Object o)
		{
			if (!(o instanceof StoredItem))
			{
				return false;
			}
			StoredItem that = (StoredItem) o;
			return itemId == that.itemId && quantity == that.quantity && kind.equals(that.kind);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(itemId, quantity, kind);
		}
	}

	/** One quest's reading, by RuneLite's own quest identifier and name. */
	static final class QuestEntry
	{
		private final int questId;
		private final String name;
		private final String state;

		QuestEntry(int questId, String name, String state)
		{
			if (!QUEST_NOT_STARTED.equals(state) && !QUEST_IN_PROGRESS.equals(state)
				&& !QUEST_FINISHED.equals(state) && !QUEST_UNAVAILABLE.equals(state))
			{
				throw new IllegalArgumentException("no such quest state: " + state);
			}
			this.questId = questId;
			this.name = Objects.requireNonNull(name, "name");
			this.state = state;
		}

		@Override
		public boolean equals(Object o)
		{
			if (!(o instanceof QuestEntry))
			{
				return false;
			}
			QuestEntry that = (QuestEntry) o;
			return questId == that.questId && name.equals(that.name) && state.equals(that.state);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(questId, name, state);
		}
	}

	/** A built document, its revision and the session it describes. */
	static final class Document
	{
		private final long revision;
		private final String characterSessionId;
		private final byte[] bytes;
		private final long changesAtBuild;

		private Document(long revision, String characterSessionId, byte[] bytes, long changesAtBuild)
		{
			this.revision = revision;
			this.characterSessionId = characterSessionId;
			this.bytes = bytes;
			this.changesAtBuild = changesAtBuild;
		}

		long getRevision()
		{
			return revision;
		}

		String getCharacterSessionId()
		{
			return characterSessionId;
		}

		byte[] getBytes()
		{
			return bytes;
		}
	}

	/** A stored-item container's observation. */
	private static final class Container
	{
		private String availability = TelemetryGroupObservation.NOT_OBSERVED;
		private Long observedAt;
		private List<StoredItem> items;
	}

	private final String instanceId;
	private final LongSupplier wallClockMillis;
	private final LongSupplier elapsedNanos;

	private String characterSessionId;

	// The revision of the last document that reached the file, and the session it described.
	private long committedRevision;
	private String committedSessionId;

	private boolean dirty;
	// Counts changes, so a commit can tell whether anything arrived after its document was built.
	private long changes;
	// A session ended after a document for it reached the file, so the next document clears it and
	// goes out without waiting for the write interval.
	private boolean clearingPending;
	private long lastAttemptAtElapsedNanos;
	private boolean attempted;
	// The run has stopped: no session can begin again, so the final clearing document stays final.
	private boolean closed;

	private Container bank = new Container();
	private Container seedVault = new Container();

	// RuneLite's quest enumeration as this build was compiled against it. Fixed for the run.
	private final int questCatalogueSize;
	private QuestEntry[] quests;
	private int questsRead;
	// Where the next round-robin batch of quest reads starts.
	private int questCursor;
	private boolean questPassComplete;
	private Long questsObservedAt;

	private boolean[][] diaries;
	private Long diariesObservedAt;

	private boolean[] combatAchievements;
	private Long combatAchievementsObservedAt;

	TelemetryAccountState(String instanceId, int questCatalogueSize, LongSupplier wallClockMillis,
		LongSupplier elapsedNanos)
	{
		this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
		if (questCatalogueSize < 0)
		{
			throw new IllegalArgumentException("questCatalogueSize must be non-negative");
		}
		this.questCatalogueSize = questCatalogueSize;
		this.wallClockMillis = Objects.requireNonNull(wallClockMillis, "wallClockMillis");
		this.elapsedNanos = Objects.requireNonNull(elapsedNanos, "elapsedNanos");
	}

	synchronized String getCharacterSessionId()
	{
		return characterSessionId;
	}

	/** Starts collecting for a character session. Nothing observed yet, so nothing to write. */
	synchronized void beginSession(String sessionId)
	{
		Objects.requireNonNull(sessionId, "sessionId");
		if (closed || sessionId.equals(characterSessionId))
		{
			return;
		}
		endSession();
		characterSessionId = sessionId;
	}

	/**
	 * Discards every observation. When a document for the ending session already reached the file,
	 * the next document is a clearing one, so that character's bank is not left on disk looking
	 * current.
	 */
	synchronized void endSession()
	{
		if (characterSessionId == null && !hasObservations())
		{
			return;
		}
		characterSessionId = null;
		bank = new Container();
		seedVault = new Container();
		quests = null;
		questsRead = 0;
		questCursor = 0;
		questPassComplete = false;
		questsObservedAt = null;
		diaries = null;
		diariesObservedAt = null;
		combatAchievements = null;
		combatAchievementsObservedAt = null;
		if (committedSessionId != null)
		{
			clearingPending = true;
			changed();
		}
		else if (!clearingPending)
		{
			// Nothing of this session reached the file, so nothing needs clearing. A replacement
			// still pending from the run's start is kept: it has to land whatever happens.
			dirty = false;
		}
	}

	/**
	 * Queues a document at once, before anything is observed, so this run's first write replaces an
	 * account document an earlier run left behind: one RuneLite closed or killed while logged in, or
	 * one whose final clearing write was superseded by a fast re-enable. Called once, as the run
	 * starts.
	 */
	synchronized void replacePreviousDocument()
	{
		clearingPending = true;
		changed();
	}

	/**
	 * Ends the session for good as the run stops. Unlike {@link #endSession()}, a session cannot
	 * begin again afterwards, so a game tick racing the shutdown cannot refill the final document.
	 */
	synchronized void close()
	{
		endSession();
		closed = true;
	}

	private void changed()
	{
		dirty = true;
		changes++;
	}

	private boolean hasObservations()
	{
		return bank.observedAt != null || seedVault.observedAt != null || quests != null
			|| diaries != null || combatAchievements != null;
	}

	/**
	 * The bank as the game delivered it. Null items, or more than the bound, make the group
	 * unavailable rather than cutting it short.
	 */
	synchronized boolean observeBank(String sessionId, List<StoredItem> items, long atMillis)
	{
		return observeContainer(sessionId, bank, items, MAX_BANK_ITEMS, atMillis);
	}

	synchronized boolean observeSeedVault(String sessionId, List<StoredItem> items, long atMillis)
	{
		return observeContainer(sessionId, seedVault, items, MAX_SEED_VAULT_ITEMS, atMillis);
	}

	// Every delivery is a new observation, even of the same contents: the player opened the
	// interface again, and the time is when the game last said so.
	private boolean observeContainer(String sessionId, Container container, List<StoredItem> items,
		int maxItems, long atMillis)
	{
		if (!isSession(sessionId))
		{
			return false;
		}
		if (items == null || items.size() > maxItems || items.contains(null))
		{
			container.availability = TelemetryGroupObservation.UNAVAILABLE;
			container.observedAt = null;
			container.items = null;
		}
		else
		{
			container.availability = TelemetryGroupObservation.OBSERVED;
			container.observedAt = atMillis;
			container.items = Collections.unmodifiableList(new ArrayList<>(items));
		}
		changed();
		return true;
	}

	/**
	 * Records one quest read at a catalogue position. The catalogue is RuneLite's, fixed for the
	 * life of the build, and a size change mid-session restarts the pass rather than mixing two.
	 */
	synchronized boolean observeQuest(String sessionId, int index, QuestEntry entry, long atMillis)
	{
		if (!isSession(sessionId) || questCatalogueSize <= 0 || questCatalogueSize > MAX_QUESTS
			|| index < 0 || index >= questCatalogueSize || entry == null)
		{
			return false;
		}
		if (quests == null)
		{
			quests = new QuestEntry[questCatalogueSize];
			questsRead = 0;
			questPassComplete = false;
			questsObservedAt = null;
		}
		QuestEntry bounded = entry.name.length() > MAX_QUEST_NAME_CHARS
			? new QuestEntry(entry.questId, entry.name.substring(0, MAX_QUEST_NAME_CHARS), entry.state)
			: entry;
		QuestEntry previous = quests[index];
		if (previous == null)
		{
			questsRead++;
		}
		if (!bounded.equals(previous))
		{
			quests[index] = bounded;
			questsObservedAt = atMillis;
			changed();
		}
		if (!questPassComplete && questsRead == questCatalogueSize)
		{
			questPassComplete = true;
			questsObservedAt = atMillis;
			changed();
		}
		return true;
	}

	/**
	 * Claims the next batch of catalogue positions to read and returns where it starts, or -1 when
	 * there is no catalogue to read. Round-robin, so every quest is re-read once per pass and a pass
	 * costs the same however far into the game the account is.
	 */
	synchronized int claimQuestBatch(int batchSize)
	{
		if (characterSessionId == null || questCatalogueSize <= 0 || questCatalogueSize > MAX_QUESTS
			|| batchSize <= 0)
		{
			return -1;
		}
		int start = questCursor;
		questCursor = (int) ((questCursor + (long) batchSize) % questCatalogueSize);
		return start;
	}

	synchronized int getQuestCatalogueSize()
	{
		return questCatalogueSize;
	}

	/** One reading of every diary tier, region by region in {@link #DIARY_REGIONS} order. */
	synchronized boolean observeDiaries(String sessionId, boolean[][] complete, long atMillis)
	{
		if (!isSession(sessionId) || complete == null || complete.length != DIARY_REGIONS.size())
		{
			return false;
		}
		boolean[][] copy = new boolean[complete.length][];
		for (int i = 0; i < complete.length; i++)
		{
			if (complete[i] == null || complete[i].length != DIARY_TIERS.size())
			{
				return false;
			}
			copy[i] = complete[i].clone();
		}
		if (!Arrays.deepEquals(copy, diaries))
		{
			diaries = copy;
			diariesObservedAt = atMillis;
			changed();
		}
		return true;
	}

	synchronized boolean observeCombatAchievements(String sessionId, boolean[] completed, long atMillis)
	{
		if (!isSession(sessionId) || completed == null
			|| completed.length != COMBAT_ACHIEVEMENT_TIERS.size())
		{
			return false;
		}
		if (!Arrays.equals(completed, combatAchievements))
		{
			combatAchievements = completed.clone();
			combatAchievementsObservedAt = atMillis;
			changed();
		}
		return true;
	}

	private boolean isSession(String sessionId)
	{
		return sessionId != null && sessionId.equals(characterSessionId);
	}

	/**
	 * The next document to write, or null when none is due: nothing changed, or the last attempt was
	 * less than the interval ago. A clearing document is due at once. The revision is the next one
	 * after the last committed, and is consumed only by {@link #markCommitted(Document)}.
	 */
	synchronized Document prepareDocument(long writeIntervalMillis)
	{
		if (!dirty)
		{
			return null;
		}
		long now = elapsedNanos.getAsLong();
		if (!clearingPending && attempted
			&& now - lastAttemptAtElapsedNanos < TimeUnit.MILLISECONDS.toNanos(writeIntervalMillis))
		{
			return null;
		}
		attempted = true;
		lastAttemptAtElapsedNanos = now;
		long revision = committedRevision + 1;
		return new Document(revision, characterSessionId,
			toJson(revision).getBytes(StandardCharsets.UTF_8), changes);
	}

	/** Records that a document reached the file. Changes made since it was built stay pending. */
	synchronized void markCommitted(Document document)
	{
		if (document.revision <= committedRevision)
		{
			return;
		}
		committedRevision = document.revision;
		committedSessionId = document.characterSessionId;
		if (!Objects.equals(document.characterSessionId, characterSessionId))
		{
			// The session ended or changed while this document was being written, so the file now
			// holds a character that is no longer current. Replace it at once, even when this was
			// the session's first document and its end therefore queued nothing.
			clearingPending = true;
			changed();
			return;
		}
		// The file matches the current session: no clearing is outstanding.
		clearingPending = false;
		// Anything that changed after the document was built is still waiting for the next one.
		dirty = changes != document.changesAtBuild;
	}

	synchronized long getCommittedRevision()
	{
		return committedRevision;
	}

	/** The document for one revision of the current observations. Key order is fixed. */
	synchronized String toJson(long revision)
	{
		StringBuilder sb = new StringBuilder(8_192);
		sb.append("{\"schema\":").append(TelemetrySnapshot.SCHEMA_5)
			.append(",\"source\":\"").append(TelemetrySnapshot.SOURCE)
			.append("\",\"kind\":\"account\",\"instanceId\":");
		TelemetrySnapshot.appendString(sb, instanceId, TelemetrySnapshot.MAX_INSTANCE_ID_CHARS);
		sb.append(",\"characterSessionId\":");
		appendNullableString(sb, characterSessionId, TelemetrySnapshot.MAX_INSTANCE_ID_CHARS);
		sb.append(",\"revision\":").append(revision);
		sb.append(",\"writtenAt\":").append(wallClockMillis.getAsLong());

		sb.append(",\"bank\":");
		appendContainer(sb, bank);
		sb.append(",\"seedVault\":");
		appendContainer(sb, seedVault);

		sb.append(",\"questStates\":{");
		String questAvailability = quests == null
			? TelemetryGroupObservation.NOT_OBSERVED
			: questPassComplete ? TelemetryGroupObservation.CURRENT : TelemetryGroupObservation.PARTIAL;
		appendObservation(sb, questAvailability, questsObservedAt);
		sb.append(",\"catalogueSize\":").append(questCatalogueSize);
		sb.append(",\"entries\":");
		if (quests == null)
		{
			sb.append("null");
		}
		else
		{
			sb.append('[');
			boolean first = true;
			for (QuestEntry entry : quests)
			{
				if (entry == null)
				{
					continue;
				}
				if (!first)
				{
					sb.append(',');
				}
				first = false;
				sb.append("{\"questId\":").append(entry.questId).append(",\"name\":");
				TelemetrySnapshot.appendString(sb, entry.name, MAX_QUEST_NAME_CHARS);
				sb.append(",\"state\":\"").append(entry.state).append("\"}");
			}
			sb.append(']');
		}
		sb.append('}');

		sb.append(",\"diaries\":{");
		appendObservation(sb, diaries == null
			? TelemetryGroupObservation.NOT_OBSERVED
			: TelemetryGroupObservation.CURRENT, diariesObservedAt);
		sb.append(",\"regions\":");
		if (diaries == null)
		{
			sb.append("null");
		}
		else
		{
			sb.append('[');
			for (int i = 0; i < DIARY_REGIONS.size(); i++)
			{
				if (i > 0)
				{
					sb.append(',');
				}
				sb.append("{\"region\":\"").append(DIARY_REGIONS.get(i)).append('"');
				for (int t = 0; t < DIARY_TIERS.size(); t++)
				{
					sb.append(",\"").append(DIARY_TIERS.get(t)).append("\":").append(diaries[i][t]);
				}
				sb.append('}');
			}
			sb.append(']');
		}
		sb.append('}');

		sb.append(",\"combatAchievements\":{");
		appendObservation(sb, combatAchievements == null
			? TelemetryGroupObservation.NOT_OBSERVED
			: TelemetryGroupObservation.CURRENT, combatAchievementsObservedAt);
		sb.append(",\"tiers\":");
		if (combatAchievements == null)
		{
			sb.append("null");
		}
		else
		{
			sb.append('[');
			for (int i = 0; i < COMBAT_ACHIEVEMENT_TIERS.size(); i++)
			{
				if (i > 0)
				{
					sb.append(',');
				}
				sb.append("{\"tier\":\"").append(COMBAT_ACHIEVEMENT_TIERS.get(i))
					.append("\",\"completed\":").append(combatAchievements[i]).append('}');
			}
			sb.append(']');
		}
		// No documented source variable exists for the points total, so it is never derived.
		sb.append(",\"points\":null}");

		sb.append('}');
		return sb.toString();
	}

	private static void appendContainer(StringBuilder sb, Container container)
	{
		sb.append('{');
		appendObservation(sb, container.availability, container.observedAt);
		sb.append(",\"items\":");
		if (container.items == null)
		{
			sb.append("null");
		}
		else
		{
			sb.append('[');
			for (int i = 0; i < container.items.size(); i++)
			{
				if (i > 0)
				{
					sb.append(',');
				}
				StoredItem item = container.items.get(i);
				sb.append("{\"itemId\":").append(item.itemId)
					.append(",\"quantity\":").append(item.quantity)
					.append(",\"kind\":\"").append(item.kind).append("\"}");
			}
			sb.append(']');
		}
		sb.append('}');
	}

	private static void appendObservation(StringBuilder sb, String availability, Long observedAt)
	{
		sb.append("\"availability\":\"").append(availability).append("\",\"observedAt\":")
			.append(observedAt == null ? "null" : observedAt.toString());
	}

	private static void appendNullableString(StringBuilder sb, String value, int maxChars)
	{
		if (value == null)
		{
			sb.append("null");
			return;
		}
		TelemetrySnapshot.appendString(sb, value, maxChars);
	}
}
