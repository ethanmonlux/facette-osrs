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
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The login-recognition regression matrix at the state level: a character is recognized on its first idle
 * sample whatever containers the client has provided, a missing container is never reported as
 * empty, a confirmed empty one is, and an observation never gets fresher without a new read.
 */
public class TelemetryStateSchema5Test
{
	private static final long START = 1_770_000_000_000L;

	/** One wall clock shared by a test's states, moved by hand. */
	private static final long[] NOW = {START};

	static TelemetryState loggedInState(boolean schema5)
	{
		NOW[0] = START;
		AtomicInteger sessions = new AtomicInteger();
		TelemetryState state = new TelemetryState("instance-1", () -> NOW[0], () -> 0L, schema5, 4,
			() -> "session-" + sessions.incrementAndGet());
		state.updateSession("LOGGED_IN", true);
		return state;
	}

	static List<TelemetryItemSlot> emptyEquipment()
	{
		return new ArrayList<>(Collections.nCopies(TelemetrySnapshot.EQUIPMENT_SLOTS.size(),
			TelemetryItemSlot.EMPTY));
	}

	static List<TelemetryItemSlot> emptyInventory()
	{
		return new ArrayList<>(Collections.nCopies(TelemetryState.INVENTORY_CAPACITY,
			TelemetryItemSlot.EMPTY));
	}

	private static List<TelemetryItemSlot> fullInventory()
	{
		List<TelemetryItemSlot> slots = new ArrayList<>();
		for (int i = 0; i < TelemetryState.INVENTORY_CAPACITY; i++)
		{
			slots.add(TelemetryItemSlot.of(3101 + i, 1, "Sample item " + i));
		}
		return slots;
	}

	private static List<TelemetryItemSlot> wornScimitar()
	{
		List<TelemetryItemSlot> slots = emptyEquipment();
		slots.set(TelemetrySnapshot.EQUIPMENT_SLOTS.indexOf("weapon"),
			TelemetryItemSlot.of(4587, 1, "Sample scimitar"));
		return slots;
	}

	/** One idle logged-in sample, with whatever containers the client provided. */
	static void sample(TelemetryState state, List<TelemetryItemSlot> equipment,
		List<TelemetryItemSlot> inventory)
	{
		sample(state, equipment, inventory, 3);
	}

	private static void sample(TelemetryState state, List<TelemetryItemSlot> equipment,
		List<TelemetryItemSlot> inventory, int combatLevel)
	{
		state.updateSession("LOGGED_IN", true);
		state.beginSample();
		if (state.needsXpBaselineSeeding())
		{
			state.markXpBaselinesSeeded();
		}
		state.updateWorld(302);
		state.updateCombatLevel(combatLevel);
		state.updateVitals(10, 10, 1, 1, 10_000, 1_000, 0);
		state.updateCombat(null, Collections.emptyList());
		state.updateTarget(null);
		state.updateEquipment(equipment);
		state.updateInventory(inventory);
		state.markPlayerStateComplete();
		state.applySchema3Sample(
			Collections.singletonList(new TelemetrySkillState("attack", 1, 1, 0)),
			0, 0, 0, -1, Collections.emptyList());
		state.endSample(true);
	}

	static TelemetrySnapshot view(TelemetryState state)
	{
		TelemetrySnapshot view = state.nextSnapshot(true).getSchema5View();
		assertNotNull("a schema-5 state attaches its view", view);
		return view;
	}

	static String json(TelemetryState state)
	{
		return view(state).toJson(TelemetrySnapshot.SCHEMA_5);
	}

	private static String group(String json, String name)
	{
		int at = json.indexOf("\"groups\":");
		int start = json.indexOf("\"" + name + "\":{", at);
		return json.substring(start, json.indexOf('}', start) + 1);
	}

	// --- recognition without input ---------------------------------------------------------

	@Test
	public void aBareIdleCharacterIsRecognizedOnItsFirstSampleWithoutEitherContainer()
	{
		TelemetryState state = loggedInState(true);
		sample(state, null, null);

		TelemetrySnapshot snapshot = state.nextSnapshot(true);
		assertFalse("schemas 3 and 4 keep their all-or-nothing rule", snapshot.isLoggedIn());
		TelemetrySnapshot view = snapshot.getSchema5View();
		assertTrue("schema 5 recognizes the character", view.isLoggedIn());
		assertEquals(TelemetrySnapshot.PARTIAL, view.getReadinessState());
		assertEquals(Arrays.asList("equipment", "inventory"), view.getReadinessMissing());
		String json = view.toJson(TelemetrySnapshot.SCHEMA_5);
		assertTrue(json.contains("\"equipment\":{\"slots\":null}"));
		assertTrue(json.contains("\"inventory\":{\"usedSlots\":null,\"freeSlots\":null,\"slots\":null}"));
		assertEquals("\"equipment\":{\"availability\":\"not_observed\",\"observedAt\":null}",
			group(json, "equipment"));
		assertEquals("\"vitals\":{\"availability\":\"current\",\"observedAt\":" + START + "}",
			group(json, "vitals"));
		assertNotNull(view.getCharacterSessionId());
	}

