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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The single in-memory current snapshot, plus the rules that decide when it is published. Holds no
 * RuneLite types. Mutators are called from the client thread and the publication methods from
 * whichever thread is publishing, so every method is synchronized. The experience baselines and the
 * internal counters are never exported.
 */
final class TelemetryState
{
	static final int INVENTORY_CAPACITY = TelemetrySnapshot.INVENTORY_SLOTS;

	// RuneLite reports run energy in 1/100th of a percent.
	private static final int RUN_ENERGY_SCALE = 100;

	// The client reports special attack in 1/10th of a percent.
	private static final int SPECIAL_ATTACK_SCALE = 10;

	// A reading outside these bounds is reported as unavailable, not clamped.
	private static final int WEIGHT_MIN_KG = -1_000;

	private static final int WEIGHT_MAX_KG = 1_000_000;

	private static final String UNKNOWN_GAME_STATE = "UNKNOWN";

	// RuneLite's aggregate-experience sentinel, which is not a real trainable skill.
	private static final String OVERALL_SKILL_NAME = "overall";

	// Ceiling on the exported gain collection, so document size cannot grow with play duration.
	private static final int MAX_TRACKED_SKILLS = 32;

	private final String instanceId;

	// The only source for exported timestamps. Never measures an interval, because it can jump in
	// either direction.
	private final LongSupplier wallClockMillis;

	// The only source for interval decisions. Never exported.
	private final LongSupplier elapsedNanos;

	// Session-local total-experience baselines, keyed by lowercase skill name. Never exported.
	private final Map<String, Integer> xpBaselines = new HashMap<>();

	private final Map<String, TrackedSkillGain> sessionXpGains = new HashMap<>();

	// Experience seen while the run was still starting. Startup is deferred onto the client thread,
	// so events can arrive before any baseline exists, and seeding from live totals afterwards would
	// absorb every gain that landed in that window.
	private final Map<String, RetainedXp> preInitialXp = new HashMap<>();

	private static final class TrackedSkillGain
	{
		private final String skill;
		private final int order;
		private int gained;
		private int lastDelta;
		private long lastChangedAt;

		private TrackedSkillGain(String skill, int order)
		{
			this.skill = skill;
			this.order = order;
		}

		// latestGain equals earned for a live observation, and is smaller when a startup window
		// contributed several gains at once.
		private void add(int earned, int latestGain, long atMillis)
		{
			gained += earned;
			lastDelta = latestGain;
			lastChangedAt = atMillis;
		}

		private TelemetrySkillGain exported()
		{
			return new TelemetrySkillGain(skill, gained, lastDelta, lastChangedAt);
		}
	}

	// One skill's pre-initialization experience, reduced to the values a measurable delta needs. Not
	// an event list, which would grow without bound while startup stayed queued.
	private static final class RetainedXp
	{
		private final int earliestTotal;

		private int latestTotal;

		private int previousTotal;

		private long latestEventAtMillis;

		private long latestEventOrder;

		private RetainedXp(int total, long atMillis, long order)
		{
			this.earliestTotal = total;
			this.latestTotal = total;
			this.previousTotal = total;
			this.latestEventAtMillis = atMillis;
			this.latestEventOrder = order;
		}

		private void observe(int total, long atMillis, long order)
		{
			if (total <= latestTotal)
			{
				return;
			}
			previousTotal = latestTotal;
			latestTotal = total;
			latestEventAtMillis = atMillis;
			latestEventOrder = order;
		}

		private boolean hasMeasurableSpan()
		{
			return latestTotal > earliestTotal;
		}

		private int span()
		{
			return latestTotal - earliestTotal;
		}

		private int lastIncrement()
		{
			return latestTotal - previousTotal;
		}
	}

	// Reset when a session ends, so a later login seeds again rather than inheriting a comparison.
	private boolean xpBaselinesSeeded;

	// Decides which gain is the most recent. Recency cannot come from a timestamp: events arriving
	// in one game tick commonly share a millisecond.
	private long xpEventOrder;

	private long lastReportedXpOrder;

	private boolean dirty = true;

	private long version;

	private long pendingVersion;

	private long nextSeq;

	// A refused or failed publication leaves this alone, so the heartbeat is measured from the last
	// snapshot a reader could genuinely have seen.
	private long lastPublishAtElapsedNanos;

	private String gameState = UNKNOWN_GAME_STATE;
	private boolean loggedIn;

