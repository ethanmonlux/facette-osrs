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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers the telemetry rules that decide what may be exported and when: nulling while logged
 * out, the completeness gate on reporting a live session, vitals normalization, the eleven
 * equipment slots and twenty-eight inventory slots, active prayers, the NPC target, experience
 * seeding versus a real gain, session-local accumulated experience, session reset, identity and
 * sequence behavior, and the shutdown snapshot.
 */
public class TelemetryStateTest
{
	private static final String INSTANCE_ID = "8e5a1c02-3f47-4d6b-9a10-77c2e5b4f831";

	/**
	 * RuneLite {@code Skill} enum positions, spelled out here rather than imported so this class
	 * stays free of RuneLite types. They are what the exported session-gain collection is ordered
	 * by, so the numbers themselves are part of what several tests assert.
	 */
	private static final int ATTACK = 0;

	private static final int COOKING = 7;

	private static final int WOODCUTTING = 8;

	private static final int FISHING = 10;

	private static final int MINING = 14;

	private static final int HERBLORE = 15;

	private static final int AGILITY = 16;

	private static final int THIEVING = 17;

	private static final int SLAYER = 18;

	private static final int SAILING = 23;

	/** Wall-clock milliseconds. Drives exported timestamps only. */
	private long now;

	/**
	 * Monotonic elapsed nanoseconds. Drives cadence only, and is moved independently of
	 * {@link #now} so a test can prove one cannot influence the other.
	 */
	private long elapsed;

	private TelemetryState state;

	@Before
	public void setUp()
	{
		now = 1_770_000_000_000L;
		// Deliberately not zero, and deliberately negative, because System.nanoTime has no
		// meaningful origin and is permitted to be negative. Anything that treats an elapsed
		// reading as an absolute quantity rather than a difference fails here.
		elapsed = -4_000_000_000L;
		LongSupplier wallClock = () -> now;
		LongSupplier elapsedClock = () -> elapsed;
		state = new TelemetryState(INSTANCE_ID, wallClock, elapsedClock);
	}

	/** Advances monotonic elapsed time by a millisecond amount, leaving wall time alone. */
	private void elapseMillis(long millis)
	{
		elapsed += millis * 1_000_000L;
	}

	/**
	 * Enters the logged-in game state without sampling anything, which is what the client-state
	 * transition alone does. The session is logged in; no player data has been read yet.
	 */
	private void enterLoggedIn()
	{
		state.updateSession("LOGGED_IN", true);
	}

	/**
	 * A complete live sample, exactly as the plugin performs on each logged-in tick. Tests that care
	 * about one reading override it afterwards; tests about the completeness gate itself use
	 * {@link #enterLoggedIn()}.
	 */
	private void logIn()
	{
		liveSample(state);
	}

	private static void liveSample(TelemetryState target)
	{
		target.updateSession("LOGGED_IN", true);
		target.updateWorld(302);
		target.updateCombatLevel(87);
		target.updateVitals(73, 75, 40, 52, 8_800, 650, 12);
		target.updateCombat(null, Collections.<String>emptyList());
		target.updateTarget(null);
		target.updateEquipment(emptyEquipment());
		target.updateInventory(emptyInventory());
		assertTrue("a complete sample must satisfy the completeness gate",
			target.markPlayerStateComplete());
	}

	private static List<TelemetryItemSlot> emptyEquipment()
	{
		List<TelemetryItemSlot> slots = new ArrayList<>();
		while (slots.size() < TelemetrySnapshot.EQUIPMENT_SLOTS.size())
		{
			slots.add(TelemetryItemSlot.EMPTY);
		}
		return slots;
	}

	private static List<TelemetryItemSlot> emptyInventory()
	{
		List<TelemetryItemSlot> slots = new ArrayList<>();
		while (slots.size() < TelemetryState.INVENTORY_CAPACITY)
		{
			slots.add(TelemetryItemSlot.EMPTY);
		}
		return slots;
	}

	/** An inventory whose first {@code occupied} slots hold an item and whose rest are empty. */
	private static List<TelemetryItemSlot> inventoryWith(int occupied)
	{
		List<TelemetryItemSlot> slots = new ArrayList<>();
		for (int slot = 0; slot < occupied; slot++)
		{
			slots.add(TelemetryItemSlot.of(3000 + slot, 1, "Sample item " + slot));
		}
		while (slots.size() < TelemetryState.INVENTORY_CAPACITY)
		{
			slots.add(TelemetryItemSlot.EMPTY);
		}
		return slots;
	}

	/**
	 * Takes the first occurrence, which is the top-level one for every key this class asks about,
	 * because the session and vitals objects come before the per-slot and per-skill entries that
	 * reuse some of those names. Exact whole-document equality is pinned in
	 * {@link TelemetrySnapshotTest} instead.
	 */
	private static String value(String json, String key)
	{
		int at = json.indexOf("\"" + key + "\":");
		assertTrue("missing key " + key + " in " + json, at >= 0);
		int start = at + key.length() + 3;
		int end = start;
		int depth = 0;
		while (end < json.length())
		{
			char c = json.charAt(end);
			if (c == '[' || c == '{')
			{
				depth++;
			}
			else if (c == ']' || c == '}')
			{
				if (depth == 0)
				{
					break;
				}
				depth--;
			}
			else if ((c == ',') && depth == 0)
			{
				break;
			}
			end++;
		}
		return json.substring(start, end);
	}

	private String snapshot()
	{
		return state.nextSnapshot(true).toJson();
	}

	// --- logged-out nulling and the completeness gate -----------------------------------------

	@Test
	public void loggedOutSnapshotNullsEveryPlayerDerivedField()
	{
		state.updateSession("LOGIN_SCREEN", false);
		String json = snapshot();

		assertEquals("false", value(json, "loggedIn"));
		assertEquals("\"LOGIN_SCREEN\"", value(json, "gameState"));
		for (String key : new String[]{"world", "combatLevel", "trackingStartedAt",
			"hitpointsCurrent", "hitpointsBase", "prayerCurrent", "prayerBase", "runEnergyPercent",
			"specialAttackPercent", "weightKg", "attackStyle", "activePrayers", "target",
			"slots", "usedSlots", "freeSlots", "lastSkill", "lastDelta", "lastChangedAt", "skills"})
		{
			assertEquals(key + " should be null while logged out", "null", value(json, key));
		}
	}

	/**
	 * The one document that must never exist: a session reported as carrying live player data
	 * whose collections have never been read. Entering the logged-in state is not the same event
	 * as having sampled it, and the exported {@code loggedIn} flag reports the second.
	 */
	@Test
	public void aSessionIsNotReportedAsLiveUntilAFullSampleHasPopulatedIt()
	{
		enterLoggedIn();
		assertFalse("nothing has been read yet", state.markPlayerStateComplete());

		String json = snapshot();
		assertEquals("the game state is still reported honestly", "\"LOGGED_IN\"",
			value(json, "gameState"));
		assertEquals("but the player block is not claimed to be valid", "false",
			value(json, "loggedIn"));
		assertEquals("and no empty collection is invented", "null", value(json, "activePrayers"));
		assertEquals("null", value(json, "slots"));
		assertEquals("null", value(json, "usedSlots"));

		logIn();
		String live = snapshot();
		assertEquals("true", value(live, "loggedIn"));
		assertEquals("[]", value(live, "activePrayers"));
		assertEquals("0", value(live, "usedSlots"));
		assertEquals("28", value(live, "freeSlots"));
	}

	@Test
	public void theCompletenessGateRefusesUntilEveryRequiredReadingIsPresent()
	{
		enterLoggedIn();
		assertFalse(state.markPlayerStateComplete());
		state.updateWorld(302);
		assertFalse(state.markPlayerStateComplete());
		state.updateCombatLevel(87);
		assertFalse(state.markPlayerStateComplete());
		state.updateVitals(73, 75, 40, 52, 8_800, 650, 12);
		assertFalse("prayers, equipment, and inventory are still unread",
			state.markPlayerStateComplete());
		state.updateCombat(null, Collections.<String>emptyList());
		assertFalse(state.markPlayerStateComplete());
		state.updateEquipment(emptyEquipment());
		assertFalse(state.markPlayerStateComplete());
		state.updateInventory(emptyInventory());
		assertTrue("and now everything required has been read", state.markPlayerStateComplete());
	}

	@Test
	public void theCompletenessGateIsRefusedOutrightWhileLoggedOut()
	{
		state.updateSession("LOGIN_SCREEN", false);
		assertFalse(state.markPlayerStateComplete());
		assertEquals("false", value(snapshot(), "loggedIn"));
	}

	@Test
	public void loggingOutDiscardsPreviousPlayerValuesRatherThanRetainingThem()
	{
		logIn();
		state.updateInventory(inventoryWith(12));
		state.updateTarget(TelemetryTarget.npc(4001, "Sample dummy", 21, 18, 30, false));
		state.observeXp("WOODCUTTING", WOODCUTTING, 100_000);
		state.observeXp("WOODCUTTING", WOODCUTTING, 100_065);
		assertTrue(snapshot().contains("\"world\":302"));

		state.updateSession("LOGIN_SCREEN", false);
		String loggedOut = snapshot();
		for (String key : new String[]{"world", "combatLevel", "hitpointsCurrent",
			"specialAttackPercent", "weightKg", "activePrayers", "target", "slots", "usedSlots",
			"lastSkill", "skills"})
		{
			assertEquals(key, "null", value(loggedOut, key));
		}

		// Entering the logged-in state again must not resurrect the stale readings; only a fresh
		// sample does, and until then the session is not reported as live at all.
		enterLoggedIn();
		String reEntered = snapshot();
		assertEquals("false", value(reEntered, "loggedIn"));
		assertEquals("null", value(reEntered, "world"));
		assertEquals("null", value(reEntered, "usedSlots"));
	}