	/** The live reproduction: a full backpack, nothing worn, no XP, no input. */
	@Test
	public void aFullBackpackWithNothingWornIsRecognizedBeforeAnyEquipOrExperience()
	{
		TelemetryState state = loggedInState(true);
		sample(state, null, fullInventory());

		TelemetrySnapshot view = view(state);
		assertTrue(view.isLoggedIn());
		assertEquals(Collections.singletonList("equipment"), view.getReadinessMissing());
		String json = view.toJson(TelemetrySnapshot.SCHEMA_5);
		assertTrue(json.contains("\"usedSlots\":28,\"freeSlots\":0"));
		assertTrue(json.contains("\"xp\":{\"lastSkill\":null,\"lastDelta\":null,\"lastChangedAt\":null,\"skills\":[]}"));
	}

	@Test
	public void confirmedEmptyContainersAreReadyAndEmptyRatherThanUnknown()
	{
		TelemetryState state = loggedInState(true);
		sample(state, emptyEquipment(), emptyInventory());

		TelemetrySnapshot snapshot = state.nextSnapshot(true);
		assertTrue("both containers confirmed: schemas 3 and 4 are complete too", snapshot.isLoggedIn());
		TelemetrySnapshot view = snapshot.getSchema5View();
		assertEquals(TelemetrySnapshot.READY, view.getReadinessState());
		String json = view.toJson(TelemetrySnapshot.SCHEMA_5);
		assertTrue(json.contains("\"usedSlots\":0,\"freeSlots\":28"));
		assertEquals("\"equipment\":{\"availability\":\"current\",\"observedAt\":" + START + "}",
			group(json, "equipment"));
	}

	@Test
	public void aContainerTheClientProvidesLaterIsPickedUpByTheNextIdleSample()
	{
		TelemetryState state = loggedInState(true);
		sample(state, null, fullInventory());
		assertEquals(TelemetrySnapshot.PARTIAL, view(state).getReadinessState());

		NOW[0] = START + 600L;
		sample(state, emptyEquipment(), fullInventory());
		String json = json(state);
		assertEquals(TelemetrySnapshot.READY, view(state).getReadinessState());
		assertEquals("\"equipment\":{\"availability\":\"current\",\"observedAt\":" + (START + 600L) + "}",
			group(json, "equipment"));
		assertEquals("the backpack has read the same since the first sample",
			"\"inventory\":{\"availability\":\"current\",\"observedAt\":" + START + "}",
			group(json, "inventory"));
	}

	@Test
	public void anUnchangedReadingNeitherFreshensItsObservationNorForcesAPublication()
	{
		TelemetryState state = loggedInState(true);
		sample(state, emptyEquipment(), emptyInventory());
		state.nextSnapshot(true);
		state.recordPublished();
		assertFalse(state.isDirty());

		NOW[0] = START + 6_000L;
		sample(state, emptyEquipment(), emptyInventory());
		assertFalse("an idle sample must not make the heartbeat publish early", state.isDirty());
		assertEquals("\"equipment\":{\"availability\":\"current\",\"observedAt\":" + START + "}",
			group(json(state), "equipment"));
	}

	@Test
	public void aContainerLostAfterAReadIsRetainedAsObservedAndNeverAsCurrent()
	{
		TelemetryState state = loggedInState(true);
		sample(state, wornScimitar(), fullInventory());
		NOW[0] = START + 600L;
		sample(state, wornScimitar(), fullInventory());
		NOW[0] = START + 1_200L;
		sample(state, null, fullInventory());

		TelemetrySnapshot view = view(state);
		assertEquals(TelemetrySnapshot.PARTIAL, view.getReadinessState());
		String json = view.toJson(TelemetrySnapshot.SCHEMA_5);
		assertEquals("the last sample that actually read it",
			"\"equipment\":{\"availability\":\"observed\",\"observedAt\":" + (START + 600L) + "}",
			group(json, "equipment"));
		assertTrue("the retained reading is carried, dated", json.contains("\"itemId\":4587"));
	}