	// What the exported loggedIn flag reports: not that the client is at the logged-in game state,
	// but that this document's player data is valid. The two differ for up to one tick.
	private boolean playerStateComplete;

	private Integer world;
	private Integer combatLevel;

	// Not a login time: later than the login whenever the plugin was enabled mid-session. Survives a
	// world hop and is discarded at a session boundary.
	private Long trackingStartedAt;

	private Integer hitpointsCurrent;
	private Integer hitpointsBase;
	private Integer prayerCurrent;
	private Integer prayerBase;
	private Integer runEnergyPercent;
	private Integer specialAttackPercent;
	private Integer weightKg;

	private String attackStyle;
	private List<String> activePrayers;
	private TelemetryTarget target;

	private List<TelemetryItemSlot> equipmentSlots;

	private Integer usedSlots;
	private Integer freeSlots;
	private List<TelemetryItemSlot> inventorySlots;

	private String lastSkill;
	private Integer lastDelta;
	private Long lastChangedAt;

	TelemetryState(String instanceId, LongSupplier wallClockMillis, LongSupplier elapsedNanos)
	{
		this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
		this.wallClockMillis = Objects.requireNonNull(wallClockMillis, "wallClockMillis");
		this.elapsedNanos = Objects.requireNonNull(elapsedNanos, "elapsedNanos");
		// Elapsed readings carry no meaningful origin, so the first interval is measured from here.
		this.lastPublishAtElapsedNanos = elapsedNanos.getAsLong();
	}

	String getInstanceId()
	{
		return instanceId;
	}

	synchronized long getNextSeq()
	{
		return nextSeq;
	}

	synchronized boolean isDirty()
	{
		return dirty;
	}

	synchronized void updateSession(String gameStateName, boolean nowLoggedIn)
	{
		updateSession(gameStateName, nowLoggedIn, false);
	}

	/**
	 * Applies a game-state transition as one step. Ending the session is folded in here rather than
	 * exposed as a second call, because two synchronized calls leave a window in which a publication
	 * could see cleared experience while the session still read as live. Ending it discards the
	 * baselines and gains, so a later login cannot report a fabricated gain.
	 */
	synchronized void updateSession(String gameStateName, boolean nowLoggedIn, boolean sessionEnded)
	{
		gameState = set(gameState, gameStateName == null ? UNKNOWN_GAME_STATE : gameStateName);
		loggedIn = set(loggedIn, nowLoggedIn);

		if (!loggedIn)
		{
			clearPlayerDerived();
		}

		if (sessionEnded)
		{
			xpBaselines.clear();
			// A total observed during a startup that spanned a logout belongs to the session that
			// ended.
			preInitialXp.clear();
			xpBaselinesSeeded = false;
			if (!sessionXpGains.isEmpty())
			{
				markDirty();
				sessionXpGains.clear();
			}
			lastSkill = set(lastSkill, null);
			lastDelta = null;
			lastChangedAt = null;
			trackingStartedAt = set(trackingStartedAt, null);
			lastReportedXpOrder = 0L;
		}
	}

	synchronized void updateWorld(Integer newWorld)
	{
		if (loggedIn)
		{
			world = set(world, newWorld);
		}
	}

	// A non-positive level is what the client reports before the local player has resolved.
	synchronized void updateCombatLevel(int level)
	{
		if (loggedIn)
		{
			combatLevel = set(combatLevel, level > 0 ? Integer.valueOf(level) : null);
		}
	}

	synchronized void updateVitals(int hpCurrent, int hpBase, int prayCurrent, int prayBase,
		int rawRunEnergy, int rawSpecialAttack, int rawWeightKg)
	{
		if (!loggedIn)
		{
			return;
		}
		hitpointsCurrent = set(hitpointsCurrent, hpCurrent);
		hitpointsBase = set(hitpointsBase, hpBase);
		prayerCurrent = set(prayerCurrent, prayCurrent);
		prayerBase = set(prayerBase, prayBase);
		runEnergyPercent = set(runEnergyPercent, clamp(rawRunEnergy / RUN_ENERGY_SCALE, 0, 100));
		specialAttackPercent =
			set(specialAttackPercent, clamp(rawSpecialAttack / SPECIAL_ATTACK_SCALE, 0, 100));
		weightKg = set(weightKg, rawWeightKg >= WEIGHT_MIN_KG && rawWeightKg <= WEIGHT_MAX_KG
			? Integer.valueOf(rawWeightKg)
			: null);
	}