	@Test
	public void aSessionEndingTransitionIsAppliedAtomically()
	{
		logIn();
		state.updateInventory(inventoryWith(12));
		state.updateTarget(TelemetryTarget.npc(4001, "Sample dummy", 21, 18, 30, false));
		state.observeXp("FISHING", FISHING, 10_000);
		state.observeXp("FISHING", FISHING, 10_050);

		state.updateSession("LOGIN_SCREEN", false, true);

		// There is no observable point between "baselines cleared" and "logged out": the very
		// next snapshot is already fully logged out with nothing player-derived left.
		String json = snapshot();
		assertEquals("false", value(json, "loggedIn"));
		assertEquals("\"LOGIN_SCREEN\"", value(json, "gameState"));
		for (String key : new String[]{"world", "combatLevel", "trackingStartedAt",
			"hitpointsCurrent", "prayerCurrent", "runEnergyPercent", "specialAttackPercent",
			"weightKg", "attackStyle", "activePrayers", "target", "slots", "usedSlots", "freeSlots",
			"lastSkill", "lastDelta", "lastChangedAt", "skills"})
		{
			assertEquals(key + " should be null the moment the session ends", "null",
				value(json, key));
		}

		// And the baselines really were discarded by that same call.
		logIn();
		assertFalse("the next login's first reading must seed, not report a gain",
			state.observeXp("FISHING", FISHING, 50_000));
	}

	// --- vitals ---------------------------------------------------------------------------------

	@Test
	public void runEnergyIsNormalizedFromHundredthsOfAPercentIntoZeroToOneHundred()
	{
		logIn();
		assertEquals("100", energyPercentFor(10_000));
		assertEquals("88", energyPercentFor(8_800));
		assertEquals("55", energyPercentFor(5_599));
		assertEquals("0", energyPercentFor(0));
		assertEquals("0", energyPercentFor(99));
		// Out-of-contract readings are clamped rather than exported as-is.
		assertEquals("100", energyPercentFor(12_345));
		assertEquals("0", energyPercentFor(-50));
	}

	private String energyPercentFor(int rawEnergy)
	{
		state.updateVitals(10, 10, 1, 1, rawEnergy, 1_000, 0);
		return value(snapshot(), "runEnergyPercent");
	}

	@Test
	public void specialAttackIsNormalizedFromTenthsOfAPercentIntoZeroToOneHundred()
	{
		logIn();
		assertEquals("100", specialPercentFor(1_000));
		assertEquals("65", specialPercentFor(650));
		assertEquals("50", specialPercentFor(509));
		assertEquals("0", specialPercentFor(0));
		assertEquals("0", specialPercentFor(9));
		// Out-of-contract readings are clamped into the declared range rather than exported raw.
		assertEquals("100", specialPercentFor(5_000));
		assertEquals("0", specialPercentFor(-40));
		assertEquals("0", specialPercentFor(Integer.MIN_VALUE));
		assertEquals("100", specialPercentFor(Integer.MAX_VALUE));
	}

	private String specialPercentFor(int rawSpecial)
	{
		state.updateVitals(10, 10, 1, 1, 5_000, rawSpecial, 0);
		return value(snapshot(), "specialAttackPercent");
	}

	@Test
	public void weightIsCarriedWhenFiniteAndBoundedAndIsUnavailableOtherwise()
	{
		logIn();
		assertEquals("0", weightFor(0));
		assertEquals("a negative load from weight-reducing equipment is real", "-22",
			weightFor(-22));
		assertEquals("1250", weightFor(1_250));
		assertEquals("null", weightFor(Integer.MAX_VALUE));
		assertEquals("null", weightFor(Integer.MIN_VALUE));
		assertEquals("null", weightFor(-1_001));
		assertEquals("-1000", weightFor(-1_000));
		assertEquals("1000000", weightFor(1_000_000));
		assertEquals("null", weightFor(1_000_001));
	}

	private String weightFor(int rawWeight)
	{
		state.updateVitals(10, 10, 1, 1, 5_000, 500, rawWeight);
		return value(snapshot(), "weightKg");
	}

	@Test
	public void aNonPositiveCombatLevelIsUnavailableRatherThanZero()
	{
		logIn();
		state.updateCombatLevel(0);
		assertEquals("null", value(snapshot(), "combatLevel"));
		state.updateCombatLevel(-3);
		assertEquals("null", value(snapshot(), "combatLevel"));
		state.updateCombatLevel(126);
		assertEquals("126", value(snapshot(), "combatLevel"));
	}

	@Test
	public void worldAndVitalsAreNotRecordedWhileLoggedOut()
	{
		state.updateSession("LOGIN_SCREEN", false);
		state.updateWorld(302);
		state.updateCombatLevel(87);
		state.updateVitals(73, 75, 40, 52, 8_800, 650, 12);
		state.updateCombat("accurate", Arrays.asList("piety"));
		state.updateTarget(TelemetryTarget.npc(4001, "Sample dummy", 21, 18, 30, false));
		assertFalse(state.updateEquipment(emptyEquipment()));
		assertFalse(state.updateInventory(inventoryWith(12)));

		String json = snapshot();
		assertEquals("null", value(json, "world"));
		assertEquals("null", value(json, "combatLevel"));
		assertEquals("null", value(json, "hitpointsCurrent"));
		assertEquals("null", value(json, "attackStyle"));
		assertEquals("null", value(json, "target"));
		assertEquals("null", value(json, "usedSlots"));
	}

	// --- combat: attack style, prayers, target --------------------------------------------------

	@Test
	public void anAttackStyleReadingIsCarriedAndItsAbsenceIsNotInvented()
	{
		logIn();
		assertEquals("no reading is a legitimate steady state", "null",
			value(snapshot(), "attackStyle"));

		state.updateCombat("controlled", Collections.<String>emptyList());
		assertEquals("\"controlled\"", value(snapshot(), "attackStyle"));

		state.updateCombat(null, Collections.<String>emptyList());
		assertEquals("and it goes back to unavailable rather than sticking", "null",
			value(snapshot(), "attackStyle"));
	}

	@Test
	public void activePrayersAreAnEmptyArrayWhenNoneIsActiveAndNullOnlyWhenNotLive()
	{
		logIn();
		assertEquals("[]", value(snapshot(), "activePrayers"));

		state.updateCombat(null, Arrays.asList("protect_from_melee", "piety"));
		assertEquals("[\"protect_from_melee\",\"piety\"]", value(snapshot(), "activePrayers"));

		state.updateSession("LOGIN_SCREEN", false);
		assertEquals("null", value(snapshot(), "activePrayers"));
	}

	@Test
	public void activePrayersKeepTheCallersOrderAndCannotContainADuplicate()
	{
		logIn();
		state.updateCombat(null, Arrays.asList("thick_skin", "piety", "thick_skin", "augury"));
		assertEquals("[\"thick_skin\",\"piety\",\"augury\"]", value(snapshot(), "activePrayers"));

		// The same set in a different order is a different document, so ordering has to come from
		// the caller's enumeration rather than from anything incidental.
		state.updateCombat(null, Arrays.asList("augury", "piety", "thick_skin"));
		assertEquals("[\"augury\",\"piety\",\"thick_skin\"]", value(snapshot(), "activePrayers"));
	}

	@Test
	public void aNullPrayerCollectionIsRefusedRatherThanTreatedAsNoneActive()
	{
		logIn();
		state.updateCombat(null, Arrays.asList("piety"));
		assertFalse("not read is not the same claim as none active",
			state.updateCombat("accurate", null));
		assertEquals("the last real reading stands", "[\"piety\"]",
			value(snapshot(), "activePrayers"));
		assertEquals("and nothing else was applied either", "null", value(snapshot(), "attackStyle"));
	}

	@Test
	public void anNpcTargetIsCarriedAndClearsTheMomentTheInteractionEnds()
	{
		logIn();
		state.updateTarget(TelemetryTarget.npc(4001, "Sample dummy", 21, 18, 30, false));
		String json = snapshot();
		assertTrue(json, json.contains("\"target\":{\"kind\":\"npc\",\"id\":4001,"
			+ "\"name\":\"Sample dummy\",\"combatLevel\":21,\"healthRatio\":18,"
			+ "\"healthScale\":30,\"dead\":false}"));

		state.updateTarget(null);
		assertEquals("the target must clear immediately, not linger", "null",
			value(snapshot(), "target"));
	}

	@Test
	public void anNpcTargetClearsOnLogoutAndDoesNotSurviveIntoTheNextSample()
	{
		logIn();
		state.updateTarget(TelemetryTarget.npc(4001, "Sample dummy", 21, 18, 30, false));
		state.updateSession("LOGIN_SCREEN", false);
		assertEquals("null", value(snapshot(), "target"));

		logIn();
		assertEquals("a fresh sample starts with no target", "null", value(snapshot(), "target"));
	}

	@Test
	public void aDeadTargetIsReportedAsDeadRatherThanRemoved()
	{
		logIn();
		state.updateTarget(TelemetryTarget.npc(4001, "Sample dummy", 21, 0, 30, true));
		String json = snapshot();
		assertTrue(json, json.contains("\"healthRatio\":0,\"healthScale\":30,\"dead\":true"));
	}

	// --- equipment -------------------------------------------------------------------------------

	@Test
	public void equipmentCarriesTheElevenNamedSlotsInOrder()
	{
		logIn();
		List<TelemetryItemSlot> slots = new ArrayList<>(emptyEquipment());
		slots.set(0, TelemetryItemSlot.of(1101, 1, "Sample helm"));
		slots.set(3, TelemetryItemSlot.of(1104, 1, "Sample blade"));
		slots.set(10, TelemetryItemSlot.of(1111, 350, "Sample bolts"));
		assertTrue(state.updateEquipment(slots));

		String json = snapshot();
		int previous = -1;
		for (String slotName : TelemetrySnapshot.EQUIPMENT_SLOTS)
		{
			int at = json.indexOf("{\"slot\":\"" + slotName + "\",");
			assertTrue("equipment slot " + slotName + " is missing", at > 0);
			assertTrue("equipment slot " + slotName + " is out of order", at > previous);
			previous = at;
		}
		assertTrue(json, json.contains(
			"{\"slot\":\"head\",\"itemId\":1101,\"quantity\":1,\"name\":\"Sample helm\"}"));
		assertTrue("an empty slot nulls all three values", json.contains(
			"{\"slot\":\"cape\",\"itemId\":null,\"quantity\":null,\"name\":null}"));
		assertTrue("a stack is reported with its quantity", json.contains(
			"{\"slot\":\"ammo\",\"itemId\":1111,\"quantity\":350,\"name\":\"Sample bolts\"}"));
	}

