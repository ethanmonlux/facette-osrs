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
 * The single in-memory current snapshot, plus the sequencing and refresh rules that decide when it
 * is published. Holds no RuneLite types: {@link FacetteTelemetryPlugin} translates game events into
 * the calls below.
 *
 * Mutators are called from the RuneLite client thread and the publication methods from whichever
 * thread is publishing, so every method is synchronized on this instance.
 * {@link #nextSnapshot(boolean)} and {@link #recordPublished()} are a pair requiring one
 * publication at a time, which {@link PublisherRunContext} guarantees by giving each run a single
 * publisher thread. The experience baselines, the arrival-order counter, the change counter, and
 * the elapsed reading used for cadence are never exported.
 */
final class TelemetryState
{
	static final int INVENTORY_CAPACITY = TelemetrySnapshot.INVENTORY_SLOTS;

	/** Divisor converting RuneLite's 1/100th-of-a-percent run energy into whole percent. */
	private static final int RUN_ENERGY_SCALE = 100;

	/** Divisor converting the client's 1/10th-of-a-percent special attack into whole percent. */
	private static final int SPECIAL_ATTACK_SCALE = 10;

	/**
	 * Bounds outside which a reported weight is not treated as a weight at all. No real load reaches
	 * either end, and a reading outside them is reported as unavailable rather than clamped.
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
	 * School has fewer skills than this. It does not depend on the caller behaving, which is what
	 * makes the document size provably independent of play duration.
	 */
	private static final int MAX_TRACKED_SKILLS = 32;

	private final String instanceId;

	/**
	 * The only source for values that leave this process as timestamps. Never used to measure an
	 * interval, because it can jump in either direction.
	 */
	private final LongSupplier wallClockMillis;

	/**
	 * The only source for interval decisions. Its absolute value is meaningless and never exported;
	 * only differences between two readings mean anything, and those are unaffected by any
	 * adjustment to wall time.
	 */
	private final LongSupplier elapsedNanos;

	/** Session-local total-experience baselines, keyed by lowercase skill name. Never exported. */
	private final Map<String, Integer> xpBaselines = new HashMap<>();

	/**
	 * Cumulative session-local gains, keyed by lowercase skill name. One entry per skill that has
	 * actually advanced, so the collection is bounded by the number of skills rather than by the
	 * number of gains, and it is discarded wholesale at a session boundary.
	 */
	private final Map<String, TrackedSkillGain> sessionXpGains = new HashMap<>();

	/**
	 * Experience evidence retained per skill while the run was still starting, keyed by lowercase
	 * skill name. Startup is deferred onto the client thread, so events can arrive before any
	 * baseline exists, and seeding afterwards from the live totals would silently absorb every gain
	 * that landed in that window. One aggregate entry per skill keeps the map bounded however long
	 * startup is queued. {@link #seedXpBaseline} turns retained evidence into an exported gain.
	 */
	private final Map<String, RetainedXp> preInitialXp = new HashMap<>();

	/**
	 * One skill's accumulated session gain, updated in place. Carries the caller's enum position so the
	 * exported collection can be ordered by it; that position is never exported.
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
		 * {@code latestGain} equals {@code earned} for a live observation, and is smaller when a
		 * startup window contributed several gains at once.
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
	 * The experience evidence one skill accumulated before the run finished initializing, reduced to
	 * the values a measurable delta needs. Not an event list, which would grow without bound while
	 * startup stayed queued. The previous total trails the latest so the size of the last gain stays
	 * answerable separately from what the whole window accounts for.
	 */
	private static final class RetainedXp
	{
		private final int earliestTotal;

		private int latestTotal;

		private int previousTotal;

		private long latestEventAtMillis;

		/** Arrival position, from the owning state's counter. Never exported. */
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

		/** Everything this window can account for. */
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
	 * Reset when a session ends, so a later login seeds again rather than inheriting a comparison.
	 */
	private boolean xpBaselinesSeeded;

	/**
	 * Monotonic counter over experience events, used only to decide which gain is most recent. Never
	 * exported. Recency cannot come from {@code lastChangedAt}: events arriving in one game tick
	 * commonly share a millisecond, and a backward clock adjustment would make the older look newer.
	 */
	private long xpEventOrder;

	/**
	 * Arrival position of the gain the exported experience fields describe. Zero when they describe
	 * nothing, which is why the counter starts at one.
	 */
	private long lastReportedXpOrder;

	private boolean dirty = true;

	private long version;

	private long pendingVersion;

	private long nextSeq;

	/**
	 * Monotonic reading at the last publication that reached the file. Never exported. A refused or
	 * failed publication leaves it alone, so a heartbeat is measured from the last snapshot a reader
	 * could genuinely have seen.
	 */
	private long lastPublishAtElapsedNanos;

	private String gameState = UNKNOWN_GAME_STATE;
	private boolean loggedIn;

	/**
	 * Whether a live sample has populated every player-derived value the schema requires. This is what
	 * {@code session.loggedIn} reports: not that the client is at the logged-in game state, but that
	 * this document's player data is valid. The two differ for up to one tick after a login or hop.
	 */
	private boolean playerStateComplete;

	private Integer world;
	private Integer combatLevel;

	/**
	 * When this plugin instance established its baselines for the current session. Not a login time:
	 * it is later than the login whenever the plugin was enabled mid-session. Survives a world hop and
	 * is discarded at a session boundary.
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
	 * The two clocks are kept separate because an interval measured against wall time stops elapsing
	 * when wall time is adjusted backwards.
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

	/** Leaving the logged-in state discards every player-derived value rather than letting it persist. */
	synchronized void updateSession(String gameStateName, boolean nowLoggedIn)
	{
		updateSession(gameStateName, nowLoggedIn, false);
	}

	/**
	 * Applies a client game-state transition as one atomic step. Ending the session is folded in here
	 * rather than exposed as a second call, because two separately synchronized calls leave a window a
	 * publication could observe cleared experience while the session still read as live.
	 * {@code sessionEnded} discards the session-local baselines and accumulated gains, so a later login
	 * cannot inherit them and report a fabricated gain.
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
	 * A non-positive level is what the client reports before the local player has resolved, and is
	 * exported as unavailable rather than zero.
	 */
	synchronized void updateCombatLevel(int level)
	{
		if (loggedIn)
		{
			combatLevel = set(combatLevel, level > 0 ? Integer.valueOf(level) : null);
		}
	}

	/**
	 * Run energy arrives in 1/100th of a percent and special attack in 1/10th, both normalized here to
	 * whole percent. Weight arrives in kilograms and is reported as unavailable, not clamped, when
	 * outside the bounds a real load can reach.
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
	 * A null {@code style} is a legitimate steady state, but a null prayer collection is refused,
	 * because "not read" and "none active" are different claims.
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
	 * Records the interacted-with NPC, or its absence. Passing null is a real update, since the target
	 * has to disappear the moment the interaction ends.
	 */
	synchronized void updateTarget(TelemetryTarget npcTarget)
	{
		if (loggedIn)
		{
			target = set(target, npcTarget);
		}
	}

	/**
	 * Records the eleven visible equipment slots, in exported order. Any other size is refused rather
	 * than padded or truncated, because padding would claim slots the client never reported.
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
	 * Records all {@link #INVENTORY_CAPACITY} slots in ascending order and the occupancy they imply.
	 * Occupancy counts slots holding an item, never quantity.
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
	 * have fired, so the next real gain would otherwise be consumed by {@link #observeXp}'s
	 * first-observation rule. Seeding never reports a gain by itself and never moves an existing
	 * baseline. Where {@link #recordPreInitialXp} captured an earlier total, that total bounds a
	 * genuine gain which is reported here.
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
			// observation reports the total after whichever gain produced it, and events carry a
			// running total rather than a delta, so the total before it is not knowable. That first
			// gain is unmeasurable and is left unreported rather than invented.
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
	 * Retains a skill's total reported before the run finished initializing. Deliberately does not
	 * require {@code loggedIn}: the client has not been sampled yet, so refusing on that basis would
	 * discard exactly the events this exists to keep. Each later strict increase extends the span and
	 * moves its event time.
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
				// can report zero while a skill's data is still initializing, and anchoring there
				// would export the player's entire skill total through a field only ever meant to
				// carry a change. The cost is that a genuine gain on a skill truly at zero is not
				// exported, the same bounded loss the unmeasurable-first-gain rule already accepts.
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
	 * from: a session ending during a startup that had not yet applied the transition. Not called
	 * merely because initialization finished, since a startup completing during a loading screen has
	 * no live session to seed from yet.
	 */
	synchronized void discardPreInitialXp()
	{
		preInitialXp.clear();
	}

	/**
	 * Whether this session still needs baselines from the client's live totals. Asked on every live
	 * sample rather than once at startup, because the startup callback can land during a world hop or
	 * loading screen, where there is no live session to read totals from.
	 */
	synchronized boolean needsXpBaselineSeeding()
	{
		return loggedIn && !xpBaselinesSeeded;
	}

	/**
	 * Records that this session's baselines are seeded and stamps when tracking started. Refused while
	 * logged out, where any totals read would not belong to a live session.
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
	 * Observes a skill's total experience. The first observation in a session only seeds the comparison
	 * and reports no gain; only a later increase updates the exported fields and the session total. The
	 * total itself is never exported, and {@code skillOrder} only orders the exported collection.
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
				// unseeded is safe, since the next non-zero observation or a live seed claims it.
				return false;
			}
			// First trustworthy reading this session: seed the comparison, report nothing.
			xpBaselines.put(skill, totalXp);
			return false;
		}
		if (totalXp <= previous)
		{
			// Ignored without moving the baseline. Lowering it would make the eventual return to
			// the true total look like a gain the size of the dip, and a transient zero would
			// fabricate a gain the size of the whole skill.
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
		// A live observation is always the newest thing seen, so no comparison is needed. The
		// position is still recorded, because a later retained span is measured against it.
		lastReportedXpOrder = ++xpEventOrder;
		markDirty();
		return true;
	}

	/**
	 * Adds one positive gain to a skill's session total. Refuses to create an entry beyond the
	 * tracked-skill ceiling, but always updates an existing one, so a reached bound cannot stop a real
	 * skill from advancing.
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
	 * Ordered by the caller's enum position rather than by arrival, name, or size, so the exported
	 * array is deterministic and a reader diffing two snapshots sees only real changes.
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
	 * Whether a publication is due: the state changed, or the last publication is old enough that a
	 * reader needs a fresh heartbeat. Measured against monotonic elapsed time, never the wall clock,
	 * because a backward adjustment would stop a healthy idle plugin heartbeating for the size of the
	 * jump. Written as a subtraction of two elapsed readings, so it survives nanoTime wraparound.
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
	 * Builds the next snapshot, carrying the sequence number that is consumed only if it reaches the
	 * file. A final shutdown snapshot passes {@code pluginActive} false, which forces every
	 * gameplay-derived field null.
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
	 * Records that the preceding {@link #nextSnapshot(boolean)} snapshot reached the file. The sequence
	 * advances only here, so a refused or failed write leaves the number for the retry. The dirty flag
	 * clears only if nothing changed while that snapshot was being written.
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
	 * Assigns an exported value, marks the state dirty when it actually changed, and returns it. Every
	 * mutator writes through this, so "changed" and "assigned" cannot drift apart.
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