	// A null style is a steady state, but a null prayer list is refused: "not read" and "none
	// active" are different claims.
	synchronized boolean updateCombat(String style, List<String> prayers)
	{
		if (!loggedIn || prayers == null)
		{
			return false;
		}
		attackStyle = set(attackStyle, style);
		activePrayers = set(activePrayers, Collections.unmodifiableList(new ArrayList<>(prayers)));
		return true;
	}

	synchronized void updateTarget(TelemetryTarget npcTarget)
	{
		if (loggedIn)
		{
			target = set(target, npcTarget);
		}
	}

	// Any other size is refused rather than padded, which would claim slots never reported.
	synchronized boolean updateEquipment(List<TelemetryItemSlot> slots)
	{
		if (!loggedIn || slots == null || slots.size() != TelemetrySnapshot.EQUIPMENT_SLOTS.size())
		{
			return false;
		}
		equipmentSlots = set(equipmentSlots, Collections.unmodifiableList(new ArrayList<>(slots)));
		return true;
	}

	// Occupancy counts slots holding an item, never quantity.
	synchronized boolean updateInventory(List<TelemetryItemSlot> slots)
	{
		if (!loggedIn || slots == null || slots.size() != INVENTORY_CAPACITY)
		{
			return false;
		}
		List<TelemetryItemSlot> next = Collections.unmodifiableList(new ArrayList<>(slots));
		int occupied = 0;
		for (TelemetryItemSlot slot : next)
		{
			if (slot.isOccupied())
			{
				occupied++;
			}
		}
		usedSlots = set(usedSlots, occupied);
		freeSlots = set(freeSlots, INVENTORY_CAPACITY - occupied);
		inventorySlots = set(inventorySlots, next);
		return true;
	}

	/**
	 * Records that the current live sample populated every player-derived value the schema requires.
	 * Refused while any is missing, which makes "logged in implies a complete player block" true by
	 * construction rather than by sampling order.
	 */
	synchronized boolean markPlayerStateComplete()
	{
		if (!loggedIn
			|| world == null
			|| combatLevel == null
			|| hitpointsCurrent == null
			|| hitpointsBase == null
			|| prayerCurrent == null
			|| prayerBase == null
			|| runEnergyPercent == null
			|| specialAttackPercent == null
			|| activePrayers == null
			|| equipmentSlots == null
			|| inventorySlots == null)
		{
			return false;
		}
		playerStateComplete = set(playerStateComplete, true);
		return true;
	}

	/**
	 * Establishes a skill's comparison baseline from the client's current total without treating it as
	 * an observation. The plugin can be enabled while already logged in, after the login-time events
	 * have fired, so the next real gain would otherwise be swallowed by the first-observation rule. A
	 * total retained during startup bounds a genuine gain, which is reported here.
	 */
	synchronized boolean seedXpBaseline(String skillName, int skillOrder, int totalXp)
	{
		if (!loggedIn || skillName == null || totalXp < 0)
		{
			return false;
		}
		String skill = skillName.toLowerCase(Locale.ROOT);
		if (OVERALL_SKILL_NAME.equals(skill))
		{
			return false;
		}
		// Consumed whether or not it is used, so evidence for a skill that already has a baseline
		// cannot influence anything later.
		RetainedXp retained = preInitialXp.remove(skill);
		if (xpBaselines.containsKey(skill))
		{
			return false;
		}

		// The baseline always ends at the trusted live total, whatever the retained evidence said.
		xpBaselines.put(skill, totalXp);

		if (retained == null || !retained.hasMeasurableSpan())
		{
			// Events carry a running total rather than a delta, so one observation cannot say what
			// the total was before it. That gain is left unreported rather than invented.
			return true;
		}

		if (retained.latestTotal > totalXp)
		{
			// Reporting a span the live client contradicts would fabricate a gain.
			return true;
		}

		// The span is everything the window accounts for; the last increment is one gain.
		int span = retained.span();
		int lastIncrement = retained.lastIncrement();
		accumulateSessionGain(skill, skillOrder, span, lastIncrement, retained.latestEventAtMillis);

		// Seeding walks every skill in one pass, so several can have measurable spans. Comparing
		// arrival positions also stops retained evidence displacing a newer live observation.
		if (retained.latestEventOrder <= lastReportedXpOrder)
		{
			return true;
		}

		lastSkill = skill;
		lastDelta = lastIncrement;
		lastChangedAt = retained.latestEventAtMillis;
		lastReportedXpOrder = retained.latestEventOrder;
		markDirty();
		return true;
	}