	@Test
	public void anEquipmentCollectionOfTheWrongSizeIsRefusedAndLeavesTheLastGoodOneInPlace()
	{
		logIn();
		List<TelemetryItemSlot> good = new ArrayList<>(emptyEquipment());
		good.set(0, TelemetryItemSlot.of(1101, 1, "Sample helm"));
		assertTrue(state.updateEquipment(good));

		assertFalse(state.updateEquipment(null));
		assertFalse(state.updateEquipment(Collections.singletonList(TelemetryItemSlot.EMPTY)));
		assertFalse(state.updateEquipment(new ArrayList<>(emptyInventory())));

		assertTrue("the last good reading must survive a refused one",
			snapshot().contains("\"itemId\":1101"));
	}

	// --- inventory --------------------------------------------------------------------------------

	@Test
	public void inventoryCarriesAllTwentyEightSlotsInAscendingOrder()
	{
		logIn();
		assertTrue(state.updateInventory(inventoryWith(12)));

		String json = snapshot();
		assertEquals("12", value(json, "usedSlots"));
		assertEquals("16", value(json, "freeSlots"));
		int previous = -1;
		for (int slot = 0; slot < TelemetryState.INVENTORY_CAPACITY; slot++)
		{
			int at = json.indexOf("{\"slot\":" + slot + ",\"itemId\":");
			assertTrue("inventory slot " + slot + " is missing", at > 0);
			assertTrue("inventory slot " + slot + " is out of order", at > previous);
			previous = at;
		}
		assertTrue(json, json.contains(
			"{\"slot\":0,\"itemId\":3000,\"quantity\":1,\"name\":\"Sample item 0\"}"));
		assertTrue(json, json.contains(
			"{\"slot\":27,\"itemId\":null,\"quantity\":null,\"name\":null}"));
	}

	@Test
	public void inventoryOccupancyCountsSlotsAndAlwaysSumsToTwentyEight()
	{
		logIn();
		for (int occupied = 0; occupied <= TelemetryState.INVENTORY_CAPACITY; occupied++)
		{
			assertTrue(state.updateInventory(inventoryWith(occupied)));
			String json = snapshot();
			assertEquals(String.valueOf(occupied), value(json, "usedSlots"));
			assertEquals(String.valueOf(TelemetryState.INVENTORY_CAPACITY - occupied),
				value(json, "freeSlots"));
		}
	}

	/**
	 * Item identity zero is a real item, so a slot holding one is occupied and must be counted.
	 * Treating zero as absent undercounted {@code usedSlots} and overcounted {@code freeSlots}.
	 */
	@Test
	public void anItemOfIdentityZeroCountsTowardsOccupancy()
	{
		logIn();
		List<TelemetryItemSlot> slots = new ArrayList<>(emptyInventory());
		slots.set(0, TelemetryItemSlot.of(0, 1, "Sample remains"));
		slots.set(1, TelemetryItemSlot.of(2001, 1, "Sample pickaxe"));
		assertTrue(state.updateInventory(slots));

		String json = snapshot();
		assertEquals("both slots are occupied", "2", value(json, "usedSlots"));
		assertEquals("26", value(json, "freeSlots"));
		assertTrue(json, json.contains(
			"{\"slot\":0,\"itemId\":0,\"quantity\":1,\"name\":\"Sample remains\"}"));

		// The negative identity beside it is still the empty representation.
		slots.set(1, TelemetryItemSlot.of(-1, 0, "ignored"));
		assertTrue(state.updateInventory(slots));
		json = snapshot();
		assertEquals("1", value(json, "usedSlots"));
		assertEquals("27", value(json, "freeSlots"));
		assertTrue(json, json.contains(
			"{\"slot\":1,\"itemId\":null,\"quantity\":null,\"name\":null}"));
	}

	@Test
	public void aStackDoesNotChangeOccupiedSlotCounting()
	{
		logIn();
		List<TelemetryItemSlot> slots = new ArrayList<>(emptyInventory());
		slots.set(0, TelemetryItemSlot.of(995, 2_000_000_000, "Sample coin pile"));
		assertTrue(state.updateInventory(slots));

		String json = snapshot();
		assertEquals("one slot, whatever the stack size", "1", value(json, "usedSlots"));
		assertEquals("27", value(json, "freeSlots"));
		assertTrue(json, json.contains("\"quantity\":2000000000"));
	}

	@Test
	public void anInventoryCollectionOfTheWrongSizeIsRefusedAndLeavesTheLastGoodOneInPlace()
	{
		logIn();
		assertTrue(state.updateInventory(inventoryWith(5)));
		assertFalse(state.updateInventory(null));
		assertFalse(state.updateInventory(Collections.singletonList(TelemetryItemSlot.EMPTY)));
		assertFalse(state.updateInventory(new ArrayList<>(emptyEquipment())));
		assertEquals("5", value(snapshot(), "usedSlots"));
	}

	// --- experience: baselines and the latest gain -------------------------------------------------

	@Test
	public void firstExperienceObservationSeedsWithoutReportingAGain()
	{
		logIn();
		assertFalse("the first observation is a baseline, not a gain",
			state.observeXp("WOODCUTTING", WOODCUTTING, 1_234_567));

		String json = snapshot();
		assertEquals("null", value(json, "lastSkill"));
		assertEquals("null", value(json, "lastDelta"));
		assertEquals("null", value(json, "lastChangedAt"));
		assertEquals("and no session total either", "[]", value(json, "skills"));
	}

	@Test
	public void subsequentExperienceIncreaseReportsSkillDeltaAndTime()
	{
		logIn();
		state.observeXp("WOODCUTTING", WOODCUTTING, 1_234_567);
		now = 1_770_000_005_000L;
		assertTrue(state.observeXp("WOODCUTTING", WOODCUTTING, 1_234_632));

		String json = snapshot();
		assertEquals("\"woodcutting\"", value(json, "lastSkill"));
		assertEquals("65", value(json, "lastDelta"));
		assertEquals("1770000005000", value(json, "lastChangedAt"));
		// The total itself is never exported.
		assertFalse(json, json.contains("1234632"));
		assertFalse(json, json.contains("1234567"));
	}

	@Test
	public void nonIncreasingExperienceIsIgnored()
	{
		logIn();
		state.observeXp("MINING", MINING, 500);
		assertFalse(state.observeXp("MINING", MINING, 500));
		assertFalse(state.observeXp("MINING", MINING, 400));
		assertEquals("null", value(snapshot(), "lastSkill"));
		assertEquals("[]", value(snapshot(), "skills"));
	}

	/**
	 * A reading that has not advanced must not move the baseline. If it did, returning to the
	 * true total would look like a gain the size of the dip.
	 */
	@Test
	public void aLowerReadingDoesNotLowerTheBaseline()
	{
		logIn();
		state.observeXp("MINING", MINING, 1_000);
		assertFalse(state.observeXp("MINING", MINING, 400));

		// Back to the total we already had: nothing was gained, so nothing is reported.
		assertFalse("returning to a known total is not a gain",
			state.observeXp("MINING", MINING, 1_000));
		assertEquals("null", value(snapshot(), "lastSkill"));
	}

	/**
	 * The case that makes the one above matter: the client can report a zero total while skill
	 * data is still initializing. A baseline that followed it down would fabricate a gain the
	 * size of the player's entire skill on the next real reading.
	 */
	@Test
	public void aTransientZeroReadingCannotFabricateAWholeSkillGain()
	{
		logIn();
		state.observeXp("WOODCUTTING", WOODCUTTING, 1_234_567);
		assertFalse(state.observeXp("WOODCUTTING", WOODCUTTING, 0));

		assertFalse("the real total returning is not a gain",
			state.observeXp("WOODCUTTING", WOODCUTTING, 1_234_567));
		assertEquals("null", value(snapshot(), "lastSkill"));

		// A genuine gain after the dip is measured from the true baseline, not from zero.
		assertTrue(state.observeXp("WOODCUTTING", WOODCUTTING, 1_234_632));
		assertEquals("65", value(snapshot(), "lastDelta"));
	}

	@Test
	public void aNonSessionEndingTransitionKeepsTheBaselines()
	{
		logIn();
		state.observeXp("FISHING", FISHING, 10_000);

		// A world hop leaves the logged-in state without ending the session.
		state.updateSession("HOPPING", false, false);
		logIn();

		assertTrue("a hop must not re-seed and swallow the next gain",
			state.observeXp("FISHING", FISHING, 10_040));
		assertEquals("40", value(snapshot(), "lastDelta"));
	}

	@Test
	public void endingASessionClearsBaselinesSoALaterLoginCannotInheritADelta()
	{
		logIn();
		state.observeXp("FISHING", FISHING, 10_000);
		state.observeXp("FISHING", FISHING, 10_050);
		assertEquals("\"fishing\"", value(snapshot(), "lastSkill"));

		state.updateSession("LOGIN_SCREEN", false, true);
		logIn();

		// The next login's very first observation is a fresh baseline, not a 40k gain.
		assertFalse(state.observeXp("FISHING", FISHING, 50_000));
		assertEquals("null", value(snapshot(), "lastSkill"));

		assertTrue(state.observeXp("FISHING", FISHING, 50_030));
		assertEquals("30", value(snapshot(), "lastDelta"));
	}

	// --- session-local accumulated experience ----------------------------------------------------

	@Test
	public void sessionGainsAccumulatePerSkillAndCarryTheirLatestDeltaAndTime()
	{
		logIn();
		state.observeXp("WOODCUTTING", WOODCUTTING, 1_000);
		now = 1_770_000_002_000L;
		state.observeXp("WOODCUTTING", WOODCUTTING, 1_065);
		now = 1_770_000_004_000L;
		state.observeXp("WOODCUTTING", WOODCUTTING, 1_130);

		String json = snapshot();
		assertEquals("[{\"skill\":\"woodcutting\",\"gained\":130,\"lastDelta\":65,"
			+ "\"lastChangedAt\":1770000004000}]", value(json, "skills"));
		assertEquals("the cumulative figure is a session difference, never a total", -1,
			json.indexOf("1130"));
	}