	@Test
	public void aConfirmedEmptyContainerReplacesAnEarlierPopulatedOne()
	{
		TelemetryState state = loggedInState(true);
		sample(state, wornScimitar(), emptyInventory());
		NOW[0] = START + 600L;
		sample(state, emptyEquipment(), emptyInventory());

		String json = json(state);
		assertFalse(json.contains("\"itemId\":4587"));
		assertEquals("\"equipment\":{\"availability\":\"current\",\"observedAt\":" + (START + 600L) + "}",
			group(json, "equipment"));
	}

	@Test
	public void aCharacterWhoseLocalPlayerHasNotResolvedIsLoadingNotLoggedOut()
	{
		TelemetryState state = loggedInState(true);
		sample(state, emptyEquipment(), emptyInventory(), 0);

		TelemetrySnapshot view = view(state);
		assertTrue(view.isLoggedIn());
		assertEquals(TelemetrySnapshot.LOADING, view.getReadinessState());
		assertEquals(Collections.singletonList("combatLevel"), view.getReadinessMissing());
	}

	@Test
	public void aSampleThatDidNotCompleteDoesNotRecognizeTheCharacter()
	{
		TelemetryState state = loggedInState(true);
		state.beginSample();
		state.endSample(false);

		TelemetrySnapshot view = view(state);
		assertFalse(view.isLoggedIn());
		assertEquals(TelemetrySnapshot.LOGGED_OUT, view.getReadinessState());
	}

	// --- session boundaries -----------------------------------------------------------------

	@Test
	public void logoutEndsTheCharacterSessionAndTheNextLoginStartsANewOne()
	{
		TelemetryState state = loggedInState(true);
		sample(state, emptyEquipment(), emptyInventory());
		String first = view(state).getCharacterSessionId();

		state.updateSession("LOGIN_SCREEN", false, true);
		TelemetrySnapshot loggedOut = view(state);
		assertFalse(loggedOut.isLoggedIn());
		assertNull(loggedOut.getCharacterSessionId());
		assertEquals(TelemetrySnapshot.LOGGED_OUT, loggedOut.getReadinessState());
		String json = loggedOut.toJson(TelemetrySnapshot.SCHEMA_5);
		assertTrue(json.contains("\"equipment\":{\"availability\":\"unavailable\",\"observedAt\":null}"));
		assertTrue(json.endsWith("\"accountData\":null}"));

		sample(state, null, null);
		String second = view(state).getCharacterSessionId();
		assertNotNull(second);
		assertNotEquals("a new login is a new character session", first, second);
	}

	@Test
	public void aWorldHopKeepsTheCharacterSessionButNotItsPlayerReadings()
	{
		TelemetryState state = loggedInState(true);
		sample(state, emptyEquipment(), emptyInventory());
		String session = view(state).getCharacterSessionId();

		state.updateSession("HOPPING", false, false);
		TelemetrySnapshot hopping = view(state);
		assertFalse(hopping.isLoggedIn());
		assertEquals(session, hopping.getCharacterSessionId());

		state.updateSession("LOGGED_IN", true, false);
		assertFalse("not recognized again until this login has been sampled", view(state).isLoggedIn());
		sample(state, emptyEquipment(), emptyInventory());
		assertTrue(view(state).isLoggedIn());
		assertEquals(session, view(state).getCharacterSessionId());
	}

	@Test
	public void aProfileChangeEndsTheCharacterSessionWithoutALogout()
	{
		TelemetryState state = loggedInState(true);
		sample(state, emptyEquipment(), emptyInventory());
		String first = view(state).getCharacterSessionId();

		state.endCharacterSession();
		assertFalse(view(state).isLoggedIn());
		assertNull(view(state).getCharacterSessionId());

		sample(state, emptyEquipment(), emptyInventory());
		assertNotEquals(first, view(state).getCharacterSessionId());
	}

	@Test
	public void theFinalInactiveSnapshotCarriesNoSessionAndNoPlayerData()
	{
		TelemetryState state = loggedInState(true);
		sample(state, emptyEquipment(), emptyInventory());
		TelemetrySnapshot view = state.nextSnapshot(false).getSchema5View();
		assertFalse(view.isLoggedIn());
		assertNull(view.getCharacterSessionId());
		assertEquals(TelemetrySnapshot.INACTIVE, view.getReadinessState());
		assertTrue(view.toJson(TelemetrySnapshot.SCHEMA_5).contains("\"pluginActive\":false"));
	}

	@Test
	public void aStateWithoutSchemaFiveAttachesNoView()
	{
		TelemetryState state = loggedInState(false);
		sample(state, null, fullInventory());
		assertNull(state.nextSnapshot(true).getSchema5View());
	}
}