	/**
	 * Retains a skill's total reported before the run finished initializing. Deliberately does not
	 * require a logged-in state: the client has not been sampled yet, so refusing on that basis would
	 * discard exactly the events this exists to keep.
	 */
	synchronized boolean recordPreInitialXp(String skillName, int totalXp)
	{
		if (skillName == null || totalXp < 0)
		{
			return false;
		}
		String skill = skillName.toLowerCase(Locale.ROOT);
		if (OVERALL_SKILL_NAME.equals(skill))
		{
			return false;
		}
		long atMillis = wallClockMillis.getAsLong();
		long order = ++xpEventOrder;
		RetainedXp existing = preInitialXp.get(skill);
		if (existing == null)
		{
			if (totalXp == 0)
			{
				// The client can report zero while a skill's data is still initializing, and
				// anchoring there would export the player's whole skill total through a field only
				// meant to carry a change.
				return false;
			}
			preInitialXp.put(skill, new RetainedXp(totalXp, atMillis, order));
			return true;
		}
		existing.observe(totalXp, atMillis, order);
		return false;
	}

	/**
	 * Drops retained pre-initialization totals when they can no longer refer to the session they came
	 * from. Not called merely because initialization finished, since a startup completing during a
	 * loading screen has no live session to seed from yet.
	 */
	synchronized void discardPreInitialXp()
	{
		preInitialXp.clear();
	}

	// Asked on every live sample rather than once at startup, because the startup callback can land
	// during a loading screen, where there is no live session to read totals from.
	synchronized boolean needsXpBaselineSeeding()
	{
		return loggedIn && !xpBaselinesSeeded;
	}

	// Refused while logged out, where any totals read would not belong to a live session.
	synchronized void markXpBaselinesSeeded()
	{
		if (!loggedIn)
		{
			return;
		}
		xpBaselinesSeeded = true;
		if (trackingStartedAt == null)
		{
			trackingStartedAt = wallClockMillis.getAsLong();
			markDirty();
		}
	}

	/**
	 * Observes a skill's total experience. The first observation in a session only seeds the
	 * comparison and reports no gain. The total itself is never exported.
	 */
	synchronized boolean observeXp(String skillName, int skillOrder, int totalXp)
	{
		if (!loggedIn || skillName == null)
		{
			return false;
		}
		String skill = skillName.toLowerCase(Locale.ROOT);
		if (OVERALL_SKILL_NAME.equals(skill))
		{
			return false;
		}
		Integer previous = xpBaselines.get(skill);
		if (previous == null)
		{
			if (totalXp == 0)
			{
				// A zero never becomes a baseline. The next non-zero observation claims the skill.
				return false;
			}
			xpBaselines.put(skill, totalXp);
			return false;
		}
		if (totalXp <= previous)
		{
			// Ignored without moving the baseline. Lowering it would make the return to the true
			// total look like a gain the size of the dip.
			return false;
		}
		xpBaselines.put(skill, totalXp);

		int delta = totalXp - previous;
		long atMillis = wallClockMillis.getAsLong();
		accumulateSessionGain(skill, skillOrder, delta, delta, atMillis);

		lastSkill = skill;
		lastDelta = delta;
		lastChangedAt = atMillis;
		lastReportedXpOrder = ++xpEventOrder;
		markDirty();
		return true;
	}

	// Refuses a new entry beyond the ceiling, but always updates an existing one.
	private void accumulateSessionGain(String skill, int skillOrder, int earned, int latestGain,
		long atMillis)
	{
		if (earned <= 0 || latestGain <= 0 || OVERALL_SKILL_NAME.equals(skill))
		{
			return;
		}
		TrackedSkillGain gain = sessionXpGains.get(skill);
		if (gain == null)
		{
			if (sessionXpGains.size() >= MAX_TRACKED_SKILLS)
			{
				return;
			}
			gain = new TrackedSkillGain(skill, skillOrder);
			sessionXpGains.put(skill, gain);
		}
		gain.add(earned, latestGain, atMillis);
		markDirty();
	}