	@Test
	public void sessionGainsCoverEveryAdvancedSkillOrderedBySkillEnumPosition()
	{
		logIn();
		// Recorded in an order that is neither enum order nor alphabetical, so only the enum
		// position can produce the expected array.
		state.observeXp("SLAYER", SLAYER, 100);
		state.observeXp("ATTACK", ATTACK, 500);
		state.observeXp("WOODCUTTING", WOODCUTTING, 1_000);
		now = 1_770_000_001_000L;
		state.observeXp("SLAYER", SLAYER, 130);
		now = 1_770_000_002_000L;
		state.observeXp("ATTACK", ATTACK, 540);
		now = 1_770_000_003_000L;
		state.observeXp("WOODCUTTING", WOODCUTTING, 1_065);

		assertEquals("[{\"skill\":\"attack\",\"gained\":40,\"lastDelta\":40,"
			+ "\"lastChangedAt\":1770000002000},"
			+ "{\"skill\":\"woodcutting\",\"gained\":65,\"lastDelta\":65,"
			+ "\"lastChangedAt\":1770000003000},"
			+ "{\"skill\":\"slayer\",\"gained\":30,\"lastDelta\":30,"
			+ "\"lastChangedAt\":1770000001000}]", value(snapshot(), "skills"));
	}

	@Test
	public void aSkillWithNoGainNeverAppearsInTheSessionCollection()
	{
		logIn();
		// Seeded but never advanced.
		state.seedXpBaseline("HERBLORE", HERBLORE, 50_000);
		state.seedXpBaseline("SAILING", SAILING, 0);
		state.observeXp("COOKING", COOKING, 800);
		assertEquals("[]", value(snapshot(), "skills"));

		assertTrue(state.observeXp("COOKING", COOKING, 850));
		assertEquals("[{\"skill\":\"cooking\",\"gained\":50,\"lastDelta\":50,"
			+ "\"lastChangedAt\":1770000000000}]", value(snapshot(), "skills"));
	}

	@Test
	public void noAggregatePseudoSkillCanEnterTheSessionCollection()
	{
		logIn();
		assertFalse("an aggregate reading establishes nothing",
			state.observeXp("OVERALL", 99, 1_000_000));
		assertFalse("and cannot become a gain either",
			state.observeXp("OVERALL", 99, 1_500_000));
		assertFalse("whatever the case", state.observeXp("overall", 99, 2_000_000));

		String json = snapshot();
		assertEquals("the aggregate sentinel must not reach the session collection", "[]",
			value(json, "skills"));
		assertEquals("nor the latest-gain triple", "null", value(json, "lastSkill"));
	}

	@Test
	public void endingASessionDiscardsAccumulatedSessionGains()
	{
		logIn();
		state.observeXp("MINING", MINING, 5_000);
		state.observeXp("MINING", MINING, 5_120);
		assertTrue(value(snapshot(), "skills").contains("\"gained\":120"));

		state.updateSession("LOGIN_SCREEN", false, true);
		logIn();
		assertEquals("a new session starts from nothing", "[]", value(snapshot(), "skills"));
	}

	@Test
	public void aWorldHopKeepsAccumulatedSessionGains()
	{
		logIn();
		state.observeXp("MINING", MINING, 5_000);
		now = 1_770_000_002_000L;
		state.observeXp("MINING", MINING, 5_120);

		state.updateSession("LOADING", false);
		assertEquals("nothing player-derived is exported mid-hop", "null",
			value(snapshot(), "skills"));

		logIn();
		assertEquals("the session's own total survives the hop",
			"[{\"skill\":\"mining\",\"gained\":120,\"lastDelta\":120,"
				+ "\"lastChangedAt\":1770000002000}]", value(snapshot(), "skills"));
	}

	@Test
	public void aRetainedStartupSpanBecomesThatSkillsSessionTotal()
	{
		now = 1_770_000_001_000L;
		state.recordPreInitialXp("THIEVING", 1_046);
		now = 1_770_000_002_000L;
		state.recordPreInitialXp("THIEVING", 1_092);

		logIn();
		now = 1_770_000_009_000L;
		assertTrue(state.seedXpBaseline("THIEVING", THIEVING, 1_092));

		assertEquals("[{\"skill\":\"thieving\",\"gained\":46,\"lastDelta\":46,"
			+ "\"lastChangedAt\":1770000002000}]", value(snapshot(), "skills"));

		// And a later live gain adds to the same entry rather than replacing it.
		now = 1_770_000_010_000L;
		assertTrue(state.observeXp("THIEVING", THIEVING, 1_100));
		assertEquals("[{\"skill\":\"thieving\",\"gained\":54,\"lastDelta\":8,"
			+ "\"lastChangedAt\":1770000010000}]", value(snapshot(), "skills"));
	}

	/**
	 * A retained span belongs to its own skill's total whether or not it also wins the
	 * latest-gain triple, so the two decisions must be independent.
	 */
	@Test
	public void aRetainedSpanCountsTowardsItsSkillEvenWhenANewerLiveGainOutranksIt()
	{
		now = 1_770_000_001_000L;
		state.recordPreInitialXp("MINING", 5_000);
		now = 1_770_000_002_000L;
		state.recordPreInitialXp("MINING", 5_060);

		logIn();
		state.observeXp("COOKING", COOKING, 800);
		now = 1_770_000_020_000L;
		assertTrue(state.observeXp("COOKING", COOKING, 900));

		assertTrue(state.seedXpBaseline("MINING", MINING, 5_060));

		String json = snapshot();
		assertEquals("the newer live gain still owns the latest-gain triple", "\"cooking\"",
			value(json, "lastSkill"));
		assertEquals("[{\"skill\":\"cooking\",\"gained\":100,\"lastDelta\":100,"
			+ "\"lastChangedAt\":1770000020000},"
			+ "{\"skill\":\"mining\",\"gained\":60,\"lastDelta\":60,"
			+ "\"lastChangedAt\":1770000002000}]", value(json, "skills"));
	}

	// --- startup baseline seeding -------------------------------------------------------------------

	/**
	 * Enabling the plugin mid-session leaves the baselines empty, so without seeding the next real
	 * gain would be consumed as a first observation and never exported.
	 */
	@Test
	public void seedingMidSessionMakesTheNextGainReportable()
	{
		logIn();
		assertTrue("seeding establishes a baseline",
			state.seedXpBaseline("WOODCUTTING", WOODCUTTING, 1_234_567));

		// Seeding itself reports nothing.
		String afterSeed = snapshot();
		assertEquals("null", value(afterSeed, "lastSkill"));
		assertEquals("null", value(afterSeed, "lastDelta"));
		assertEquals("null", value(afterSeed, "lastChangedAt"));
		assertEquals("[]", value(afterSeed, "skills"));

		// The very first gain after enabling is now exported, measured from the seeded total.
		now = 1_770_000_005_000L;
		assertTrue(state.observeXp("WOODCUTTING", WOODCUTTING, 1_234_632));

		String json = snapshot();
		assertEquals("\"woodcutting\"", value(json, "lastSkill"));
		assertEquals("65", value(json, "lastDelta"));
		assertEquals("1770000005000", value(json, "lastChangedAt"));
	}

	@Test
	public void withoutSeedingTheFirstGainIsStillConsumedAsTheBaseline()
	{
		// The unseeded counterpart of the test above, so the contrast between the two is explicit.
		logIn();
		assertFalse(state.observeXp("WOODCUTTING", WOODCUTTING, 1_234_632));
		assertEquals("null", value(snapshot(), "lastSkill"));
	}

	@Test
	public void seedingNeverOverwritesAnExistingBaseline()
	{
		logIn();
		state.observeXp("MINING", MINING, 5_000);

		// A later seed must not move a baseline a real observation already established, in either
		// direction.
		assertFalse("seeding must not replace an existing baseline",
			state.seedXpBaseline("MINING", MINING, 1));
		assertFalse(state.seedXpBaseline("MINING", MINING, 9_999_999));

		assertTrue("the original baseline still governs", state.observeXp("MINING", MINING, 5_040));
		assertEquals("40", value(snapshot(), "lastDelta"));
	}

	@Test
	public void seedingIsIdempotentForTheSameSkill()
	{
		logIn();
		assertTrue(state.seedXpBaseline("FISHING", FISHING, 400));
		assertFalse("a second seed does nothing", state.seedXpBaseline("FISHING", FISHING, 400));
		assertFalse(state.seedXpBaseline("FISHING", FISHING, 100));

		assertTrue(state.observeXp("FISHING", FISHING, 430));
		assertEquals("30", value(snapshot(), "lastDelta"));
	}

	@Test
	public void zeroIsSeededBecauseAnUntrainedSkillLegitimatelyHasNone()
	{
		logIn();
		assertTrue("zero is a real total for an untrained skill",
			state.seedXpBaseline("SAILING", SAILING, 0));
		assertEquals("null", value(snapshot(), "lastSkill"));

		// And the first experience in that skill is then reported in full.
		assertTrue(state.observeXp("SAILING", SAILING, 120));
		String json = snapshot();
		assertEquals("\"sailing\"", value(json, "lastSkill"));
		assertEquals("120", value(json, "lastDelta"));
	}

	@Test
	public void aNegativeTotalIsNeverSeeded()
	{
		logIn();
		assertFalse(state.seedXpBaseline("MINING", MINING, -1));

		// Nothing was recorded, so the next reading still behaves as a first observation rather
		// than reporting a gain measured from a nonsense baseline.
		assertFalse(state.observeXp("MINING", MINING, 500));
		assertEquals("null", value(snapshot(), "lastSkill"));
	}

	@Test
	public void seededBaselinesAreStillProtectedFromTransientLowReadings()
	{
		logIn();
		state.seedXpBaseline("WOODCUTTING", WOODCUTTING, 1_234_567);

		assertFalse(state.observeXp("WOODCUTTING", WOODCUTTING, 0));
		assertFalse("returning to the seeded total is not a gain",
			state.observeXp("WOODCUTTING", WOODCUTTING, 1_234_567));
		assertEquals("null", value(snapshot(), "lastSkill"));

		assertTrue(state.observeXp("WOODCUTTING", WOODCUTTING, 1_234_632));
		assertEquals("65", value(snapshot(), "lastDelta"));
	}

