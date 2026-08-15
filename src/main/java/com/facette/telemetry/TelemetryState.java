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
 * The single in-memory current snapshot, plus the sequencing and refresh rules that decide when
 * it is published.
 *
 * <p>Holds no RuneLite types: {@link FacetteTelemetryPlugin} translates game events into the calls
 * below, so every normalization, nulling, and sequencing rule here is exercisable without a game
 * client.
 *
 * <p>Threading: mutators are called from the RuneLite client thread and the publication methods
 * from whichever thread is publishing, so every method is synchronized on this instance.
 * {@link #nextSnapshot(boolean)} and {@link #recordPublished()} are a pair requiring one
 * publication at a time, which {@link PublisherRunContext} guarantees by giving each run a single
 * publisher thread.
 *
 * <p>The experience baselines, the arrival-order counter, the change counter, and the elapsed
 * reading used for cadence are never exported.
 */
final class TelemetryState
{
	/** Old School inventory capacity, in slots. */
	static final int INVENTORY_CAPACITY = TelemetrySnapshot.INVENTORY_SLOTS;

	/** Divisor converting RuneLite's 1/100th-of-a-percent run energy into whole percent. */
	private static final int RUN_ENERGY_SCALE = 100;

	/** Divisor converting the client's 1/10th-of-a-percent special attack into whole percent. */
	private static final int SPECIAL_ATTACK_SCALE = 10;

	/**
	 * Bounds outside which a reported weight is not treated as a weight at all. Generous by a wide
	 * margin — every item in the game carried at once falls inside, as does a full set of
	 * weight-reducing equipment at its negative extreme. A reading outside them is reported as
	 * unavailable rather than clamped.
	 */
	private static final int WEIGHT_MIN_KG = -1_000;

	private static final int WEIGHT_MAX_KG = 1_000_000;

	private static final String UNKNOWN_GAME_STATE = "UNKNOWN";

	/**
	 * RuneLite's aggregate-experience sentinel, which is not a real trainable skill. Compared
	 * lowercase, matching how skill names are normalized here.
	 */
	private static final String OVERALL_SKILL_NAME = "overall";

	/**
	 * Hard ceiling on the exported session-gain collection, unreachable in practice because Old
	 * School has fewer skills than this. It is a bound that does not depend on the caller behaving,
	 * which is what makes the document size provably independent of play duration.
	 */
	private static final int MAX_TRACKED_SKILLS = 32;

	private final String instanceId;

	/**
	 * Wall-clock milliseconds. The only source for values that leave this process as timestamps —
	 * {@code emittedAt}, {@code trackingStartedAt}, {@code lastChangedAt}. Never used to measure
	 * an interval, because it can jump in either direction.
	 */
	private final LongSupplier wallClockMillis;

	/**
	 * Monotonic elapsed nanoseconds. The only source for interval decisions. Its absolute value is
	 * meaningless and never exported; only differences between two readings mean anything, and
	 * those are unaffected by any adjustment to wall time.
	 */
	private final LongSupplier elapsedNanos;

	/** Session-local total-experience baselines, keyed by lowercase skill name. Never exported. */
	private final Map<String, Integer> xpBaselines = new HashMap<>();

	/**
	 * Cumulative session-local experience gains, keyed by lowercase skill name. One entry per skill
	 * that has actually advanced, so the collection is bounded by the number of skills rather than
	 * by the number of gains, and it is discarded wholesale at a session boundary.
	 */
	private final Map<String, TrackedSkillGain> sessionXpGains = new HashMap<>();

	/**
	 * Experience evidence retained per skill while the run was still starting, keyed by lowercase
	 * skill name.
	 *
	 * <p>Startup is deferred onto the client thread, so experience events can arrive before any
	 * baseline exists. They are held here rather than dropped, because seeding afterwards from the
	 * live totals would silently absorb every gain that landed in that window — and the window is
	 * as long as the client takes to drain its queue, not a fixed tick. One aggregate entry per
	 * skill, so the map stays bounded however long startup is queued.
	 *
	 * <p>{@link #seedXpBaseline} is where retained evidence is turned into an exported gain.
	 */
	private final Map<String, RetainedXp> preInitialXp = new HashMap<>();

	/**
	 * One skill's accumulated session gain, updated in place. Carries the caller's enum position
	 * so the exported collection can be ordered by it; that position is never exported.
	 */
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

		/**
		 * @param earned     how much to add to the cumulative session total
		 * @param latestGain the size of the most recent single gain, which equals {@code earned}
		 *                   for a live observation but is smaller when a startup window
		 *                   contributed several gains at once
		 */
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

	/**
	 * The experience evidence one skill accumulated before the run finished initializing, reduced
	 * to the values a measurable delta needs. Not an event list, because that would grow without
	 * bound while startup stayed queued.
	 *
	 * <p>The previous total trails the latest so "how big was the last gain" stays answerable
	 * separately from "how much did this window account for".
	 */
	private static final class RetainedXp
	{
		/** Total at the first observation. Fixed for the life of the entry. */
		private final int earliestTotal;

		/** Total at the most recent strictly increasing observation. */
		private int latestTotal;

		/** Total immediately before {@link #latestTotal}. */
		private int previousTotal;

		/** Wall-clock time of the observation that last advanced {@link #latestTotal}. */
		private long latestEventAtMillis;

		/** Arrival position of that observation, from the owning state's counter. Never exported. */
		private long latestEventOrder;

		private RetainedXp(int total, long atMillis, long order)
		{
			this.earliestTotal = total;
			this.latestTotal = total;
			this.previousTotal = total;
			this.latestEventAtMillis = atMillis;
			this.latestEventOrder = order;
		}

		/**
		 * Applies a later observation. Only a strict increase moves anything: an equal or lower
		 * total is not an event the exported delta represents, so it neither lowers a total nor
		 * moves the timestamp.
		 */
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

		/** Whether two distinct increasing totals bound a span that can actually be measured. */
		private boolean hasMeasurableSpan()
		{
			return latestTotal > earliestTotal;
		}

		/** The whole measurable span: everything this window can account for. */
		private int span()
		{
			return latestTotal - earliestTotal;
		}

		/** The most recent single increase, which is at most {@link #span()}. */
		private int lastIncrement()
		{
			return latestTotal - previousTotal;
		}
	}

	/**
	 * Whether this session's baselines have been established from the client's live totals. Reset
	 * when a session ends, so a later login seeds again rather than inheriting a comparison.
	 */
	private boolean xpBaselinesSeeded;

	/**
	 * Monotonic counter over experience events, used only to decide which gain is most recent.
	 * Never exported.
	 *
	 * <p>Recency cannot be decided from {@code lastChangedAt}: two skills whose events arrive in
	 * one game tick commonly share a millisecond, and a wall clock can be adjusted backwards
	 * between two events, which would make the older look newer.
	 */
	private long xpEventOrder;

	/**
	 * Arrival position of the gain the exported experience fields currently describe. Zero when
	 * they describe nothing, which is why the counter starts at one.
	 */
	private long lastReportedXpOrder;

	private boolean dirty = true;

	/** Monotonic counter incremented whenever a change marks the state dirty. */
	private long version;

	/** Change counter the snapshot handed out by {@link #nextSnapshot(boolean)} reflects. */
	private long pendingVersion;

	private long nextSeq;

	/**
	 * Monotonic elapsed reading at the last publication that actually reached the file. Not a
	 * timestamp and never exported. A refused or failed publication leaves it alone, so a
	 * heartbeat is measured from the last snapshot a reader could genuinely have seen.
	 */
	private long lastPublishAtElapsedNanos;

	private String gameState = UNKNOWN_GAME_STATE;
	private boolean loggedIn;

	/**
	 * Whether a live sample has populated every player-derived value the schema requires.
	 *
	 * <p>This is what {@code session.loggedIn} reports: not "the client is at the logged-in game
	 * state" but "the player-derived data in this document is valid". The two differ for up to one
	 * tick after a login or world hop, and conflating them would leave a document claiming a live
	 * session while its inventory, equipment, and prayer collections had never been read.
	 */
	private boolean playerStateComplete;

	private Integer world;
	private Integer combatLevel;

	/**
	 * When this plugin instance established its baselines for the current session. Not a login
	 * time: it is later than the login whenever the plugin was enabled mid-session. Survives a
	 * world hop with the session and is discarded at a session boundary.
	 */
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

	/**
	 * @param wallClockMillis wall-clock milliseconds, for exported timestamps only
	 * @param elapsedNanos    monotonic elapsed nanoseconds, for interval decisions only. Kept
	 *                        separate from the wall clock because an interval measured against
	 *                        wall time stops elapsing when wall time is adjusted backwards
	 */
	TelemetryState(String instanceId, LongSupplier wallClockMillis, LongSupplier elapsedNanos)
	{
		this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
		this.wallClockMillis = Objects.requireNonNull(wallClockMillis, "wallClockMillis");
		this.elapsedNanos = Objects.requireNonNull(elapsedNanos, "elapsedNanos");
		// Anchored at construction: elapsed readings carry no meaningful origin, so measuring the
		// first interval from zero would compare against an arbitrary point.
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

	/**
	 * Records the current client game state. Leaving the logged-in state discards every
	 * player-derived value rather than letting the last live reading persist.
	 */
	synchronized void updateSession(String gameStateName, boolean nowLoggedIn)
	{
		updateSession(gameStateName, nowLoggedIn, false);
	}

	/**
	 * Applies a client game-state transition as one atomic step.
	 *
	 * <p>Ending the session is folded in here rather than exposed as a second call, because two
	 * separately synchronized calls leave a window the publisher can be released into: discarding
	 * the experience baselines marks the state dirty, so a publication could observe cleared
	 * experience while the session still read as live and still carried the previous world,
	 * vitals, inventory, equipment, prayers, and target.
	 *
	 * @param sessionEnded whether reaching this state ends the play session, discarding the
	 *                     session-local baselines and accumulated gains so a later login cannot
	 *                     inherit a previous session's comparison and report a fabricated gain
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
			// ended, and must not become a later login's baseline.
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
			// The fields describe nothing again, so nothing outranks the next gain.
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

	/**
	 * Records the local player's combat level. A non-positive level is what the client reports
	 * before the local player has been resolved, and is exported as unavailable rather than zero.
	 */
	synchronized void updateCombatLevel(int level)
	{
		if (loggedIn)
		{
			combatLevel = set(combatLevel, level > 0 ? Integer.valueOf(level) : null);
		}
	}

	/**
	 * Records the player's vitals.
	 *
	 * @param rawRunEnergy     run energy exactly as RuneLite reports it, in 1/100th of a percent;
	 *                         normalized here to whole percent in 0..100
	 * @param rawSpecialAttack special attack energy as the client reports it, in 1/10th of a
	 *                         percent; normalized here to whole percent in 0..100
	 * @param rawWeightKg      the weight the client reports, in kilograms; exported as unavailable
	 *                         rather than clamped when outside the bounds a real load can reach
	 */
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

	/**
	 * Records the player's current combat configuration.
	 *
	 * @param style   the normalized attack-style label, or null when no trustworthy reading
	 *                exists. Null is a legitimate steady state, not a failure
	 * @param prayers the active prayer names in the caller's deterministic order; empty when none
	 *                is active. A null collection is refused, because "not read" and "none active"
	 *                are different claims
	 * @return true when the prayer collection was accepted
	 */
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

	/**
	 * Records the NPC the local player is interacting with, or its absence. Passing null is a real
	 * update rather than a no-op: the target has to disappear the moment the interaction ends, the
	 * actor becomes impermissible, or the actor is gone.
	 */
	synchronized void updateTarget(TelemetryTarget npcTarget)
	{
		if (loggedIn)
		{
			target = set(target, npcTarget);
		}
	}

	/**
	 * Records the eleven visible equipment slots.
	 *
	 * @param slots exactly one entry per exported equipment slot, in exported order. Any other
	 *              size is refused rather than padded or truncated, because padding would claim
	 *              empty slots the client never reported
	 * @return true when the reading was accepted
	 */
	synchronized boolean updateEquipment(List<TelemetryItemSlot> slots)
	{
		if (!loggedIn || slots == null || slots.size() != TelemetrySnapshot.EQUIPMENT_SLOTS.size())
		{
			return false;
		}
		equipmentSlots = set(equipmentSlots, Collections.unmodifiableList(new ArrayList<>(slots)));
		return true;
	}

	/**
	 * Records all twenty-eight inventory slots and the occupancy they imply. Occupancy counts
	 * slots holding an item, never item quantity: one slot holding a million coins is one used
	 * slot.
	 *
	 * @param slots exactly {@link #INVENTORY_CAPACITY} entries, in ascending slot order
	 * @return true when the reading was accepted
	 */
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
	 * Records that the current live sample populated every player-derived value the schema
	 * requires, so a snapshot may now report the session as carrying valid player data.
	 *
	 * <p>Refused while any of those values is still missing. That refusal makes "logged in implies
	 * a complete player block" true by construction rather than by the sampler happening to have
	 * read everything before the publisher woke up.
	 *
	 * @return true when the state now carries a complete player block
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
	 * Establishes a skill's comparison baseline from the client's current total, without treating
	 * it as an observation.
	 *
	 * <p>The plugin can be enabled while the player is already logged in, where the login-time
	 * experience events have long since fired: nothing has filled the baselines, and the next real
	 * gain would otherwise be consumed by {@link #observeXp}'s first-observation rule and never
	 * exported. Seeding can never report a gain by itself and never moves a baseline that already
	 * exists.
	 *
	 * <p>Where {@link #recordPreInitialXp} captured an earlier total, that total bounds a genuine
	 * gain which is reported here, so experience earned while startup was queued is not absorbed.
	 *
	 * @param skillOrder the caller's enum position, used only to order the exported collection
	 * @param totalXp    the client's current total; zero is legitimate for an untrained skill and
	 *                   is seeded, while a negative total is refused
	 * @return true when this call established a new baseline
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
		// Consumed whether or not it is used, so evidence held for a skill that already has a
		// baseline cannot survive to influence anything later.
		RetainedXp retained = preInitialXp.remove(skill);
		// A baseline already established by a real observation, or by an earlier seed, is the more
		// trustworthy one.
		if (xpBaselines.containsKey(skill))
		{
			return false;
		}

		// The baseline always ends at the trusted live total, whatever the retained evidence said.
		xpBaselines.put(skill, totalXp);

		if (retained == null || !retained.hasMeasurableSpan())
		{
			// Either nothing arrived while starting, or exactly one observation did. A single
			// observation reports the total *after* whichever gain produced it, and the total
			// before that gain is not knowable — experience events carry a running total, not a
			// delta. So the first gain is unmeasurable and is left unreported rather than invented.
			// Seeding alone changes nothing exported, so the state is not marked dirty.
			return true;
		}

		if (retained.latestTotal > totalXp)
		{
			// Retained evidence sits above the total the client now reports. Reporting a span the
			// live client contradicts would fabricate a gain; the baseline above still holds.
			return true;
		}

		// The span is everything this window accounts for; the last increment is the size of one
		// gain. They coincide when the window held exactly one measurable gain and diverge when it
		// held more, so conflating them would overstate a single event.
		int span = retained.span();
		int lastIncrement = retained.lastIncrement();
		accumulateSessionGain(skill, skillOrder, span, lastIncrement, retained.latestEventAtMillis);

		// The latest-gain triple can hold only the newest gain, and seeding walks every skill in
		// enum order, so several can have measurable spans in one pass. Compared by arrival
		// position, which also stops retained evidence displacing a newer live observation.
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
	 * Retains a skill's total experience reported before the run finished initializing.
	 *
	 * <p>Deliberately does not require {@code loggedIn}: during this window the client has not
	 * been sampled yet, so refusing on that basis would discard exactly the events this exists to
	 * keep.
	 *
	 * <p>Each later strict increase extends the span and moves its event time, so the delta
	 * eventually exported is stamped with the event that last contributed to it.
	 *
	 * @return true when this call created the entry for a skill that had none
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
				// A zero is never trusted to anchor a comparison, here or in observeXp. The client
				// can report zero for a skill while its data is still initializing, and anchoring
				// there would export the player's entire skill total as a single gain — through a
				// field only ever meant to carry a change. The cost is that a genuine gain on a
				// skill truly at zero is not exported: the same bounded loss the
				// unmeasurable-first-gain rule already accepts.
				return false;
			}
			preInitialXp.put(skill, new RetainedXp(totalXp, atMillis, order));
			return true;
		}
		existing.observe(totalXp, atMillis, order);
		return false;
	}

	/**
	 * Drops any retained pre-initialization totals, for the one case where they can no longer
	 * refer to the session they were taken from: a session ending during a startup that had not
	 * yet applied the transition.
	 *
	 * <p>Deliberately <em>not</em> called merely because initialization finished — a startup that
	 * completes during a loading screen or world hop has no live session to seed from yet, and the
	 * totals have to survive until the one that follows.
	 */
	synchronized void discardPreInitialXp()
	{
		preInitialXp.clear();
	}

	/**
	 * Whether this session still needs its baselines established from the client's live totals.
	 *
	 * <p>Asked on every live sample rather than once at startup, because the startup callback is
	 * deferred onto the client thread and can land while the client is between states — a world
	 * hop, a loading screen — where there is no live session to read totals from. This also covers
	 * a logout and login inside one plugin run.
	 */
	synchronized boolean needsXpBaselineSeeding()
	{
		return loggedIn && !xpBaselinesSeeded;
	}

	/**
	 * Records that this session's baselines have been seeded, so the client is not re-read on
	 * every subsequent sample, and stamps when this plugin instance started tracking. Refused
	 * while logged out, where any totals read would not belong to a live session.
	 */
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
	 * Observes a skill's total experience. The first observation for a skill in a session only
	 * seeds the comparison and never reports a gain; only a subsequent increase updates the
	 * exported fields and the skill's session total. The total itself is never exported.
	 *
	 * @param skillOrder the caller's enum position, used only to order the exported collection
	 * @return true when this observation produced a reportable gain
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
			// Refused here as well as in seeding, so the aggregate sentinel has no path into the
			// exported experience fields at all. Unreachable from a real experience event, which
			// always carries a real skill.
			return false;
		}
		Integer previous = xpBaselines.get(skill);
		if (previous == null)
		{
			if (totalXp == 0)
			{
				// A zero never becomes a baseline; see recordPreInitialXp. Leaving the skill
				// unseeded is safe — the next non-zero observation, or a live seed, claims it.
				return false;
			}
			// First trustworthy reading this session: seed the comparison, report nothing.
			xpBaselines.put(skill, totalXp);
			return false;
		}
		if (totalXp <= previous)
		{
			// A total that has not advanced is ignored *without* moving the baseline. Lowering it
			// would make the eventual return to the true total look like a gain the size of the
			// dip — and a transient zero would then fabricate a gain the size of the whole skill.
			return false;
		}
		xpBaselines.put(skill, totalXp);

		int delta = totalXp - previous;
		long atMillis = wallClockMillis.getAsLong();
		// A live observation is one gain, so what it adds and its size as an event are the same
		// number. Only the deferred-startup window can make those differ.
		accumulateSessionGain(skill, skillOrder, delta, delta, atMillis);

		lastSkill = skill;
		lastDelta = delta;
		lastChangedAt = atMillis;
		// A live observation is always the newest thing seen, so no comparison is needed — but the
		// position is still recorded, because it is what a later retained span is measured against.
		lastReportedXpOrder = ++xpEventOrder;
		markDirty();
		return true;
	}

	/**
	 * Adds one positive gain to a skill's session total. Refuses the aggregate sentinel and
	 * refuses to create an entry beyond the tracked-skill ceiling, so the exported collection
	 * stays bounded whatever it is fed. An existing entry is always updated, so a bound that has
	 * been reached cannot stop a real skill's total from continuing to advance.
	 */
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

	/**
	 * The session's gains, ordered by the caller's enum position. Ordering by that rather than by
	 * arrival, name, or size is what makes the exported array deterministic, so a reader diffing
	 * two snapshots sees only real changes.
	 */
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
	 * Whether a publication is due: either the state changed, or the last publication is old
	 * enough that a reader needs a fresh heartbeat to distinguish a live plugin from a stale file.
	 *
	 * <p>Measured against monotonic elapsed time, never the wall clock: an adjustment backwards
	 * would keep the elapsed figure negative until wall time caught up, so a healthy idle plugin
	 * would stop heartbeating for the size of the jump. Written as a subtraction of two elapsed
	 * readings, so it also stays correct across the wraparound {@code System.nanoTime} may have.
	 */
	synchronized boolean isPublicationDue(long heartbeatIntervalMillis)
	{
		if (dirty)
		{
			return true;
		}
		// Saturating rather than overflowing: an absurd interval yields an unreachable threshold
		// instead of a wrapped negative one that would make everything due.
		long heartbeatIntervalNanos = TimeUnit.MILLISECONDS.toNanos(heartbeatIntervalMillis);
		return elapsedNanos.getAsLong() - lastPublishAtElapsedNanos >= heartbeatIntervalNanos;
	}

	/**
	 * Builds the next snapshot to publish, carrying the sequence number that will be consumed only
	 * if it reaches the file.
	 *
	 * @param pluginActive whether the plugin is still running; a final shutdown snapshot passes
	 *                     false, which also forces every gameplay-derived field null
	 */
	synchronized TelemetrySnapshot nextSnapshot(boolean pluginActive)
	{
		pendingVersion = version;

		// A snapshot never carries player-derived values unless the client is live, the plugin is
		// running, and a sample has actually populated them, whatever the fields currently hold.
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
	 * Records that the snapshot from the preceding {@link #nextSnapshot(boolean)} call reached the
	 * file. The sequence advances only here, so a refused or failed write leaves the number to be
	 * reused by the retry. The dirty flag is cleared only when nothing changed while that snapshot
	 * was being written; otherwise the change is republished rather than dropped.
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

		// The experience fields and the tracking stamp are left alone: a world hop passes through
		// here with the session intact, and a gain is an event no later sample can reconstruct.
		// Nothing leaks by keeping them, because nextSnapshot exports them only when the client is
		// live and the player block is complete. A genuine session end discards them, in the
		// sessionEnded branch of updateSession.
	}

	/**
	 * Assigns an exported value, marking the state dirty when it actually changed.
	 *
	 * <p>Every mutator writes its fields through this, so "changed" and "assigned" are one step and
	 * cannot drift apart.
	 *
	 * @return {@code next}, for assignment straight back into the field
	 */
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