	// Ordered by enum position, so a reader diffing two snapshots sees only real changes.
	private List<TelemetrySkillGain> exportedSkillGains()
	{
		Map<Integer, TelemetrySkillGain> ordered = new TreeMap<>();
		for (TrackedSkillGain gain : sessionXpGains.values())
		{
			ordered.put(gain.order, gain.exported());
		}
		return new ArrayList<>(ordered.values());
	}

	/**
	 * Whether a publication is due: the state changed, or the last one is old enough that a reader
	 * needs a fresh heartbeat. Measured against monotonic elapsed time, because a backward wall-clock
	 * adjustment would stop a healthy idle plugin heartbeating for the size of the jump.
	 */
	synchronized boolean isPublicationDue(long heartbeatIntervalMillis)
	{
		if (dirty)
		{
			return true;
		}
		long heartbeatIntervalNanos = TimeUnit.MILLISECONDS.toNanos(heartbeatIntervalMillis);
		return elapsedNanos.getAsLong() - lastPublishAtElapsedNanos >= heartbeatIntervalNanos;
	}

	/**
	 * Builds the next snapshot, carrying a sequence number that is consumed only if it reaches the
	 * file. The final snapshot written at shutdown or disable passes false, which forces every
	 * gameplay-derived field null.
	 */
	synchronized TelemetrySnapshot nextSnapshot(boolean pluginActive)
	{
		pendingVersion = version;

		// Player-derived values are exported only when the client is live, the plugin is running,
		// and a sample has populated them, whatever the fields currently hold.
		boolean live = pluginActive && loggedIn && playerStateComplete;
		TelemetrySnapshot.Builder b = TelemetrySnapshot.builder()
			.envelope(instanceId, nextSeq, wallClockMillis.getAsLong());

		if (live)
		{
			b.session(pluginActive, gameState, true, world, combatLevel, trackingStartedAt)
				.vitals(hitpointsCurrent, hitpointsBase, prayerCurrent, prayerBase,
					runEnergyPercent, specialAttackPercent, weightKg)
				.combat(attackStyle, activePrayers, target)
				.equipment(equipmentSlots)
				.inventory(usedSlots, freeSlots, inventorySlots)
				.xp(lastSkill, lastDelta, lastChangedAt, exportedSkillGains());
		}
		else
		{
			b.session(pluginActive, gameState, false, null, null, null)
				.vitals(null, null, null, null, null, null, null)
				.combat(null, null, null)
				.equipment(null)
				.inventory(null, null, null)
				.xp(null, null, null, null);
		}

		return b.build();
	}

	/**
	 * Records that the preceding snapshot reached the file. The sequence advances only here, so a
	 * refused or failed write leaves the number for the retry.
	 */
	synchronized void recordPublished()
	{
		nextSeq++;
		lastPublishAtElapsedNanos = elapsedNanos.getAsLong();
		if (version == pendingVersion)
		{
			dirty = false;
		}
	}

	private void clearPlayerDerived()
	{
		playerStateComplete = set(playerStateComplete, false);
		world = set(world, null);
		combatLevel = set(combatLevel, null);
		hitpointsCurrent = set(hitpointsCurrent, null);
		hitpointsBase = set(hitpointsBase, null);
		prayerCurrent = set(prayerCurrent, null);
		prayerBase = set(prayerBase, null);
		runEnergyPercent = set(runEnergyPercent, null);
		specialAttackPercent = set(specialAttackPercent, null);
		weightKg = set(weightKg, null);
		attackStyle = set(attackStyle, null);
		activePrayers = set(activePrayers, null);
		target = set(target, null);
		equipmentSlots = set(equipmentSlots, null);
		usedSlots = set(usedSlots, null);
		freeSlots = set(freeSlots, null);
		inventorySlots = set(inventorySlots, null);

		// The experience fields and the tracking stamp are left alone, because a world hop passes
		// through here with the session intact and a gain cannot be reconstructed later. A genuine
		// session end discards them instead.
	}

	// Every mutator writes through this, so "changed" and "assigned" cannot drift apart.
	private <T> T set(T current, T next)
	{
		if (!Objects.equals(current, next))
		{
			markDirty();
		}
		return next;
	}

	private void markDirty()
	{
		dirty = true;
		version++;
	}

	private static Integer clamp(int value, int min, int max)
	{
		return Math.max(min, Math.min(max, value));
	}
}