	@Test
	public void nothingIsSeededWhileLoggedOut()
	{
		state.updateSession("LOGIN_SCREEN", false);
		assertFalse(state.seedXpBaseline("WOODCUTTING", WOODCUTTING, 1_234_567));

		// Confirmed by behavior, not just the return value: after logging in, the first reading is
		// still a first observation because no baseline was stored.
		logIn();
		assertFalse(state.observeXp("WOODCUTTING", WOODCUTTING, 1_234_567));
	}

	@Test
	public void invalidAndSentinelSkillEntriesAreSkipped()
	{
		logIn();
		assertFalse("a null skill name is refused", state.seedXpBaseline(null, 0, 100));
		assertFalse("the OVERALL sentinel is refused",
			state.seedXpBaseline("OVERALL", 99, 12_345_678));
		assertFalse("case does not matter", state.seedXpBaseline("overall", 99, 12_345_678));

		// No baseline was stored under any of them.
		assertFalse(state.observeXp("OVERALL", 99, 12_345_678));
		assertEquals("null", value(snapshot(), "lastSkill"));
	}

	@Test
	public void endingASessionClearsSeededBaselines()
	{
		logIn();
		state.seedXpBaseline("FISHING", FISHING, 10_000);

		state.updateSession("LOGIN_SCREEN", false, true);
		logIn();

		assertFalse("the next login re-seeds rather than inheriting",
			state.observeXp("FISHING", FISHING, 50_000));
		assertEquals("null", value(snapshot(), "lastSkill"));
	}

	@Test
	public void aWorldHopPreservesSeededBaselines()
	{
		logIn();
		state.seedXpBaseline("FISHING", FISHING, 10_000);

		state.updateSession("HOPPING", false, false);
		logIn();

		assertTrue("a hop must not swallow the next gain",
			state.observeXp("FISHING", FISHING, 10_040));
		assertEquals("40", value(snapshot(), "lastDelta"));
	}

	@Test
	public void aFreshRunReseedsAndReportsTheNextGainCorrectly()
	{
		// Disable/re-enable while logged in: the plugin builds a new TelemetryState, so the new
		// run starts with no baselines and seeds from the client's current totals.
		logIn();
		state.seedXpBaseline("COOKING", COOKING, 800);
		state.observeXp("COOKING", COOKING, 850);

		TelemetryState reEnabled = new TelemetryState(INSTANCE_ID, () -> now, () -> elapsed);
		liveSample(reEnabled);
		assertEquals("a fresh run starts at sequence zero", 0L, reEnabled.getNextSeq());

		assertTrue(reEnabled.seedXpBaseline("COOKING", COOKING, 850));
		assertTrue("the first gain after re-enabling is reported",
			reEnabled.observeXp("COOKING", COOKING, 875));
		assertEquals("25", value(reEnabled.nextSnapshot(true).toJson(), "lastDelta"));
	}

	@Test
	public void seedingDoesNotByItselfMakeAPublicationDue()
	{
		logIn();
		state.nextSnapshot(true);
		state.recordPublished();
		assertFalse(state.isDirty());

		// Baselines are not exported, so seeding must not trigger a publication.
		assertTrue(state.seedXpBaseline("HERBLORE", HERBLORE, 1_000));
		assertFalse("seeding changes nothing exported", state.isDirty());
		assertFalse(state.isPublicationDue(1_500L));
	}

	/**
	 * Startup is deferred onto the client thread, so experience events can land before any
	 * baseline exists. Seeding from the live total alone would absorb every gain earned in that
	 * window, and absorb more the longer startup stayed queued.
	 */
	@Test
	public void experienceEarnedWhileStartupWasQueuedIsExportedRatherThanAbsorbed()
	{
		// Two gains land before initialization: 1000 -> 1046 -> 1092, at distinct times.
		now = 1_770_000_001_000L;
		assertTrue(state.recordPreInitialXp("THIEVING", 1_046));
		now = 1_770_000_002_000L;
		assertFalse("one aggregate entry per skill", state.recordPreInitialXp("THIEVING", 1_092));

		logIn();
		// Startup finally runs, much later, and seeds from the live total.
		now = 1_770_000_009_000L;
		assertTrue(state.seedXpBaseline("THIEVING", THIEVING, 1_092));

		String json = snapshot();
		assertEquals("\"thieving\"", value(json, "lastSkill"));
		assertEquals("the measurable span between the two retained events", "46",
			value(json, "lastDelta"));
		assertEquals("the second event's time, not the seeding time", "1770000002000",
			value(json, "lastChangedAt"));

		// The baseline still ends at the live total, so the next gain measures from the truth.
		assertTrue(state.observeXp("THIEVING", THIEVING, 1_100));
		assertEquals("8", value(snapshot(), "lastDelta"));
	}

	/**
	 * A long, quiet gap between the last retained event and seeding must not move the exported
	 * time at all: the timestamp belongs to the event, not to when seeding happened to run.
	 */
	@Test
	public void aLongStartupDelayDoesNotMoveTheExportedExperienceTime()
	{
		now = 1_770_000_001_000L;
		state.recordPreInitialXp("THIEVING", 1_046);
		now = 1_770_000_002_000L;
		state.recordPreInitialXp("THIEVING", 1_092);

		logIn();
		now = 1_770_000_600_000L;
		assertTrue(state.seedXpBaseline("THIEVING", THIEVING, 1_092));

		assertEquals("1770000002000", value(snapshot(), "lastChangedAt"));
	}

	/**
	 * Three events, so a first-writer-wins timestamp and a seeding-time timestamp are both
	 * distinguishable failures rather than coincidentally equal to the right answer.
	 */
	@Test
	public void threeRetainedEventsTotalTheSpanButReportOnlyTheLastGainAsTheLatest()
	{
		now = 1_770_000_001_000L;
		state.recordPreInitialXp("THIEVING", 1_046);
		now = 1_770_000_002_000L;
		state.recordPreInitialXp("THIEVING", 1_092);
		now = 1_770_000_003_000L;
		state.recordPreInitialXp("THIEVING", 1_150);

		logIn();
		now = 1_770_000_050_000L;
		assertTrue(state.seedXpBaseline("THIEVING", THIEVING, 1_150));

		String json = snapshot();
		assertEquals("the last single gain, 1150 - 1092, not the whole span", "58",
			value(json, "lastDelta"));
		assertEquals("the third event's time, not the first and not the seed", "1770000003000",
			value(json, "lastChangedAt"));
		assertEquals("but the cumulative total still accounts for the whole span, 1150 - 1046",
			"[{\"skill\":\"thieving\",\"gained\":104,\"lastDelta\":58,"
				+ "\"lastChangedAt\":1770000003000}]", value(json, "skills"));
	}

	/**
	 * The boundary between the two quantities: a window holding exactly one measurable gain has a
	 * span equal to its last increment, so separating them must not perturb that case.
	 */
	@Test
	public void aWindowWithOneMeasurableGainReportsTheSameValueForBoth()
	{
		now = 1_770_000_001_000L;
		state.recordPreInitialXp("MINING", 5_000);
		now = 1_770_000_002_000L;
		state.recordPreInitialXp("MINING", 5_060);

		logIn();
		assertTrue(state.seedXpBaseline("MINING", MINING, 5_060));

		String json = snapshot();
		assertEquals("60", value(json, "lastDelta"));
		assertEquals("[{\"skill\":\"mining\",\"gained\":60,\"lastDelta\":60,"
			+ "\"lastChangedAt\":1770000002000}]", value(json, "skills"));
	}

	/**
	 * A live gain following a multi-event startup window takes over the latest-gain fields with
	 * its own size, while the cumulative total keeps accruing across both.
	 */
	@Test
	public void aLiveGainAfterAMultiEventWindowOwnsTheLatestGainFields()
	{
		now = 1_770_000_001_000L;
		state.recordPreInitialXp("THIEVING", 100);
		now = 1_770_000_002_000L;
		state.recordPreInitialXp("THIEVING", 110);
		now = 1_770_000_003_000L;
		state.recordPreInitialXp("THIEVING", 115);

		logIn();
		assertTrue(state.seedXpBaseline("THIEVING", THIEVING, 115));
		assertEquals("the last increment, 115 - 110", "5", value(snapshot(), "lastDelta"));

		now = 1_770_000_010_000L;
		assertTrue(state.observeXp("THIEVING", THIEVING, 142));

		String json = snapshot();
		assertEquals("27", value(json, "lastDelta"));
		assertEquals("[{\"skill\":\"thieving\",\"gained\":42,\"lastDelta\":27,"
			+ "\"lastChangedAt\":1770000010000}]", value(json, "skills"));
	}

	/**
	 * Experience earned between the last retained event and seeding has no event of its own.
	 * Stretching the delta to cover it would mean stamping that part with a time no event
	 * happened at, so only the retained span is reported and the baseline absorbs the rest.
	 */
	@Test
	public void experienceWithNoRetainedEventIsNotGivenAnInventedTime()
	{
		now = 1_770_000_001_000L;
		state.recordPreInitialXp("MINING", 5_000);
		now = 1_770_000_002_000L;
		state.recordPreInitialXp("MINING", 5_040);

		logIn();
		now = 1_770_000_009_000L;
		// The live total is higher than anything retained: more was earned, unobserved.
		assertTrue(state.seedXpBaseline("MINING", MINING, 5_100));

		String json = snapshot();
		assertEquals("only the span two events actually bound", "40", value(json, "lastDelta"));
		assertEquals("1770000002000", value(json, "lastChangedAt"));

		// The unreported remainder is absorbed by the baseline, so it is not counted twice.
		assertTrue(state.observeXp("MINING", MINING, 5_130));
		assertEquals("30", value(snapshot(), "lastDelta"));
	}

	/**
	 * Retained evidence above the total the client now reports means the two disagree. Reporting
	 * a span measured against contradicted evidence would fabricate a gain.
	 */
	@Test
	public void retainedEvidenceAboveTheLiveTotalCannotFabricateADelta()
	{
		now = 1_770_000_001_000L;
		state.recordPreInitialXp("MINING", 5_000);
		now = 1_770_000_002_000L;
		state.recordPreInitialXp("MINING", 9_999);

		logIn();
		assertTrue(state.seedXpBaseline("MINING", MINING, 5_050));

		String json = snapshot();
		assertEquals("no gain is reported from contradicted evidence", "null",
			value(json, "lastSkill"));
		assertEquals("null", value(json, "lastDelta"));
		assertEquals("null", value(json, "lastChangedAt"));
		assertEquals("and no session total either", "[]", value(json, "skills"));

		// The live total governs from here.
		assertTrue(state.observeXp("MINING", MINING, 5_090));
		assertEquals("40", value(snapshot(), "lastDelta"));
	}

	@Test
	public void measurableRetainedExperienceMarksTheStateDirtyExactlyOnce()
	{
		now = 1_770_000_001_000L;
		state.recordPreInitialXp("THIEVING", 1_046);
		now = 1_770_000_002_000L;
		state.recordPreInitialXp("THIEVING", 1_092);

		logIn();
		state.nextSnapshot(true);
		state.recordPublished();
		assertFalse(state.isDirty());

		assertTrue(state.seedXpBaseline("THIEVING", THIEVING, 1_092));
		assertTrue("a measurable retained gain is exported, so it must publish", state.isDirty());

		state.nextSnapshot(true);
		state.recordPublished();
		assertFalse("and it must not keep republishing afterwards", state.isDirty());
	}

	/**
	 * The residual limit, pinned deliberately. Experience events carry a running total and not a
	 * delta, and the pre-gain total cannot be read off the client thread, so the first gain per
	 * skill inside the startup window is unmeasurable however this is arranged. What must not
	 * happen is a fabricated gain in its place.
	 */
	@Test
	public void theFirstGainInsideTheStartupWindowIsUnmeasurableAndIsNotInvented()
	{
		state.recordPreInitialXp("THIEVING", 1_046);
		logIn();
		assertTrue(state.seedXpBaseline("THIEVING", THIEVING, 1_046));

		String json = snapshot();
		assertEquals("no gain is fabricated", "null", value(json, "lastSkill"));
		assertEquals("null", value(json, "lastDelta"));

		// And the baseline is correct, so the next genuine gain is exported in full.
		assertTrue(state.observeXp("THIEVING", THIEVING, 1_092));
		assertEquals("46", value(snapshot(), "lastDelta"));
	}

	@Test
	public void aRetainedTotalNeverLowersOrReplacesAnExistingBaseline()
	{
		logIn();
		state.observeXp("MINING", MINING, 5_000);

		state.recordPreInitialXp("MINING", 1);
		assertFalse(state.seedXpBaseline("MINING", MINING, 5_000));
		assertFalse("the retained total was consumed, not left to act later",
			state.seedXpBaseline("MINING", MINING, 5_000));

		assertTrue("the original baseline still governs", state.observeXp("MINING", MINING, 5_040));
		assertEquals("40", value(snapshot(), "lastDelta"));
	}

	@Test
	public void aRetainedTotalAboveTheLiveTotalIsIgnoredRatherThanReportedAsALoss()
	{
		state.recordPreInitialXp("MINING", 9_999);
		logIn();
		assertTrue(state.seedXpBaseline("MINING", MINING, 5_000));

		String json = snapshot();
		assertEquals("null", value(json, "lastSkill"));
		assertEquals("null", value(json, "lastDelta"));

		assertTrue(state.observeXp("MINING", MINING, 5_040));
		assertEquals("40", value(snapshot(), "lastDelta"));
	}

	@Test
	public void retainedEvidenceIsBoundedPerSkillAndRejectsInvalidEntries()
	{
		now = 1_770_000_001_000L;
		assertTrue("the first observation creates the entry",
			state.recordPreInitialXp("FISHING", 100));
		now = 1_770_000_002_000L;
		assertFalse("a later observation updates that entry rather than adding one",
			state.recordPreInitialXp("FISHING", 200));
		now = 1_770_000_003_000L;
		assertFalse("case does not create a second entry", state.recordPreInitialXp("fishing", 300));
		assertFalse("a null skill name is refused", state.recordPreInitialXp(null, 100));
		assertFalse("the OVERALL sentinel is refused",
			state.recordPreInitialXp("OVERALL", 12_345_678));
		assertFalse("a negative total is refused", state.recordPreInitialXp("MINING", -1));

		logIn();
		now = 1_770_000_020_000L;
		assertTrue(state.seedXpBaseline("FISHING", FISHING, 300));
		String json = snapshot();
		assertEquals("the last single gain, 300 - 200", "100", value(json, "lastDelta"));
		assertEquals("1770000003000", value(json, "lastChangedAt"));
		assertEquals("while the cumulative total spans 100..300",
			"[{\"skill\":\"fishing\",\"gained\":200,\"lastDelta\":100,"
				+ "\"lastChangedAt\":1770000003000}]", value(json, "skills"));
	}

	/**
	 * Equal and lower readings are not events the exported span represents, so neither the totals
	 * nor the timestamp may follow them. A transient zero is the case that matters: a baseline
	 * that followed one down would export the whole skill as a single gain.
	 */
	@Test
	public void equalAndLowerRetainedReadingsMoveNeitherTotalNorTimestamp()
	{
		now = 1_770_000_001_000L;
		state.recordPreInitialXp("MINING", 5_000);
		now = 1_770_000_002_000L;
		state.recordPreInitialXp("MINING", 5_040);

		now = 1_770_000_003_000L;
		state.recordPreInitialXp("MINING", 5_040);
		now = 1_770_000_004_000L;
		state.recordPreInitialXp("MINING", 4_000);
		now = 1_770_000_005_000L;
		state.recordPreInitialXp("MINING", 0);

		logIn();
		now = 1_770_000_030_000L;
		assertTrue(state.seedXpBaseline("MINING", MINING, 5_040));

		String json = snapshot();
		assertEquals("the span is unchanged by the dips", "40", value(json, "lastDelta"));
		assertEquals("the timestamp stayed on the last increase", "1770000002000",
			value(json, "lastChangedAt"));
	}

	/**
	 * The opposite arrival order, which the non-advancing rule alone does not cover: the
	 * transient zero comes first, so it would anchor the span at zero and the real total
	 * would then read as a gain the size of the whole skill.
	 */
	@Test
	public void aLeadingTransientZeroCannotAnchorASpanAndFabricateAWholeSkillGain()
	{
		now = 1_770_000_001_000L;
		assertFalse("a zero must not establish retained evidence",
			state.recordPreInitialXp("WOODCUTTING", 0));
		now = 1_770_000_002_000L;
		assertTrue("the real total establishes it instead",
			state.recordPreInitialXp("WOODCUTTING", 1_234_567));

		logIn();
		assertTrue(state.seedXpBaseline("WOODCUTTING", WOODCUTTING, 1_234_567));

		String json = snapshot();
		assertEquals("no whole-skill gain is fabricated", "null", value(json, "lastSkill"));
		assertEquals("null", value(json, "lastDelta"));

		now = 1_770_000_009_000L;
		assertTrue(state.observeXp("WOODCUTTING", WOODCUTTING, 1_234_632));
		assertEquals("65", value(snapshot(), "lastDelta"));
	}

	/**
	 * Seeding walks every skill in enum order, so several can carry a measurable span in one
	 * pass. The exported latest-gain triple means the most recent gain, so the winner must be
	 * decided by event order rather than by whichever skill the enum happened to visit last.
	 */
	@Test
	public void theNewestRetainedEventWinsRatherThanTheLastSkillSeeded()
	{
		now = 1_770_000_001_000L;
		state.recordPreInitialXp("MINING", 5_000);
		now = 1_770_000_002_000L;
		state.recordPreInitialXp("FISHING", 100);
		now = 1_770_000_003_000L;
		state.recordPreInitialXp("FISHING", 140);
		now = 1_770_000_004_000L;
		state.recordPreInitialXp("MINING", 5_060);

		logIn();
		now = 1_770_000_050_000L;
		assertTrue(state.seedXpBaseline("MINING", MINING, 5_060));
		assertTrue(state.seedXpBaseline("FISHING", FISHING, 140));

		String json = snapshot();
		assertEquals("the most recent gain must win", "\"mining\"", value(json, "lastSkill"));
		assertEquals("60", value(json, "lastDelta"));
		assertEquals("1770000004000", value(json, "lastChangedAt"));
		// Both skills still keep their own session totals: winning the latest-gain triple and
		// accumulating a session total are separate decisions.
		assertEquals("[{\"skill\":\"fishing\",\"gained\":40,\"lastDelta\":40,"
			+ "\"lastChangedAt\":1770000003000},"
			+ "{\"skill\":\"mining\",\"gained\":60,\"lastDelta\":60,"
			+ "\"lastChangedAt\":1770000004000}]", value(json, "skills"));
	}

	@Test
	public void theNewestRetainedEventStillWinsWhenSeededInTheOtherOrder()
	{
		now = 1_770_000_001_000L;
		state.recordPreInitialXp("MINING", 5_000);
		now = 1_770_000_002_000L;
		state.recordPreInitialXp("FISHING", 100);
		now = 1_770_000_003_000L;
		state.recordPreInitialXp("FISHING", 140);
		now = 1_770_000_004_000L;
		state.recordPreInitialXp("MINING", 5_060);

		logIn();
		assertTrue(state.seedXpBaseline("FISHING", FISHING, 140));
		assertTrue(state.seedXpBaseline("MINING", MINING, 5_060));

		String json = snapshot();
		assertEquals("\"mining\"", value(json, "lastSkill"));
		assertEquals("1770000004000", value(json, "lastChangedAt"));
	}

	/**
	 * Events delivered in one game tick routinely share a millisecond, so wall time cannot
	 * separate them. Selection is by arrival position, which has no ties.
	 */
	@Test
	public void twoRetainedSpansInTheSameMillisecondAreSeparatedByArrivalOrder()
	{
		now = 1_770_000_001_000L;
		state.recordPreInitialXp("MINING", 5_000);
		state.recordPreInitialXp("FISHING", 100);
		state.recordPreInitialXp("MINING", 5_060);
		// FISHING's span is completed last, so FISHING is the most recent gain.
		state.recordPreInitialXp("FISHING", 140);

		logIn();
		assertTrue(state.seedXpBaseline("MINING", MINING, 5_060));
		assertTrue(state.seedXpBaseline("FISHING", FISHING, 140));

		String json = snapshot();
		assertEquals("the later-arriving span wins a tied millisecond", "\"fishing\"",
			value(json, "lastSkill"));
		assertEquals("40", value(json, "lastDelta"));
		assertEquals("1770000001000", value(json, "lastChangedAt"));
	}

	/**
	 * Recency is decided by arrival order, not by the exported wall-clock timestamp: a backward
	 * clock adjustment between two events would give the older one the larger value.
	 */
	@Test
	public void aBackwardWallClockJumpBetweenRetainedEventsDoesNotReorderThem()
	{
		now = 1_770_000_005_000L;
		state.recordPreInitialXp("MINING", 5_000);
		state.recordPreInitialXp("MINING", 5_060);

		now = 1_770_000_001_000L;
		state.recordPreInitialXp("FISHING", 100);
		state.recordPreInitialXp("FISHING", 140);

		logIn();
		assertTrue(state.seedXpBaseline("FISHING", FISHING, 140));
		assertTrue(state.seedXpBaseline("MINING", MINING, 5_060));

		String json = snapshot();
		assertEquals("arrival order decides, not the adjusted clock", "\"fishing\"",
			value(json, "lastSkill"));
		assertEquals("40", value(json, "lastDelta"));
		assertEquals("and the exported time is still the event's own wall time", "1770000001000",
			value(json, "lastChangedAt"));
	}

	@Test
	public void aRetainedEventCannotDisplaceANewerLiveObservation()
	{
		now = 1_770_000_001_000L;
		state.recordPreInitialXp("MINING", 5_000);
		now = 1_770_000_002_000L;
		state.recordPreInitialXp("MINING", 5_060);

		logIn();
		state.observeXp("COOKING", COOKING, 800);
		now = 1_770_000_020_000L;
		assertTrue(state.observeXp("COOKING", COOKING, 900));

		assertTrue(state.seedXpBaseline("MINING", MINING, 5_060));

		String json = snapshot();
		assertEquals("\"cooking\"", value(json, "lastSkill"));
		assertEquals("100", value(json, "lastDelta"));
		assertEquals("1770000020000", value(json, "lastChangedAt"));
	}

	@Test
	public void aTransientZeroDuringStartupCannotFabricateAWholeSkillGain()
	{
		now = 1_770_000_001_000L;
		state.recordPreInitialXp("WOODCUTTING", 1_234_567);
		now = 1_770_000_002_000L;
		state.recordPreInitialXp("WOODCUTTING", 0);

		logIn();
		assertTrue(state.seedXpBaseline("WOODCUTTING", WOODCUTTING, 1_234_567));

		String json = snapshot();
		assertEquals("no whole-skill gain is fabricated", "null", value(json, "lastSkill"));
		assertEquals("null", value(json, "lastDelta"));

		now = 1_770_000_009_000L;
		assertTrue(state.observeXp("WOODCUTTING", WOODCUTTING, 1_234_632));
		assertEquals("65", value(snapshot(), "lastDelta"));
	}

	@Test
	public void discardingRetainedTotalsStopsThemInfluencingALaterSeed()
	{
		state.recordPreInitialXp("FISHING", 100);
		state.discardPreInitialXp();

		logIn();
		assertTrue(state.seedXpBaseline("FISHING", FISHING, 250));
		assertEquals("nothing is reported from a discarded total", "null",
			value(snapshot(), "lastSkill"));
	}

	@Test
	public void aStartupSpanningALogoutDoesNotCarryItsTotalIntoTheNextLogin()
	{
		state.recordPreInitialXp("FISHING", 100);
		// The session ends before startup finished, discarding session-local experience.
		state.updateSession("LOGIN_SCREEN", false, true);

		logIn();
		assertTrue(state.seedXpBaseline("FISHING", FISHING, 250));
		assertEquals("a previous session's total cannot become this login's baseline", "null",
			value(snapshot(), "lastSkill"));
	}

	@Test
	public void discardingOnSessionEndProtectsAgainstMeasuringAcrossCharacters()
	{
		state.recordPreInitialXp("FISHING", 100);
		state.discardPreInitialXp();

		// A different character logs in with a far higher total in the same skill.
		logIn();
		assertTrue(state.seedXpBaseline("FISHING", FISHING, 5_000_000));

		String json = snapshot();
		assertEquals("no cross-character gain is exported", "null", value(json, "lastSkill"));
		assertEquals("null", value(json, "lastDelta"));
	}

	// --- tracking start, seeding lifecycle, and cadence ----------------------------------------------

	@Test
	public void trackingStartsWhenTheSessionsBaselinesAreEstablished()
	{
		enterLoggedIn();
		assertTrue(state.needsXpBaselineSeeding());
		now = 1_770_000_003_000L;
		state.markXpBaselinesSeeded();

		logIn();
		assertEquals("1770000003000", value(snapshot(), "trackingStartedAt"));
	}

	@Test
	public void trackingStartIsNotClaimedToBeTheAccountsLoginTimeAndSurvivesAHop()
	{
		logIn();
		now = 1_770_000_003_000L;
		state.markXpBaselinesSeeded();
		String started = value(snapshot(), "trackingStartedAt");
		assertEquals("1770000003000", started);

		state.updateSession("LOADING", false);
		assertEquals("null", value(snapshot(), "trackingStartedAt"));

		now = 1_770_000_090_000L;
		logIn();
		assertEquals("a hop keeps the session, so tracking did not restart", started,
			value(snapshot(), "trackingStartedAt"));
	}

	@Test
	public void endingTheSessionResetsTrackingStart()
	{
		logIn();
		now = 1_770_000_003_000L;
		state.markXpBaselinesSeeded();
		assertEquals("1770000003000", value(snapshot(), "trackingStartedAt"));

		state.updateSession("LOGIN_SCREEN", false, true);
		assertEquals("null", value(snapshot(), "trackingStartedAt"));

		logIn();
		assertEquals("the next session has not established baselines yet", "null",
			value(snapshot(), "trackingStartedAt"));
		now = 1_770_000_120_000L;
		state.markXpBaselinesSeeded();
		assertEquals("1770000120000", value(snapshot(), "trackingStartedAt"));
	}

	@Test
	public void aSessionThatStartedMidHopStillSeedsOnceItGoesLive()
	{
		// Startup lands during a loading screen: nothing to seed from.
		state.updateSession("LOADING", false);
		assertFalse("no seeding is possible without a live session", state.needsXpBaselineSeeding());

		logIn();
		assertTrue("the first live sample must seed", state.needsXpBaselineSeeding());
		assertTrue(state.seedXpBaseline("AGILITY", AGILITY, 50_000));
		state.markXpBaselinesSeeded();

		assertFalse("seeding happens once per session, not every sample",
			state.needsXpBaselineSeeding());

		now = 1_770_000_005_000L;
		assertTrue(state.observeXp("AGILITY", AGILITY, 50_120));
		assertEquals("120", value(snapshot(), "lastDelta"));
	}

	@Test
	public void retainedEvidenceBeforeAHopSurvivesUntilTheSessionGoesLive()
	{
		now = 1_770_000_001_000L;
		assertTrue(state.recordPreInitialXp("AGILITY", 50_000));
		now = 1_770_000_002_000L;
		state.recordPreInitialXp("AGILITY", 50_120);
		state.updateSession("LOADING", false);

		logIn();
		assertTrue(state.needsXpBaselineSeeding());
		now = 1_770_000_030_000L;
		assertTrue(state.seedXpBaseline("AGILITY", AGILITY, 50_120));

		String json = snapshot();
		assertEquals("the window's experience is still measured", "120", value(json, "lastDelta"));
		assertEquals("and still carries its own event time", "1770000002000",
			value(json, "lastChangedAt"));
	}

	@Test
	public void aWorldHopPreservesTheLastExportedExperienceGain()
	{
		logIn();
		state.observeXp("WOODCUTTING", WOODCUTTING, 1_000);
		now = 1_770_000_004_000L;
		assertTrue(state.observeXp("WOODCUTTING", WOODCUTTING, 1_065));
		assertEquals("65", value(snapshot(), "lastDelta"));

		// The hop itself: not a session end, so baselines are kept.
		state.updateSession("LOADING", false);
		String duringHop = snapshot();
		assertEquals("nothing player-derived is exported mid-hop", "null",
			value(duringHop, "lastSkill"));
		assertEquals("null", value(duringHop, "lastDelta"));

		// Back in the same session: the gain is still the most recent one that happened.
		logIn();
		String afterHop = snapshot();
		assertEquals("\"woodcutting\"", value(afterHop, "lastSkill"));
		assertEquals("65", value(afterHop, "lastDelta"));
		assertEquals("1770000004000", value(afterHop, "lastChangedAt"));
	}

	@Test
	public void aSessionEndStillDiscardsTheLastExportedExperienceGain()
	{
		logIn();
		state.observeXp("WOODCUTTING", WOODCUTTING, 1_000);
		now = 1_770_000_004_000L;
		assertTrue(state.observeXp("WOODCUTTING", WOODCUTTING, 1_065));

		state.updateSession("LOGIN_SCREEN", false, true);
		logIn();

		String json = snapshot();
		assertEquals("a new session inherits no gain", "null", value(json, "lastSkill"));
		assertEquals("null", value(json, "lastDelta"));
		assertEquals("null", value(json, "lastChangedAt"));
		assertEquals("[]", value(json, "skills"));
	}

	@Test
	public void aWorldHopDoesNotCauseTheSessionToReseed()
	{
		logIn();
		state.seedXpBaseline("AGILITY", AGILITY, 50_000);
		state.markXpBaselinesSeeded();

		state.updateSession("LOADING", false);
		logIn();
		assertFalse("a hop must not re-read totals or reset the comparison",
			state.needsXpBaselineSeeding());

		now = 1_770_000_005_000L;
		assertTrue(state.observeXp("AGILITY", AGILITY, 50_120));
		assertEquals("120", value(snapshot(), "lastDelta"));
	}

	@Test
	public void aLogoutAndLoginInsideOneRunSeedsAgainRatherThanConsumingTheNextGain()
	{
		logIn();
		state.seedXpBaseline("AGILITY", AGILITY, 50_000);
		state.markXpBaselinesSeeded();

		state.updateSession("LOGIN_SCREEN", false, true);
		logIn();
		assertTrue("a new session seeds again", state.needsXpBaselineSeeding());

		assertTrue(state.seedXpBaseline("AGILITY", AGILITY, 60_000));
		now = 1_770_000_005_000L;
		assertTrue("the first gain after logging back in is exported",
			state.observeXp("AGILITY", AGILITY, 60_075));
		assertEquals("75", value(snapshot(), "lastDelta"));
	}

	@Test
	public void seedingIsNotMarkedCompleteWhileLoggedOut()
	{
		state.updateSession("LOGIN_SCREEN", false);
		state.markXpBaselinesSeeded();

		logIn();
		assertTrue("a logged-out mark must not suppress the real seeding",
			state.needsXpBaselineSeeding());
	}

	@Test
	public void aTransientZeroObservationNeverBecomesABaseline()
	{
		logIn();
		assertFalse("a zero must not seed a baseline", state.observeXp("WOODCUTTING", WOODCUTTING, 0));

		assertFalse("the real total seeds instead",
			state.observeXp("WOODCUTTING", WOODCUTTING, 1_234_567));
		String json = snapshot();
		assertEquals("no whole-skill gain is fabricated", "null", value(json, "lastSkill"));
		assertEquals("null", value(json, "lastDelta"));

		now = 1_770_000_009_000L;
		assertTrue(state.observeXp("WOODCUTTING", WOODCUTTING, 1_234_632));
		assertEquals("65", value(snapshot(), "lastDelta"));
	}

	@Test
	public void aRefusedZeroLeavesTheSkillOpenToATrustedLiveSeed()
	{
		logIn();
		state.observeXp("WOODCUTTING", WOODCUTTING, 0);

		assertTrue("no baseline is in the way",
			state.seedXpBaseline("WOODCUTTING", WOODCUTTING, 1_234_567));
		assertEquals("null", value(snapshot(), "lastSkill"));

		now = 1_770_000_009_000L;
		assertTrue(state.observeXp("WOODCUTTING", WOODCUTTING, 1_234_632));
		assertEquals("65", value(snapshot(), "lastDelta"));
	}

	@Test
	public void experienceIsNotObservedWhileLoggedOut()
	{
		state.updateSession("LOGIN_SCREEN", false);
		assertFalse(state.observeXp("SLAYER", SLAYER, 1));
		assertFalse(state.observeXp("SLAYER", SLAYER, 2));
		assertEquals("null", value(snapshot(), "lastSkill"));
	}

	// --- identity, sequence, cadence, and shutdown ---------------------------------------------------

	@Test
	public void instanceIdIsFixedForTheLifetimeOfTheStateAndIsNotDerivedFromGameData()
	{
		logIn();
		assertEquals(INSTANCE_ID, state.getInstanceId());
		assertEquals(INSTANCE_ID, state.nextSnapshot(true).getInstanceId());
		state.recordPublished();
		assertEquals(INSTANCE_ID, state.nextSnapshot(true).getInstanceId());

		// A separate start is a separate identity.
		TelemetryState other =
			new TelemetryState(UUID.randomUUID().toString(), () -> now, () -> elapsed);
		assertFalse(INSTANCE_ID.equals(other.getInstanceId()));
	}

	@Test
	public void sequenceStartsAtZeroAndAdvancesOnlyForAPublishedSnapshot()
	{
		assertEquals(0L, state.getNextSeq());
		assertEquals(0L, state.nextSnapshot(true).getSeq());

		// A snapshot that was built but never written must not consume its number.
		assertEquals(0L, state.nextSnapshot(true).getSeq());

		state.recordPublished();
		assertEquals(1L, state.getNextSeq());
		assertEquals(1L, state.nextSnapshot(true).getSeq());
		state.recordPublished();
		assertEquals(2L, state.nextSnapshot(true).getSeq());
	}

	@Test
	public void publicationIsDueWhenDirtyAndAgainOnceTheHeartbeatIntervalElapses()
	{
		assertTrue("a newly started plugin publishes immediately", state.isPublicationDue(1_500L));
		state.nextSnapshot(true);
		state.recordPublished();
		assertFalse(state.isDirty());
		assertFalse(state.isPublicationDue(1_500L));

		// Unchanged state still republishes as a heartbeat, driven by elapsed time.
		elapseMillis(1_500L);
		assertTrue(state.isPublicationDue(1_500L));

		elapseMillis(1L);
		state.nextSnapshot(true);
		state.recordPublished();
		assertFalse(state.isPublicationDue(1_500L));

		// A change makes it due again straight away.
		logIn();
		assertTrue(state.isDirty());
		assertTrue(state.isPublicationDue(1_500L));
	}

	/**
	 * Cadence is measured against the monotonic clock. Measured against wall time, an adjustment
	 * backwards would keep the elapsed figure negative until wall time caught up, so a healthy
	 * idle plugin would stop heartbeating and its file would read as stale.
	 */
	@Test
	public void aBackwardWallClockJumpCannotSuppressAHeartbeat()
	{
		state.nextSnapshot(true);
		state.recordPublished();
		assertFalse(state.isDirty());
		assertFalse(state.isPublicationDue(1_500L));

		now -= 3_600_000L;
		assertFalse("a clock adjustment alone is not a heartbeat", state.isPublicationDue(1_500L));

		elapseMillis(1_500L);
		assertTrue("the heartbeat must not wait for wall time to catch up",
			state.isPublicationDue(1_500L));
	}

	@Test
	public void aForwardWallClockJumpCannotForceAHeartbeat()
	{
		state.nextSnapshot(true);
		state.recordPublished();
		assertFalse(state.isPublicationDue(1_500L));

		now += 3_600_000L;
		assertFalse("wall time cannot bring a heartbeat forward", state.isPublicationDue(1_500L));

		elapseMillis(1_499L);
		assertFalse("still short of the interval", state.isPublicationDue(1_500L));
		elapseMillis(1L);
		assertTrue("due exactly when the elapsed interval is reached",
			state.isPublicationDue(1_500L));
	}

	@Test
	public void aDirtyStateIsDueImmediatelyWhateverEitherClockSays()
	{
		state.nextSnapshot(true);
		state.recordPublished();
		assertFalse(state.isPublicationDue(1_500L));

		logIn();
		assertTrue(state.isDirty());
		assertTrue("a change publishes without consulting any interval",
			state.isPublicationDue(1_500L));

		now -= 3_600_000L;
		assertTrue(state.isPublicationDue(1_500L));
	}

	/**
	 * The heartbeat is measured from the last snapshot a reader could genuinely have seen. A
	 * publication that was refused or failed never called
	 * {@link TelemetryState#recordPublished()}, so the interval must keep running rather than
	 * restarting on an attempt that wrote nothing.
	 */
	@Test
	public void aPublicationThatNeverReachedTheFileDoesNotRestartTheHeartbeatInterval()
	{
		state.nextSnapshot(true);
		state.recordPublished();
		assertFalse(state.isPublicationDue(1_500L));

		elapseMillis(1_400L);
		state.nextSnapshot(true);
		elapseMillis(100L);

		assertTrue("the interval runs from the last committed publication, not the last attempt",
			state.isPublicationDue(1_500L));
	}

	@Test
	public void exportedTimestampsFollowWallTimeWhileCadenceFollowsElapsedTime()
	{
		logIn();
		state.observeXp("WOODCUTTING", WOODCUTTING, 1_000);

		// Elapsed time races ahead; it must not appear in any exported field.
		elapseMillis(500_000L);
		now = 1_770_000_007_000L;
		assertTrue(state.observeXp("WOODCUTTING", WOODCUTTING, 1_065));

		String json = snapshot();
		assertEquals("1770000007000", value(json, "emittedAt"));
		assertEquals("1770000007000", value(json, "lastChangedAt"));

		// And wall time moving does not by itself satisfy the cadence.
		state.recordPublished();
		now += 60_000L;
		assertFalse(state.isPublicationDue(1_500L));
	}

	@Test
	public void aChangeDuringPublicationIsRepublishedRatherThanDropped()
	{
		state.nextSnapshot(true);
		logIn();
		state.recordPublished();
		assertTrue("the change landed after the snapshot was built", state.isDirty());
	}

	@Test
	public void shutdownSnapshotIsInactiveLoggedOutAndFullyNulled()
	{
		logIn();
		state.updateInventory(inventoryWith(12));
		state.updateCombat("accurate", Arrays.asList("piety"));
		state.updateTarget(TelemetryTarget.npc(4001, "Sample dummy", 21, 18, 30, false));
		state.observeXp("COOKING", COOKING, 1_000);
		state.observeXp("COOKING", COOKING, 1_100);
		state.recordPublished();

		TelemetrySnapshot shutdown = state.nextSnapshot(false);
		String json = shutdown.toJson();

		assertFalse(shutdown.isPluginActive());
		assertFalse(shutdown.isLoggedIn());
		assertEquals("false", value(json, "pluginActive"));
		assertEquals("false", value(json, "loggedIn"));
		// Lifecycle metadata is retained: same instance, next sequence, current game state.
		assertEquals(INSTANCE_ID, shutdown.getInstanceId());
		assertEquals(1L, shutdown.getSeq());
		assertEquals("\"LOGGED_IN\"", value(json, "gameState"));

		for (String key : new String[]{"world", "combatLevel", "trackingStartedAt",
			"hitpointsCurrent", "hitpointsBase", "prayerCurrent", "prayerBase", "runEnergyPercent",
			"specialAttackPercent", "weightKg", "attackStyle", "activePrayers", "target", "slots",
			"usedSlots", "freeSlots", "lastSkill", "lastDelta", "lastChangedAt", "skills"})
		{
			assertEquals(key + " should be null on shutdown", "null", value(json, key));
		}
	}
}
