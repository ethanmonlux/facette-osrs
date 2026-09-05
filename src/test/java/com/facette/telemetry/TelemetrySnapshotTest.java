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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;

/**
 * Pins the exported schema-3 document: its exact keys, its key order at every level, its shape while
 * logged out, its capability answers, its size ceiling, and the absence of anything outside the
 * schema.
 *
 * {@link #populatedFixture()} and {@link #loggedOutFixture()} are the single definition of what
 * schema 3 looks like, and {@code TelemetrySchemaFixtureTest} checks the committed files against
 * them, so the schema exists in one place.
 */
public class TelemetrySnapshotTest
{
	private static final String INSTANCE_ID = "0f8b1d3a-6c2e-4a15-9f77-2b8d4e6a1c90";

	private static final long EMITTED_AT = 1_770_000_000_000L;

	/**
	 * Schema 2's eleven keys in their original order, then schema 3's six appended after them. The
	 * split is written out rather than merged, because "schema 3 is schema 2 plus a suffix" is the
	 * compatibility promise and this is where it is enforced.
	 */
	private static final List<String> SCHEMA_TWO_TOP_LEVEL_KEYS = Arrays.asList(
		"schema", "source", "instanceId", "seq", "emittedAt",
		"session", "vitals", "combat", "equipment", "inventory", "xp");

	private static final List<String> SCHEMA_THREE_TOP_LEVEL_KEYS = Arrays.asList(
		"capabilities", "skills", "account", "quests", "slayer", "grandExchange");

	private static final List<String> AUTHORIZED_TOP_LEVEL_KEYS = concat(
		SCHEMA_TWO_TOP_LEVEL_KEYS, SCHEMA_THREE_TOP_LEVEL_KEYS);

	private static final List<String> SCHEMA_TWO_SECOND_LEVEL_KEYS = Arrays.asList(
		"pluginActive", "gameState", "loggedIn", "world", "combatLevel", "trackingStartedAt",
		"hitpointsCurrent", "hitpointsBase", "prayerCurrent", "prayerBase", "runEnergyPercent",
		"specialAttackPercent", "weightKg",
		"attackStyle", "activePrayers", "target",
		"slots",
		"usedSlots", "freeSlots", "slots",
		"lastSkill", "lastDelta", "lastChangedAt", "skills");

	private static final List<String> SCHEMA_THREE_SECOND_LEVEL_KEYS = concat(
		// capabilities: one entry per group, named by the group itself
		TelemetrySnapshot.CAPABILITY_GROUPS,
		Arrays.asList(
			"entries",
			"type", "typeId",
			"points",
			"remaining", "taskCreatureId",
			"slots"));

	private static final List<String> AUTHORIZED_SECOND_LEVEL_KEYS = concat(
		SCHEMA_TWO_SECOND_LEVEL_KEYS, SCHEMA_THREE_SECOND_LEVEL_KEYS);

	private static final List<String> TARGET_KEYS = Arrays.asList(
		"kind", "id", "name", "combatLevel", "healthRatio", "healthScale", "dead");

	private static final List<String> ITEM_SLOT_KEYS = Arrays.asList(
		"slot", "itemId", "quantity", "name");

	private static final List<String> SKILL_GAIN_KEYS = Arrays.asList(
		"skill", "gained", "lastDelta", "lastChangedAt");

	private static final List<String> SKILL_STATE_KEYS = Arrays.asList(
		"skill", "level", "boostedLevel", "xp");

	private static final List<String> OFFER_KEYS = Arrays.asList(
		"slot", "state", "itemId", "price", "totalQuantity", "quantityTransacted", "spent");

	/**
	 * Key names that must never appear in the document. Schema 3 exports no account identity,
	 * credential, chat, social, bank, collection, valuation, location, network, or control data, and
	 * no player target.
	 *
	 * Three names schema 2 refused are no longer on this list, because schema 3 exports exactly the
	 * bounded facts they named and nothing adjacent to them: {@code account} is the account's mode
	 * and never its identity, {@code grandExchange} is the player's own open offer slots and never a
	 * price guide or a market, and {@code price} is an offer's own asking price. The identity names
	 * beside {@code account} all stay forbidden, and {@code price} is separately pinned below to the
	 * offers and nowhere else.
	 */
	private static final List<String> FORBIDDEN_KEYS = Arrays.asList(
		"accountId", "accountHash", "accountType", "username", "displayName",
		"playerName", "player", "email", "password", "token", "sessionToken", "credential",
		"profile", "machineId", "installationId",
		"chat", "message", "friends", "clan", "party", "players", "nearbyPlayers",
		"bank", "wealth", "gp", "coins", "value", "highAlch", "ge",
		"tradeable", "examine", "loadout", "offers", "history", "sellPrice", "buyPrice",
		"totalXp", "startingXp", "levelHistory",
		"clue", "loot", "achievement", "collectionLog", "diary", "seedVault",
		"location", "worldPoint", "regionId", "coordinates", "latitude", "longitude",
		"x", "y", "plane", "tile", "movement",
		"path", "url", "host", "address", "port", "endpoint",
		"command", "input", "menu", "click", "keystroke", "ack", "sprite", "icon", "image");

	/**
	 * What the canonical populated document happens to contain. These are counts of the fixture, not
	 * bounds of the schema: the schema bounds these groups by the client's own enumerations, and a
	 * document that carries a different number of skills or offer slots is not malformed.
	 */
	private static final int FIXTURE_SESSION_GAINS = 3;

	private static final int FIXTURE_SKILLS = 24;

	private static final int FIXTURE_OFFER_SLOTS = 8;

	private static List<String> concat(List<String> first, List<String> second)
	{
		List<String> all = new ArrayList<>(first);
		all.addAll(second);
		return Collections.unmodifiableList(all);
	}

	// --- the canonical documents ------------------------------------------------------------

	/**
	 * The populated canonical document: every exported field present, both the occupied and the
	 * empty item shape, a target, active prayers, and session experience in more than one skill.
	 * Every value is obviously synthetic, and no real account, character, item, NPC, filesystem
	 * path, or copied game material appears.
	 */
	static TelemetrySnapshot populatedFixture()
	{
		return TelemetrySnapshot.builder()
			.envelope(INSTANCE_ID, 7L, EMITTED_AT)
			.session(true, "LOGGED_IN", true, 302, 87, 1_769_999_940_000L)
			.vitals(73, 75, 40, 52, 88, 65, 12)
			.combat(
				"accurate",
				Arrays.asList("protect_from_melee", "piety"),
				TelemetryTarget.npc(4001, "Sample target dummy", 21, 18, 30, false))
			.equipment(fixtureEquipment())
			.inventory(12, 16, fixtureInventory())
			.xp("woodcutting", 65, 1_769_999_998_000L, Arrays.asList(
				new TelemetrySkillGain("attack", 240, 40, 1_769_999_990_000L),
				new TelemetrySkillGain("woodcutting", 130, 65, 1_769_999_998_000L),
				new TelemetrySkillGain("fishing", 90, 30, 1_769_999_995_000L)))
			.skills(fixtureSkillStates())
			.account(0)
			.quests(231)
			.slayer(42, 494)
			.grandExchange(fixtureGrandExchangeSlots())
			.build();
	}

	/**
	 * The logged-out canonical document: the complete schema-3 shape with every player-derived
	 * value nulled, which is what a reader sees whenever the snapshot carries no valid player
	 * data. Every schema-3 group reports itself unavailable here, which is the shape a reader has
	 * to handle and so the shape a fixture has to show.
	 */
	static TelemetrySnapshot loggedOutFixture()
	{
		return TelemetrySnapshot.builder()
			.envelope(INSTANCE_ID, 42L, EMITTED_AT)
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
			.build();
	}

	/**
	 * One entry per skill the client enumerates today, in that order, with a spread that shows every
	 * shape a reader must handle: a boost above the base level, a drain below it, an untrained skill
	 * at level one with no experience, and a maximum level.
	 *
	 * Hand-written rather than generated from the client's enumeration, so this document stays the
	 * same bytes whatever version of the client the build resolves. What keeps it honest about the
	 * live enumeration is a separate lifecycle test, which drives the real reader and checks it
	 * exports every skill the enumeration defines, in its order.
	 */
	private static List<TelemetrySkillState> fixtureSkillStates()
	{
		List<TelemetrySkillState> skills = new ArrayList<>();
		skills.add(new TelemetrySkillState("attack", 70, 74, 737_627));
		skills.add(new TelemetrySkillState("defence", 70, 70, 737_627));
		skills.add(new TelemetrySkillState("strength", 72, 79, 992_895));
		skills.add(new TelemetrySkillState("hitpoints", 75, 73, 1_210_421));
		skills.add(new TelemetrySkillState("ranged", 61, 61, 320_000));
		skills.add(new TelemetrySkillState("prayer", 52, 40, 131_000));
		skills.add(new TelemetrySkillState("magic", 75, 75, 1_210_421));
		skills.add(new TelemetrySkillState("cooking", 68, 68, 645_000));
		skills.add(new TelemetrySkillState("woodcutting", 80, 80, 1_986_068));
		skills.add(new TelemetrySkillState("fletching", 55, 55, 170_000));
		skills.add(new TelemetrySkillState("fishing", 66, 66, 510_000));
		skills.add(new TelemetrySkillState("firemaking", 61, 61, 320_000));
		skills.add(new TelemetrySkillState("crafting", 54, 54, 155_000));
		skills.add(new TelemetrySkillState("smithing", 50, 50, 101_333));
		skills.add(new TelemetrySkillState("mining", 62, 62, 350_000));
		skills.add(new TelemetrySkillState("herblore", 43, 43, 51_000));
		skills.add(new TelemetrySkillState("agility", 58, 58, 240_000));
		skills.add(new TelemetrySkillState("thieving", 49, 49, 92_000));
		skills.add(new TelemetrySkillState("slayer", 63, 63, 380_000));
		skills.add(new TelemetrySkillState("farming", 45, 45, 61_500));
		skills.add(new TelemetrySkillState("runecraft", 42, 42, 46_000));
		skills.add(new TelemetrySkillState("hunter", 40, 40, 37_224));
		skills.add(new TelemetrySkillState("construction", 99, 99, 13_034_431));
		skills.add(new TelemetrySkillState("sailing", 1, 1, 0));
		return skills;
	}

	/**
	 * Eight offer slots, which is what the client exposes today. Between them they show a buy in
	 * progress, a completed sale, a cancelled offer, and the empty shape, so a reader sees both the
	 * populated entry and the state-only one.
	 */
	private static List<TelemetryGrandExchangeSlot> fixtureGrandExchangeSlots()
	{
		List<TelemetryGrandExchangeSlot> slots = new ArrayList<>();
		slots.add(TelemetryGrandExchangeSlot.offer("buying", 3001, 170, 1_000, 250, 42_500));
		slots.add(TelemetryGrandExchangeSlot.offer("selling", 3002, 4_800, 24, 24, 115_200));
		slots.add(TelemetryGrandExchangeSlot.offer("cancelled_buy", 3003, 96, 500, 120, 11_520));
		slots.add(TelemetryGrandExchangeSlot.empty("empty"));
		slots.add(TelemetryGrandExchangeSlot.empty("empty"));
		slots.add(TelemetryGrandExchangeSlot.empty("empty"));
		slots.add(TelemetryGrandExchangeSlot.empty("empty"));
		slots.add(TelemetryGrandExchangeSlot.empty("empty"));
		return slots;
	}

	/** Nine occupied slots and two empty ones, so both item shapes appear in the fixture. */
	private static List<TelemetryItemSlot> fixtureEquipment()
	{
		Map<String, TelemetryItemSlot> byName = new LinkedHashMap<>();
		byName.put("head", TelemetryItemSlot.of(1101, 1, "Sample helm"));
		byName.put("cape", TelemetryItemSlot.of(1102, 1, "Sample cape"));
		byName.put("amulet", TelemetryItemSlot.of(1103, 1, "Sample amulet"));
		byName.put("weapon", TelemetryItemSlot.of(1104, 1, "Sample blade"));
		byName.put("body", TelemetryItemSlot.of(1105, 1, "Sample platebody"));
		byName.put("shield", TelemetryItemSlot.EMPTY);
		byName.put("legs", TelemetryItemSlot.of(1107, 1, "Sample platelegs"));
		byName.put("gloves", TelemetryItemSlot.of(1108, 1, "Sample gloves"));
		byName.put("boots", TelemetryItemSlot.of(1109, 1, "Sample boots"));
		byName.put("ring", TelemetryItemSlot.EMPTY);
		byName.put("ammo", TelemetryItemSlot.of(1111, 350, "Sample bolts"));
		assertEquals("the fixture must name every exported equipment slot exactly once",
			TelemetrySnapshot.EQUIPMENT_SLOTS, new ArrayList<>(byName.keySet()));
		return new ArrayList<>(byName.values());
	}

	/** Twelve occupied slots, one of them a stack, and sixteen empty ones. */
	private static List<TelemetryItemSlot> fixtureInventory()
	{
		List<TelemetryItemSlot> slots = new ArrayList<>();
		slots.add(TelemetryItemSlot.of(2001, 1, "Sample pickaxe"));
		slots.add(TelemetryItemSlot.of(2002, 1, "Sample hatchet"));
		slots.add(TelemetryItemSlot.of(2003, 4, "Sample loaf"));
		slots.add(TelemetryItemSlot.of(2004, 1_500, "Sample coin pile"));
		slots.add(TelemetryItemSlot.of(2005, 3, "Sample potion"));
		slots.add(TelemetryItemSlot.of(2006, 1, "Sample teleport tablet"));
		slots.add(TelemetryItemSlot.of(2007, 27, "Sample logs"));
		slots.add(TelemetryItemSlot.of(2008, 12, "Sample ore"));
		slots.add(TelemetryItemSlot.of(2009, 1, "Sample gem"));
		slots.add(TelemetryItemSlot.of(2010, 6, "Sample herb"));
		slots.add(TelemetryItemSlot.of(2011, 2, "Sample plank"));
		slots.add(TelemetryItemSlot.of(2012, 1, "Sample seed pouch"));
		while (slots.size() < TelemetrySnapshot.INVENTORY_SLOTS)
		{
			slots.add(TelemetryItemSlot.EMPTY);
		}
		return slots;
	}

	// --- exact documents ---------------------------------------------------------------------

	@Test
	public void populatedSnapshotSerializesToTheExactSchemaThreeDocument()
	{
		assertEquals(
			"{\"schema\":3,\"source\":\"runelite\",\"instanceId\":\""
				+ INSTANCE_ID
				+ "\",\"seq\":7,\"emittedAt\":1770000000000,\"session\":{\"pluginActive\":true,\"gameState\":\"LOGGED_IN\","
				+ "\"loggedIn\":true,\"world\":302,\"combatLevel\":87,\"trackingStartedAt\":1769999940000},"
				+ "\"vitals\":{\"hitpointsCurrent\":73,\"hitpointsBase\":75,\"prayerCurrent\":40,"
				+ "\"prayerBase\":52,\"runEnergyPercent\":88,\"specialAttackPercent\":65,\"weightKg\":12},"
				+ "\"combat\":{\"attackStyle\":\"accurate\",\"activePrayers\":[\"protect_from_melee\","
				+ "\"piety\"],\"target\":{\"kind\":\"npc\",\"id\":4001,\"name\":\"Sample target dummy\","
				+ "\"combatLevel\":21,\"healthRatio\":18,\"healthScale\":30,\"dead\":false}},\"equipment\":{\"slots\":[{\"slot\":\"head\","
				+ "\"itemId\":1101,\"quantity\":1,\"name\":\"Sample helm\"},{\"slot\":\"cape\",\"itemId\":1102,"
				+ "\"quantity\":1,\"name\":\"Sample cape\"},{\"slot\":\"amulet\",\"itemId\":1103,\"quantity\":1,"
				+ "\"name\":\"Sample amulet\"},{\"slot\":\"weapon\",\"itemId\":1104,\"quantity\":1,\"name\":\"Sample blade\"},"
				+ "{\"slot\":\"body\",\"itemId\":1105,\"quantity\":1,\"name\":\"Sample platebody\"},{\"slot\":\"shield\","
				+ "\"itemId\":null,\"quantity\":null,\"name\":null},{\"slot\":\"legs\",\"itemId\":1107,"
				+ "\"quantity\":1,\"name\":\"Sample platelegs\"},{\"slot\":\"gloves\",\"itemId\":1108,"
				+ "\"quantity\":1,\"name\":\"Sample gloves\"},{\"slot\":\"boots\",\"itemId\":1109,\"quantity\":1,"
				+ "\"name\":\"Sample boots\"},{\"slot\":\"ring\",\"itemId\":null,\"quantity\":null,\"name\":null},"
				+ "{\"slot\":\"ammo\",\"itemId\":1111,\"quantity\":350,\"name\":\"Sample bolts\"}]},\"inventory\":{\"usedSlots\":12,"
				+ "\"freeSlots\":16,\"slots\":[{\"slot\":0,\"itemId\":2001,\"quantity\":1,\"name\":\"Sample pickaxe\"},"
				+ "{\"slot\":1,\"itemId\":2002,\"quantity\":1,\"name\":\"Sample hatchet\"},{\"slot\":2,"
				+ "\"itemId\":2003,\"quantity\":4,\"name\":\"Sample loaf\"},{\"slot\":3,\"itemId\":2004,"
				+ "\"quantity\":1500,\"name\":\"Sample coin pile\"},{\"slot\":4,\"itemId\":2005,\"quantity\":3,"
				+ "\"name\":\"Sample potion\"},{\"slot\":5,\"itemId\":2006,\"quantity\":1,\"name\":\"Sample teleport tablet\"},"
				+ "{\"slot\":6,\"itemId\":2007,\"quantity\":27,\"name\":\"Sample logs\"},{\"slot\":7,"
				+ "\"itemId\":2008,\"quantity\":12,\"name\":\"Sample ore\"},{\"slot\":8,\"itemId\":2009,"
				+ "\"quantity\":1,\"name\":\"Sample gem\"},{\"slot\":9,\"itemId\":2010,\"quantity\":6,"
				+ "\"name\":\"Sample herb\"},{\"slot\":10,\"itemId\":2011,\"quantity\":2,\"name\":\"Sample plank\"},"
				+ "{\"slot\":11,\"itemId\":2012,\"quantity\":1,\"name\":\"Sample seed pouch\"},{\"slot\":12,"
				+ "\"itemId\":null,\"quantity\":null,\"name\":null},{\"slot\":13,\"itemId\":null,\"quantity\":null,"
				+ "\"name\":null},{\"slot\":14,\"itemId\":null,\"quantity\":null,\"name\":null},{\"slot\":15,"
				+ "\"itemId\":null,\"quantity\":null,\"name\":null},{\"slot\":16,\"itemId\":null,\"quantity\":null,"
				+ "\"name\":null},{\"slot\":17,\"itemId\":null,\"quantity\":null,\"name\":null},{\"slot\":18,"
				+ "\"itemId\":null,\"quantity\":null,\"name\":null},{\"slot\":19,\"itemId\":null,\"quantity\":null,"
				+ "\"name\":null},{\"slot\":20,\"itemId\":null,\"quantity\":null,\"name\":null},{\"slot\":21,"
				+ "\"itemId\":null,\"quantity\":null,\"name\":null},{\"slot\":22,\"itemId\":null,\"quantity\":null,"
				+ "\"name\":null},{\"slot\":23,\"itemId\":null,\"quantity\":null,\"name\":null},{\"slot\":24,"
				+ "\"itemId\":null,\"quantity\":null,\"name\":null},{\"slot\":25,\"itemId\":null,\"quantity\":null,"
				+ "\"name\":null},{\"slot\":26,\"itemId\":null,\"quantity\":null,\"name\":null},{\"slot\":27,"
				+ "\"itemId\":null,\"quantity\":null,\"name\":null}]},\"xp\":{\"lastSkill\":\"woodcutting\","
				+ "\"lastDelta\":65,\"lastChangedAt\":1769999998000,\"skills\":[{\"skill\":\"attack\","
				+ "\"gained\":240,\"lastDelta\":40,\"lastChangedAt\":1769999990000},{\"skill\":\"woodcutting\","
				+ "\"gained\":130,\"lastDelta\":65,\"lastChangedAt\":1769999998000},{\"skill\":\"fishing\","
				+ "\"gained\":90,\"lastDelta\":30,\"lastChangedAt\":1769999995000}]},\"capabilities\":{\"skills\":\"supported\","
				+ "\"account\":\"supported\",\"quests\":\"supported\",\"slayer\":\"supported\",\"grandExchange\":\"supported\"},"
				+ "\"skills\":{\"entries\":[{\"skill\":\"attack\",\"level\":70,\"boostedLevel\":74,\"xp\":737627},"
				+ "{\"skill\":\"defence\",\"level\":70,\"boostedLevel\":70,\"xp\":737627},{\"skill\":\"strength\","
				+ "\"level\":72,\"boostedLevel\":79,\"xp\":992895},{\"skill\":\"hitpoints\",\"level\":75,"
				+ "\"boostedLevel\":73,\"xp\":1210421},{\"skill\":\"ranged\",\"level\":61,\"boostedLevel\":61,"
				+ "\"xp\":320000},{\"skill\":\"prayer\",\"level\":52,\"boostedLevel\":40,\"xp\":131000},"
				+ "{\"skill\":\"magic\",\"level\":75,\"boostedLevel\":75,\"xp\":1210421},{\"skill\":\"cooking\","
				+ "\"level\":68,\"boostedLevel\":68,\"xp\":645000},{\"skill\":\"woodcutting\",\"level\":80,"
				+ "\"boostedLevel\":80,\"xp\":1986068},{\"skill\":\"fletching\",\"level\":55,\"boostedLevel\":55,"
				+ "\"xp\":170000},{\"skill\":\"fishing\",\"level\":66,\"boostedLevel\":66,\"xp\":510000},"
				+ "{\"skill\":\"firemaking\",\"level\":61,\"boostedLevel\":61,\"xp\":320000},{\"skill\":\"crafting\","
				+ "\"level\":54,\"boostedLevel\":54,\"xp\":155000},{\"skill\":\"smithing\",\"level\":50,"
				+ "\"boostedLevel\":50,\"xp\":101333},{\"skill\":\"mining\",\"level\":62,\"boostedLevel\":62,"
				+ "\"xp\":350000},{\"skill\":\"herblore\",\"level\":43,\"boostedLevel\":43,\"xp\":51000},"
				+ "{\"skill\":\"agility\",\"level\":58,\"boostedLevel\":58,\"xp\":240000},{\"skill\":\"thieving\","
				+ "\"level\":49,\"boostedLevel\":49,\"xp\":92000},{\"skill\":\"slayer\",\"level\":63,"
				+ "\"boostedLevel\":63,\"xp\":380000},{\"skill\":\"farming\",\"level\":45,\"boostedLevel\":45,"
				+ "\"xp\":61500},{\"skill\":\"runecraft\",\"level\":42,\"boostedLevel\":42,\"xp\":46000},"
				+ "{\"skill\":\"hunter\",\"level\":40,\"boostedLevel\":40,\"xp\":37224},{\"skill\":\"construction\","
				+ "\"level\":99,\"boostedLevel\":99,\"xp\":13034431},{\"skill\":\"sailing\",\"level\":1,"
				+ "\"boostedLevel\":1,\"xp\":0}]},\"account\":{\"type\":\"normal\",\"typeId\":0},\"quests\":{\"points\":231},"
				+ "\"slayer\":{\"remaining\":42,\"taskCreatureId\":494},\"grandExchange\":{\"slots\":[{\"slot\":0,"
				+ "\"state\":\"buying\",\"itemId\":3001,\"price\":170,\"totalQuantity\":1000,\"quantityTransacted\":250,"
				+ "\"spent\":42500},{\"slot\":1,\"state\":\"selling\",\"itemId\":3002,\"price\":4800,"
				+ "\"totalQuantity\":24,\"quantityTransacted\":24,\"spent\":115200},{\"slot\":2,\"state\":\"cancelled_buy\","
				+ "\"itemId\":3003,\"price\":96,\"totalQuantity\":500,\"quantityTransacted\":120,"
				+ "\"spent\":11520},{\"slot\":3,\"state\":\"empty\",\"itemId\":null,\"price\":null,\"totalQuantity\":null,"
				+ "\"quantityTransacted\":null,\"spent\":null},{\"slot\":4,\"state\":\"empty\",\"itemId\":null,"
				+ "\"price\":null,\"totalQuantity\":null,\"quantityTransacted\":null,\"spent\":null},"
				+ "{\"slot\":5,\"state\":\"empty\",\"itemId\":null,\"price\":null,\"totalQuantity\":null,"
				+ "\"quantityTransacted\":null,\"spent\":null},{\"slot\":6,\"state\":\"empty\",\"itemId\":null,"
				+ "\"price\":null,\"totalQuantity\":null,\"quantityTransacted\":null,\"spent\":null},"
				+ "{\"slot\":7,\"state\":\"empty\",\"itemId\":null,\"price\":null,\"totalQuantity\":null,"
				+ "\"quantityTransacted\":null,\"spent\":null}]}}",
			populatedFixture().toJson());
	}

	@Test
	public void loggedOutSnapshotKeepsEveryKeyAndNullsEveryPlayerValue()
	{
		assertEquals(
			"{\"schema\":3,\"source\":\"runelite\",\"instanceId\":\""
				+ INSTANCE_ID
				+ "\",\"seq\":42,\"emittedAt\":1770000000000,\"session\":{\"pluginActive\":true,\"gameState\":\"LOGIN_SCREEN\","
				+ "\"loggedIn\":false,\"world\":null,\"combatLevel\":null,\"trackingStartedAt\":null},"
				+ "\"vitals\":{\"hitpointsCurrent\":null,\"hitpointsBase\":null,\"prayerCurrent\":null,"
				+ "\"prayerBase\":null,\"runEnergyPercent\":null,\"specialAttackPercent\":null,"
				+ "\"weightKg\":null},\"combat\":{\"attackStyle\":null,\"activePrayers\":null,\"target\":null},"
				+ "\"equipment\":{\"slots\":null},\"inventory\":{\"usedSlots\":null,\"freeSlots\":null,"
				+ "\"slots\":null},\"xp\":{\"lastSkill\":null,\"lastDelta\":null,\"lastChangedAt\":null,"
				+ "\"skills\":null},\"capabilities\":{\"skills\":\"unavailable\",\"account\":\"unavailable\","
				+ "\"quests\":\"unavailable\",\"slayer\":\"unavailable\",\"grandExchange\":\"unavailable\"},"
				+ "\"skills\":{\"entries\":null},\"account\":{\"type\":null,\"typeId\":null},\"quests\":{\"points\":null},"
				+ "\"slayer\":{\"remaining\":null,\"taskCreatureId\":null},\"grandExchange\":{\"slots\":null}}",
			loggedOutFixture().toJson());
	}

	// --- key sets and key order --------------------------------------------------------------

	@Test
	public void topLevelKeysAreExactlyTheAuthorizedSeventeenInOrder()
	{
		assertEquals(AUTHORIZED_TOP_LEVEL_KEYS, keysByDepth(populatedFixture().toJson()).get(1));
		assertEquals(AUTHORIZED_TOP_LEVEL_KEYS, keysByDepth(loggedOutFixture().toJson()).get(1));
	}

	@Test
	public void secondLevelKeysAreExactlyTheAuthorizedOnesInOrder()
	{
		assertEquals(AUTHORIZED_SECOND_LEVEL_KEYS,
			keysByDepth(populatedFixture().toJson()).get(2));
		assertEquals("the shape does not change when there is no player data",
			AUTHORIZED_SECOND_LEVEL_KEYS, keysByDepth(loggedOutFixture().toJson()).get(2));
	}

	/**
	 * The third level is the target object, then every equipment entry, every inventory entry, every
	 * session-gain entry, every skill entry, and every offer entry, each with its own fixed key
	 * order. Written out in document order rather than as a set, because the order is the contract.
	 */
	@Test
	public void thirdLevelKeysAreEveryFixedEntryInDocumentOrder()
	{
		assertEquals(expectedThirdLevelKeys(), keysByDepth(populatedFixture().toJson()).get(3));
	}

	private static List<String> expectedThirdLevelKeys()
	{
		List<String> expected = new ArrayList<>(TARGET_KEYS);
		for (int i = 0; i < TelemetrySnapshot.EQUIPMENT_SLOTS.size(); i++)
		{
			expected.addAll(ITEM_SLOT_KEYS);
		}
		for (int i = 0; i < TelemetrySnapshot.INVENTORY_SLOTS; i++)
		{
			expected.addAll(ITEM_SLOT_KEYS);
		}
		for (int i = 0; i < FIXTURE_SESSION_GAINS; i++)
		{
			expected.addAll(SKILL_GAIN_KEYS);
		}
		for (int i = 0; i < FIXTURE_SKILLS; i++)
		{
			expected.addAll(SKILL_STATE_KEYS);
		}
		for (int i = 0; i < FIXTURE_OFFER_SLOTS; i++)
		{
			expected.addAll(OFFER_KEYS);
		}
		return expected;
	}

	@Test
	public void aLoggedOutDocumentHasNoThirdLevelAtAll()
	{
		Map<Integer, List<String>> keys = keysByDepth(loggedOutFixture().toJson());
		assertEquals("only the envelope and its eleven objects", 2, keys.size());
	}

	@Test
	public void documentHasNoKeysBeyondTheAuthorizedSchema()
	{
		Map<Integer, List<String>> keys = keysByDepth(populatedFixture().toJson());
		assertEquals("no nesting beyond three levels of objects", 3, keys.size());
		assertEquals(
			AUTHORIZED_TOP_LEVEL_KEYS.size() + AUTHORIZED_SECOND_LEVEL_KEYS.size()
				+ expectedThirdLevelKeys().size(),
			keys.get(1).size() + keys.get(2).size() + keys.get(3).size());
	}

	@Test
	public void thereAreExactlyElevenEquipmentSlotsAndTwentyEightInventorySlots()
	{
		assertEquals(11, TelemetrySnapshot.EQUIPMENT_SLOTS.size());
		assertEquals(28, TelemetrySnapshot.INVENTORY_SLOTS);
		assertEquals(
			Arrays.asList("head", "cape", "amulet", "weapon", "body", "shield", "legs", "gloves",
				"boots", "ring", "ammo"),
			TelemetrySnapshot.EQUIPMENT_SLOTS);

		String json = populatedFixture().toJson();
		assertEquals(11, countOccurrences(json, "{\"slot\":\""));
		// Offer entries open with a numbered slot too, so they are counted and subtracted rather
		// than left to make the inventory look larger than it is.
		int offers = countOccurrences(json, ",\"state\":\"");
		assertEquals(FIXTURE_OFFER_SLOTS, offers);
		assertEquals(28, countOccurrences(json, "{\"slot\":") - 11 - offers);
		for (int slot = 0; slot < TelemetrySnapshot.INVENTORY_SLOTS; slot++)
		{
			assertTrue("inventory slot " + slot + " must appear exactly once in position",
				json.contains("{\"slot\":" + slot + ",\"itemId\":"));
		}
	}

	// --- capabilities --------------------------------------------------------------------------

	/**
	 * The one property the capability block exists to carry: a group says supported exactly when it
	 * is carrying values. Checked over every group and over both canonical documents, so neither
	 * half of the claim can drift on its own.
	 */
	@Test
	public void everyCapabilityAgreesWithWhetherItsGroupCarriesValues()
	{
		for (TelemetrySnapshot snapshot
			: Arrays.asList(populatedFixture(), loggedOutFixture()))
		{
			String json = snapshot.toJson();
			for (String group : TelemetrySnapshot.CAPABILITY_GROUPS)
			{
				boolean supported = snapshot.capability(group) == TelemetryCapability.SUPPORTED;
				assertEquals("capability for " + group,
					supported ? "supported" : "unavailable",
					capabilityIn(json, group));
				assertEquals(group + " must carry values only while it says it does",
					supported, groupCarriesValues(json, group));
			}
		}
	}

	@Test
	public void everyGroupIsSupportedInThePopulatedDocumentAndUnavailableWhenLoggedOut()
	{
		for (String group : TelemetrySnapshot.CAPABILITY_GROUPS)
		{
			assertEquals(group, TelemetryCapability.SUPPORTED, populatedFixture().capability(group));
			assertEquals(group, TelemetryCapability.UNAVAILABLE, loggedOutFixture().capability(group));
		}
	}

	/**
	 * One group failing to read must not take the others down with it. This is what "missing facts
	 * degrade explicitly" means in the document: the unread group is null and says so, and every
	 * other group is untouched.
	 */
	@Test
	public void oneUnavailableGroupLeavesEveryOtherGroupSupported()
	{
		for (String absent : TelemetrySnapshot.CAPABILITY_GROUPS)
		{
			TelemetrySnapshot snapshot = allGroupsExcept(absent);
			assertEquals(absent, TelemetryCapability.UNAVAILABLE, snapshot.capability(absent));
			for (String group : TelemetrySnapshot.CAPABILITY_GROUPS)
			{
				if (!group.equals(absent))
				{
					assertEquals(absent + " must not disturb " + group,
						TelemetryCapability.SUPPORTED, snapshot.capability(group));
				}
			}
			// The whole schema-2 half of the document is unaffected too.
			assertTrue(absent, snapshot.toJson().contains("\"loggedIn\":true"));
		}
	}

	/** Every group present except the named one, which is left unread. */
	private static TelemetrySnapshot allGroupsExcept(String absent)
	{
		TelemetrySnapshot.Builder b = TelemetrySnapshot.builder()
			.envelope(INSTANCE_ID, 1L, EMITTED_AT)
			.session(true, "LOGGED_IN", true, 302, 87, EMITTED_AT)
			.skills(absent.equals("skills") ? null : fixtureSkillStates())
			.account(absent.equals("account") ? null : Integer.valueOf(0))
			.quests(absent.equals("quests") ? null : Integer.valueOf(231))
			.grandExchange(absent.equals("grandExchange") ? null : fixtureGrandExchangeSlots());
		return absent.equals("slayer")
			? b.slayer(null, null).build()
			: b.slayer(42, 494).build();
	}

	private static String capabilityIn(String json, String group)
	{
		String at = "\"capabilities\":{";
		String block = json.substring(json.indexOf(at) + at.length());
		block = block.substring(0, block.indexOf('}'));
		for (String entry : block.split(","))
		{
			String[] pair = entry.split(":");
			if (pair[0].equals("\"" + group + "\""))
			{
				return pair[1].replace("\"", "");
			}
		}
		fail("no capability entry for " + group);
		return null;
	}

	/**
	 * Whether the named group's own object carries anything but nulls. The unavailable shape is
	 * every field of the group written as null, and nothing else is, so matching that shape exactly
	 * is the whole test.
	 */
	private static boolean groupCarriesValues(String json, String group)
	{
		int at = json.lastIndexOf("\"" + group + "\":{");
		assertTrue("no group object for " + group, at >= 0);
		String block = json.substring(at + group.length() + 4);
		int depth = 1;
		int end = 0;
		while (end < block.length() && depth > 0)
		{
			char c = block.charAt(end);
			if (c == '{' || c == '[')
			{
				depth++;
			}
			else if (c == '}' || c == ']')
			{
				depth--;
			}
			end++;
		}
		String body = block.substring(0, end - 1);
		return !body.matches("(\"[A-Za-z]+\":null)(,\"[A-Za-z]+\":null)*");
	}

	@Test
	public void anAccountModeThisBuildCannotNameStillReportsItsReading()
	{
		String json = TelemetrySnapshot.builder()
			.envelope(INSTANCE_ID, 1L, EMITTED_AT)
			.session(true, "LOGGED_IN", true, 302, 87, EMITTED_AT)
			.account(99)
			.build()
			.toJson();
		// The name is unknown, the reading is not, and the group is still supported: rounding an
		// unknown mode down to "normal" would be the fabricated default the schema refuses.
		assertTrue(json, json.contains("\"account\":{\"type\":null,\"typeId\":99}"));
		assertTrue(json, json.contains("\"account\":\"supported\""));
	}

	@Test
	public void everyNamedAccountModeSerializesItsOwnName()
	{
		for (TelemetryAccountType type : TelemetryAccountType.values())
		{
			String json = TelemetrySnapshot.builder()
				.envelope(INSTANCE_ID, 1L, EMITTED_AT)
				.session(true, "LOGGED_IN", true, 302, 87, EMITTED_AT)
				.account(type.getTypeId())
				.build()
				.toJson();
			assertTrue(json, json.contains("\"account\":{\"type\":\"" + type.exported()
				+ "\",\"typeId\":" + type.getTypeId() + "}"));
		}
	}

	@Test
	public void aSlayerTaskCreatureCannotBeReportedWithoutItsRemainingCount()
	{
		try
		{
			TelemetrySnapshot.builder()
				.envelope(INSTANCE_ID, 1L, EMITTED_AT)
				.session(true, "LOGGED_IN", true, 302, 87, EMITTED_AT)
				.slayer(null, 494)
				.build();
			fail("a creature without a count must not build");
		}
		catch (IllegalArgumentException expected)
		{
			assertTrue(expected.getMessage(), expected.getMessage().contains("remaining count"));
		}
	}

	@Test
	public void noTaskAssignedIsASupportedAnswerRatherThanAnUnavailableOne()
	{
		TelemetrySnapshot snapshot = TelemetrySnapshot.builder()
			.envelope(INSTANCE_ID, 1L, EMITTED_AT)
			.session(true, "LOGGED_IN", true, 302, 87, EMITTED_AT)
			.slayer(0, null)
			.build();
		assertEquals(TelemetryCapability.SUPPORTED, snapshot.capability("slayer"));
		assertTrue(snapshot.toJson(),
			snapshot.toJson().contains("\"slayer\":{\"remaining\":0,\"taskCreatureId\":null}"));
	}

	@Test
	public void anEmptyOfferSlotCarriesItsStateAndNothingElse()
	{
		String json = offersOf(TelemetryGrandExchangeSlot.empty("empty"));
		assertTrue(json, json.contains("{\"slot\":0,\"state\":\"empty\",\"itemId\":null,"
			+ "\"price\":null,\"totalQuantity\":null,\"quantityTransacted\":null,\"spent\":null}"));
	}

	/**
	 * A slot whose readings contradict each other is exported as unavailable rather than as an offer
	 * carrying a figure that cannot be true.
	 */
	@Test
	public void anOfferTransactingMoreThanItOrderedIsNotExportedAsAnOffer()
	{
		String json = offersOf(
			TelemetryGrandExchangeSlot.offer("buying", 3001, 170, 10, 11, 1_700));
		assertTrue(json, json.contains("{\"slot\":0,\"state\":null,\"itemId\":null,"
			+ "\"price\":null,\"totalQuantity\":null,\"quantityTransacted\":null,\"spent\":null}"));
	}

	@Test
	public void anOfferPriceAppearsOnlyInsideTheGrandExchangeSlots()
	{
		String json = populatedFixture().toJson();
		int offers = json.indexOf("\"grandExchange\":{");
		assertTrue(json, offers > 0);
		assertEquals("a price outside the offer slots is a valuation, which schema 3 refuses",
			0, countOccurrences(json.substring(0, offers), "\"price\":"));
		assertEquals(FIXTURE_OFFER_SLOTS, countOccurrences(json, "\"price\":"));
	}

	private static String offersOf(TelemetryGrandExchangeSlot... slots)
	{
		return TelemetrySnapshot.builder()
			.envelope(INSTANCE_ID, 1L, EMITTED_AT)
			.session(true, "LOGGED_IN", true, 302, 87, EMITTED_AT)
			.grandExchange(Arrays.asList(slots))
			.build()
			.toJson();
	}

	// --- forbidden content --------------------------------------------------------------------

	@Test
	public void forbiddenIdentitySocialBankValuationLocationAndControlFieldsAreAbsent()
	{
		List<String> present = new ArrayList<>();
		for (String json : Arrays.asList(populatedFixture().toJson(), loggedOutFixture().toJson()))
		{
			for (String key : FORBIDDEN_KEYS)
			{
				if (json.contains("\"" + key + "\":") && !present.contains(key))
				{
					present.add(key);
				}
			}
		}
		assertEquals("forbidden fields present in schema 3", new ArrayList<String>(), present);
	}

	@Test
	public void theOnlyPermittedTargetKindIsAnNpc()
	{
		assertEquals("npc", TelemetryTarget.KIND);
		String json = populatedFixture().toJson();
		assertTrue(json, json.contains("\"kind\":\"npc\""));
		// The kind is a constant in the serializer, so no other value has a path into the
		// document at all.
		assertEquals(1, countOccurrences(json, "\"kind\":"));
	}

	// --- bounds, escaping, and size -----------------------------------------------------------

	@Test
	public void stringValuesAreJsonEscaped()
	{
		// A quote, a backslash, a newline, and a bare control character. RuneLite's own game
		// state names contain none of these, but a serializer able to emit them raw could
		// produce a document a reader cannot parse.
		String hostile = "a\"b\\c\nd" + (char) 1 + "e";
		String json = TelemetrySnapshot.builder()
			.envelope(INSTANCE_ID, 0L, EMITTED_AT)
			.session(false, hostile, false, null, null, null)
			.build()
			.toJson();
		assertTrue(json, json.contains("\"gameState\":\"a\\\"b\\\\c\\nd\\u0001e\""));
	}

	@Test
	public void itemAndNpcNamesAreEscapedAndBounded()
	{
		// Every character placed inside the name rather than at its edges: leading and trailing
		// whitespace and control characters are trimmed away before serialization ever sees them.
		String hostileItem = "we\"ird\\name\twith\ncontrol" + (char) 2 + "inside";
		TelemetrySnapshot snapshot = withNames(hostileItem, "np\"c\\na\rme");
		String json = snapshot.toJson();
		assertTrue(json, json.contains(
			"\"name\":\"np\\\"c\\\\na\\rme\""));
		assertTrue(json, json.contains(
			"\"name\":\"we\\\"ird\\\\name\\twith\\ncontrol\\u0002inside\""));

		String longName = repeat('x', TelemetrySnapshot.MAX_ITEM_NAME_CHARS + 40);
		String longNpc = repeat('y', TelemetrySnapshot.MAX_NPC_NAME_CHARS + 40);
		String bounded = withNames(longName, longNpc).toJson();
		assertTrue(bounded, bounded.contains(
			"\"name\":\"" + repeat('x', TelemetrySnapshot.MAX_ITEM_NAME_CHARS) + "\""));
		assertTrue(bounded, bounded.contains(
			"\"name\":\"" + repeat('y', TelemetrySnapshot.MAX_NPC_NAME_CHARS) + "\""));
		assertFalse("no name may exceed its bound",
			bounded.contains(repeat('x', TelemetrySnapshot.MAX_ITEM_NAME_CHARS + 1)));
	}

	/**
	 * Truncation must not split a surrogate pair, which would leave a lone surrogate in the
	 * document.
	 */
	@Test
	public void boundingNeverSplitsASurrogatePair()
	{
		StringBuilder name = new StringBuilder(repeat('a', TelemetrySnapshot.MAX_ITEM_NAME_CHARS - 1));
		// A supplementary character straddling the bound.
		name.append("😀").append("tail");
		String json = withNames(name.toString(), "npc").toJson();
		int at = json.indexOf("\"itemId\":9001");
		assertTrue(json, at > 0);
		String tail = json.substring(at);
		assertFalse("a high surrogate must never be emitted without its low half",
			tail.contains("\uD83D\"") || tail.contains("\uD83D,"));
	}

	@Test
	public void gameStateAndAttackStyleAndPrayerAndSkillNamesAreBounded()
	{
		String longStyle = repeat('s', TelemetrySnapshot.MAX_ATTACK_STYLE_CHARS + 10);
		String longPrayer = repeat('p', TelemetrySnapshot.MAX_PRAYER_CHARS + 10);
		String longSkill = repeat('k', TelemetrySnapshot.MAX_SKILL_CHARS + 10);
		String longState = repeat('g', TelemetrySnapshot.MAX_GAME_STATE_CHARS + 10);
		String longInstance = repeat('i', TelemetrySnapshot.MAX_INSTANCE_ID_CHARS + 10);

		String json = TelemetrySnapshot.builder()
			.envelope(longInstance, 0L, EMITTED_AT)
			.session(true, longState, true, null, null, null)
			.combat(longStyle, Collections.singletonList(longPrayer), null)
			.xp(longSkill, 1, EMITTED_AT, Collections.singletonList(
				new TelemetrySkillGain(longSkill, 1, 1, EMITTED_AT)))
			.build()
			.toJson();

		assertTrue(json, json.contains(
			"\"instanceId\":\"" + repeat('i', TelemetrySnapshot.MAX_INSTANCE_ID_CHARS) + "\""));
		assertTrue(json, json.contains(
			"\"gameState\":\"" + repeat('g', TelemetrySnapshot.MAX_GAME_STATE_CHARS) + "\""));
		assertTrue(json, json.contains(
			"\"attackStyle\":\"" + repeat('s', TelemetrySnapshot.MAX_ATTACK_STYLE_CHARS) + "\""));
		assertTrue(json, json.contains(
			"[\"" + repeat('p', TelemetrySnapshot.MAX_PRAYER_CHARS) + "\"]"));
		assertTrue(json, json.contains(
			"\"lastSkill\":\"" + repeat('k', TelemetrySnapshot.MAX_SKILL_CHARS) + "\""));
	}

	@Test
	public void theCanonicalDocumentsStayWellBelowTheSizeCeiling()
	{
		byte[] populated = populatedFixture().toJsonBytes();
		assertEquals(populated.length,
			populatedFixture().toJson().getBytes(StandardCharsets.UTF_8).length);
		assertTrue("populated fixture is " + populated.length + " bytes",
			populated.length < TelemetrySnapshotWriter.MAX_SNAPSHOT_BYTES);
		// Schema 3 is roughly twice schema 2's populated size, almost all of it the per-skill block
		// and the offer slots. Still a small fraction of the ceiling.
		assertTrue("populated fixture is " + populated.length + " bytes",
			populated.length < 8_192);
		assertTrue(loggedOutFixture().toJsonBytes().length < 2_048);
	}

	/**
	 * The measured worst case, which is what makes the existing 16,384-byte ceiling a
	 * demonstrated bound rather than an assumption: every collection at its maximum size, every
	 * bounded string at its maximum length, and every number at its widest.
	 */
	@Test
	public void theLargestReachableDocumentFitsInsideTheExistingCeiling()
	{
		List<TelemetryItemSlot> maxEquipment = new ArrayList<>();
		for (int i = 0; i < TelemetrySnapshot.EQUIPMENT_SLOTS.size(); i++)
		{
			maxEquipment.add(TelemetryItemSlot.of(Integer.MAX_VALUE, Integer.MAX_VALUE,
				repeat('W', TelemetrySnapshot.MAX_ITEM_NAME_CHARS)));
		}
		List<TelemetryItemSlot> maxInventory = new ArrayList<>();
		for (int i = 0; i < TelemetrySnapshot.INVENTORY_SLOTS; i++)
		{
			maxInventory.add(TelemetryItemSlot.of(Integer.MAX_VALUE, Integer.MAX_VALUE,
				repeat('W', TelemetrySnapshot.MAX_ITEM_NAME_CHARS)));
		}
		// One entry per prayer in the RuneLite enumeration, at the maximum exported name length.
		// The enumeration is not reachable from this test without a client, so the count is
		// deliberately over-stated rather than read from it.
		List<String> maxPrayers = new ArrayList<>();
		for (int i = 0; i < 80; i++)
		{
			maxPrayers.add(repeat('P', TelemetrySnapshot.MAX_PRAYER_CHARS - 3)
				+ String.format("%03d", i));
		}
		// One entry per skill the exported collection can hold, likewise over-stated.
		List<TelemetrySkillGain> maxSkills = new ArrayList<>();
		for (int i = 0; i < 40; i++)
		{
			maxSkills.add(new TelemetrySkillGain(
				repeat('S', TelemetrySnapshot.MAX_SKILL_CHARS - 3) + String.format("%03d", i),
				Integer.MAX_VALUE, Integer.MAX_VALUE, 9_999_999_999_999L));
		}

		// One entry per skill the client could enumerate, at the maximum exported name length, and
		// one offer per slot the client could expose. Both counts are over-stated for the same
		// reason as the two above: the real bound is an enumeration this test cannot read.
		List<TelemetrySkillState> maxSkillStates = new ArrayList<>();
		for (int i = 0; i < 40; i++)
		{
			maxSkillStates.add(new TelemetrySkillState(
				repeat('K', TelemetrySnapshot.MAX_SKILL_CHARS - 3) + String.format("%03d", i),
				Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
		}
		List<TelemetryGrandExchangeSlot> maxOffers = new ArrayList<>();
		for (int i = 0; i < 16; i++)
		{
			maxOffers.add(TelemetryGrandExchangeSlot.offer(
				repeat('O', TelemetrySnapshot.MAX_OFFER_STATE_CHARS), Integer.MAX_VALUE,
				Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
		}

		TelemetrySnapshot largest = TelemetrySnapshot.builder()
			.envelope(repeat('I', TelemetrySnapshot.MAX_INSTANCE_ID_CHARS), Long.MAX_VALUE,
				9_999_999_999_999L)
			.session(true, repeat('G', TelemetrySnapshot.MAX_GAME_STATE_CHARS), true,
				Integer.MAX_VALUE, Integer.MAX_VALUE, 9_999_999_999_999L)
			.vitals(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
				100, 100, Integer.MAX_VALUE)
			.combat(repeat('A', TelemetrySnapshot.MAX_ATTACK_STYLE_CHARS), maxPrayers,
				TelemetryTarget.npc(Integer.MAX_VALUE,
					repeat('N', TelemetrySnapshot.MAX_NPC_NAME_CHARS),
					Integer.MAX_VALUE, 1, 1, true))
			.equipment(maxEquipment)
			.inventory(TelemetrySnapshot.INVENTORY_SLOTS, 0, maxInventory)
			.xp(repeat('L', TelemetrySnapshot.MAX_SKILL_CHARS), Integer.MAX_VALUE,
				9_999_999_999_999L, maxSkills)
			.skills(maxSkillStates)
			.account(Integer.MAX_VALUE)
			.quests(Integer.MAX_VALUE)
			.slayer(Integer.MAX_VALUE, Integer.MAX_VALUE)
			.grandExchange(maxOffers)
			.build();

		int size = largest.toJsonBytes().length;
		assertTrue("largest reachable document measured at " + size + " bytes, ceiling is "
			+ TelemetrySnapshotWriter.MAX_SNAPSHOT_BYTES,
			size <= TelemetrySnapshotWriter.MAX_SNAPSHOT_BYTES);
		// Recorded rather than merely asserted, so a future field addition that eats the
		// remaining headroom is visible as a failure here rather than as a refused write in
		// production.
		assertTrue("worst case " + size + " bytes leaves less than 2 KiB of headroom",
			TelemetrySnapshotWriter.MAX_SNAPSHOT_BYTES - size >= 2_048);
	}

	// --- envelope and construction rules -------------------------------------------------------

	@Test
	public void schemaVersionAndSourceArePinned()
	{
		assertEquals(3, TelemetrySnapshot.SCHEMA);
		assertEquals("runelite", TelemetrySnapshot.SOURCE);
		assertTrue(populatedFixture().toJson().startsWith("{\"schema\":3,\"source\":\"runelite\","));
	}

	@Test
	public void instanceIdIsCarriedVerbatim()
	{
		String uuid = UUID.randomUUID().toString();
		TelemetrySnapshot snapshot = TelemetrySnapshot.builder()
			.envelope(uuid, 0L, EMITTED_AT)
			.session(false, "LOGIN_SCREEN", false, null, null, null)
			.build();
		assertEquals(uuid, snapshot.getInstanceId());
		assertTrue(snapshot.toJson().contains("\"instanceId\":\"" + uuid + "\""));
		assertEquals("a canonical UUID fits the exported bound exactly",
			36, TelemetrySnapshot.MAX_INSTANCE_ID_CHARS);
	}

	@Test
	public void negativeSequenceIsRejected()
	{
		try
		{
			TelemetrySnapshot.builder().envelope(INSTANCE_ID, -1L, EMITTED_AT)
				.session(false, "X", false, null, null, null).build();
			fail("expected a negative sequence to be rejected");
		}
		catch (IllegalArgumentException expected)
		{
			assertTrue(expected.getMessage(), expected.getMessage().contains("seq"));
		}
	}

	@Test
	public void sequenceAndTimestampsAreCarriedAsNumbers()
	{
		TelemetrySnapshot snapshot = populatedFixture();
		assertEquals(7L, snapshot.getSeq());
		assertEquals(EMITTED_AT, snapshot.getEmittedAt());
		assertTrue(snapshot.isPluginActive());
		assertTrue(snapshot.isLoggedIn());
		assertFalse(loggedOutFixture().isLoggedIn());
	}

	/**
	 * A timestamp later than the moment the document was emitted describes the future. Only a
	 * backward wall-clock adjustment can produce one, and the document must not carry it.
	 */
	@Test
	public void experienceAndTrackingTimestampsNeverExceedTheEmittedTime()
	{
		TelemetrySnapshot snapshot = TelemetrySnapshot.builder()
			.envelope(INSTANCE_ID, 0L, EMITTED_AT)
			.session(false, "LOGGED_IN", true, 302, 87, EMITTED_AT + 60_000L)
			.xp("mining", 10, EMITTED_AT + 5_000L, Collections.singletonList(
				new TelemetrySkillGain("mining", 10, 10, EMITTED_AT + 5_000L)))
			.build();

		String json = snapshot.toJson();
		assertTrue(json, json.contains("\"trackingStartedAt\":" + EMITTED_AT));
		assertTrue(json, json.contains("\"lastChangedAt\":" + EMITTED_AT + ","));
		assertFalse("no timestamp may sit after emittedAt",
			json.contains(String.valueOf(EMITTED_AT + 5_000L)));
	}

	@Test
	public void duplicateActivePrayersAreImpossibleInTheDocument()
	{
		String json = TelemetrySnapshot.builder()
			.envelope(INSTANCE_ID, 0L, EMITTED_AT)
			.session(false, "LOGGED_IN", true, null, null, null)
			.combat(null, Arrays.asList("piety", "piety", "augury", "piety"), null)
			.build()
			.toJson();
		assertTrue(json, json.contains("\"activePrayers\":[\"piety\",\"augury\"]"));
	}

	@Test
	public void anEquipmentOrInventoryCollectionOfTheWrongSizeIsRefused()
	{
		try
		{
			TelemetrySnapshot.builder().envelope(INSTANCE_ID, 0L, EMITTED_AT)
				.session(false, "X", false, null, null, null)
				.equipment(Collections.singletonList(TelemetryItemSlot.EMPTY)).build();
			fail("expected a short equipment collection to be refused");
		}
		catch (IllegalArgumentException expected)
		{
			assertTrue(expected.getMessage(), expected.getMessage().contains("equipment"));
		}

		try
		{
			TelemetrySnapshot.builder().envelope(INSTANCE_ID, 0L, EMITTED_AT)
				.session(false, "X", false, null, null, null)
				.inventory(0, 28, Collections.singletonList(TelemetryItemSlot.EMPTY)).build();
			fail("expected a short inventory collection to be refused");
		}
		catch (IllegalArgumentException expected)
		{
			assertTrue(expected.getMessage(), expected.getMessage().contains("inventory"));
		}
	}

	@Test
	public void inventoryOccupancyMustBeReportedTogetherAndSumToTwentyEight()
	{
		try
		{
			TelemetrySnapshot.builder().envelope(INSTANCE_ID, 0L, EMITTED_AT)
				.session(false, "X", false, null, null, null)
				.inventory(12, 12, null).build();
			fail("expected a used and free pair that does not sum to 28 to be refused");
		}
		catch (IllegalArgumentException expected)
		{
			assertTrue(expected.getMessage(), expected.getMessage().contains("28"));
		}

		try
		{
			TelemetrySnapshot.builder().envelope(INSTANCE_ID, 0L, EMITTED_AT)
				.session(false, "X", false, null, null, null)
				.inventory(12, null, null).build();
			fail("expected a half-reported occupancy pair to be refused");
		}
		catch (IllegalArgumentException expected)
		{
			assertTrue(expected.getMessage(), expected.getMessage().contains("together"));
		}
	}

	// --- item slot and target value rules --------------------------------------------------------

	@Test
	public void anItemSlotIsEitherEmptyOrFullyOccupied()
	{
		assertFalse(TelemetryItemSlot.EMPTY.isOccupied());
		assertEquals(null, TelemetryItemSlot.EMPTY.getItemId());
		assertEquals(null, TelemetryItemSlot.EMPTY.getQuantity());
		assertEquals(null, TelemetryItemSlot.EMPTY.getName());

		TelemetryItemSlot occupied = TelemetryItemSlot.of(4151, 1, "Sample whip");
		assertTrue(occupied.isOccupied());
		assertEquals(Integer.valueOf(4151), occupied.getItemId());
		assertEquals(Integer.valueOf(1), occupied.getQuantity());
		assertEquals("Sample whip", occupied.getName());

		// Nothing between the two states is reachable.
		assertEquals(TelemetryItemSlot.EMPTY, TelemetryItemSlot.of(-1, 0, "Sample"));
		assertEquals(TelemetryItemSlot.EMPTY, TelemetryItemSlot.of(4151, 0, "Sample"));
		assertEquals(TelemetryItemSlot.EMPTY, TelemetryItemSlot.of(4151, -3, "Sample"));
	}

	/**
	 * A game update can recase, respell, or rename an item at any time, so identity must rest on the
	 * numeric id rather than the display name. The same holds for an NPC target.
	 */
	@Test
	public void identityAndOccupancyNeverDependOnADisplayName()
	{
		List<TelemetryItemSlot> sameItem = Arrays.asList(
			TelemetryItemSlot.of(4151, 1, "sample whip"),
			TelemetryItemSlot.of(4151, 1, "SAMPLE WHIP"),
			TelemetryItemSlot.of(4151, 1, "Sample whip (or)"),
			TelemetryItemSlot.of(4151, 1, null));
		for (TelemetryItemSlot slot : sameItem)
		{
			assertTrue("occupancy comes from the id and quantity, never from the name",
				slot.isOccupied());
			assertEquals("identity is the numeric id, whatever the name says",
				Integer.valueOf(4151), slot.getItemId());
			assertEquals(Integer.valueOf(1), slot.getQuantity());
		}
		assertEquals("sample whip", sameItem.get(0).getName());
		assertEquals("SAMPLE WHIP", sameItem.get(1).getName());
		assertEquals(null, sameItem.get(3).getName());

		// The cache's absent-name sentinel is the one name reduced to none, and even that leaves
		// identity and occupancy untouched, including for item identity zero.
		TelemetryItemSlot absentName = TelemetryItemSlot.of(0, 5, "null");
		assertTrue(absentName.isOccupied());
		assertEquals(Integer.valueOf(0), absentName.getItemId());
		assertEquals(Integer.valueOf(5), absentName.getQuantity());
		assertEquals(null, absentName.getName());

		assertEquals(4001, TelemetryTarget.npc(4001, "SAMPLE BOSS", 21, 1, 1, false).getId());
		assertEquals(4001, TelemetryTarget.npc(4001, null, 21, 1, 1, false).getId());
	}

	/**
	 * The client signals an empty slot with a negative identity, and zero is a genuine entry in the
	 * game's item enumeration. Treating it as absent undercounted occupancy.
	 */
	@Test
	public void itemIdentityZeroIsOccupiedRatherThanEmpty()
	{
		TelemetryItemSlot zero = TelemetryItemSlot.of(0, 1, "Sample remains");
		assertTrue("identity zero is an item", zero.isOccupied());
		assertEquals(Integer.valueOf(0), zero.getItemId());
		assertEquals(Integer.valueOf(1), zero.getQuantity());
		assertEquals("Sample remains", zero.getName());
	}

	@Test
	public void onlyANegativeIdentityNormalizesToTheEmptySlotRepresentation()
	{
		for (int absent : new int[]{-1, -2, Integer.MIN_VALUE})
		{
			TelemetryItemSlot slot = TelemetryItemSlot.of(absent, 1, "Sample");
			assertEquals("identity " + absent + " is the client's empty signal",
				TelemetryItemSlot.EMPTY, slot);
			assertFalse(slot.isOccupied());
			assertEquals(null, slot.getItemId());
			assertEquals(null, slot.getQuantity());
			assertEquals(null, slot.getName());
		}
	}

	@Test
	public void ordinaryPositiveIdentitiesAreUnaffectedByTheZeroRule()
	{
		for (int id : new int[]{1, 995, 4151, Integer.MAX_VALUE})
		{
			TelemetryItemSlot slot = TelemetryItemSlot.of(id, 2, "Sample");
			assertTrue(slot.isOccupied());
			assertEquals(Integer.valueOf(id), slot.getItemId());
			assertEquals(Integer.valueOf(2), slot.getQuantity());
		}
	}

	@Test
	public void itemIdentityZeroSerializesAsAnOccupiedSlot()
	{
		List<TelemetryItemSlot> inventory = new ArrayList<>();
		inventory.add(TelemetryItemSlot.of(0, 1, "Sample remains"));
		while (inventory.size() < TelemetrySnapshot.INVENTORY_SLOTS)
		{
			inventory.add(TelemetryItemSlot.EMPTY);
		}
		List<TelemetryItemSlot> equipment = new ArrayList<>();
		equipment.add(TelemetryItemSlot.of(0, 1, "Sample remains"));
		while (equipment.size() < TelemetrySnapshot.EQUIPMENT_SLOTS.size())
		{
			equipment.add(TelemetryItemSlot.EMPTY);
		}

		String json = TelemetrySnapshot.builder()
			.envelope(INSTANCE_ID, 0L, EMITTED_AT)
			.session(true, "LOGGED_IN", true, null, null, null)
			.equipment(equipment)
			.inventory(1, 27, inventory)
			.build()
			.toJson();

		assertTrue(json, json.contains(
			"{\"slot\":0,\"itemId\":0,\"quantity\":1,\"name\":\"Sample remains\"}"));
		assertTrue(json, json.contains(
			"{\"slot\":\"head\",\"itemId\":0,\"quantity\":1,\"name\":\"Sample remains\"}"));
		assertEquals("and it counts towards occupancy", "1", valueOf(json, "usedSlots"));
		assertEquals("27", valueOf(json, "freeSlots"));
	}

	/** Minimal scalar reader; the whole-document assertions above are the primary pin. */
	private static String valueOf(String json, String key)
	{
		int at = json.indexOf("\"" + key + "\":");
		assertTrue("missing key " + key, at >= 0);
		int start = at + key.length() + 3;
		int end = start;
		while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}')
		{
			end++;
		}
		return json.substring(start, end);
	}

	@Test
	public void aStackIsOneOccupiedSlotWhateverItsQuantity()
	{
		assertTrue(TelemetryItemSlot.of(995, 2_147_000_000, "Sample coin pile").isOccupied());
		assertEquals(Integer.valueOf(2_147_000_000),
			TelemetryItemSlot.of(995, 2_147_000_000, "Sample coin pile").getQuantity());
	}

	@Test
	public void anAbsentOrBlankItemNameBecomesNoNameRatherThanTheWordNull()
	{
		assertEquals(null, TelemetryItemSlot.of(1, 1, null).getName());
		assertEquals(null, TelemetryItemSlot.of(1, 1, "   ").getName());
		assertEquals("the cache's own placeholder is not a name",
			null, TelemetryItemSlot.of(1, 1, "null").getName());
		assertEquals("Sample helm", TelemetryItemSlot.of(1, 1, "  Sample helm  ").getName());
	}

	@Test
	public void aTargetsHealthRatioAndScaleAreReportedTogetherOrNotAtAll()
	{
		TelemetryTarget both = TelemetryTarget.npc(1, "Sample", 5, 12, 30, false);
		assertEquals(Integer.valueOf(12), both.getHealthRatio());
		assertEquals(Integer.valueOf(30), both.getHealthScale());

		for (TelemetryTarget unreported : Arrays.asList(
			TelemetryTarget.npc(1, "Sample", 5, -1, 30, false),
			TelemetryTarget.npc(1, "Sample", 5, 12, -1, false),
			TelemetryTarget.npc(1, "Sample", 5, 12, 0, false),
			TelemetryTarget.npc(1, "Sample", 5, 31, 30, false)))
		{
			assertNotNull(unreported);
			assertEquals("an unusable pair yields no ratio", null, unreported.getHealthRatio());
			assertEquals("and no scale either", null, unreported.getHealthScale());
		}
	}

	@Test
	public void aTargetsCombatLevelIsAbsentRatherThanZeroWhenItHasNone()
	{
		assertEquals(null, TelemetryTarget.npc(1, "Sample crate", 0, 1, 1, false).getCombatLevel());
		assertEquals(null, TelemetryTarget.npc(1, "Sample crate", -4, 1, 1, false).getCombatLevel());
		assertEquals(Integer.valueOf(21),
			TelemetryTarget.npc(1, "Sample guard", 21, 1, 1, false).getCombatLevel());
	}

	@Test
	public void aNegativeIdentityDescribesNoTargetAtAll()
	{
		assertEquals(null, TelemetryTarget.npc(-1, "Sample", 5, 1, 1, false));
	}

	@Test
	public void aSessionGainMustBePositiveAndCannotExceedItsOwnCumulativeTotal()
	{
		for (int[] invalid : new int[][]{{0, 0}, {-5, -5}, {10, 0}, {10, -1}, {10, 11}})
		{
			try
			{
				new TelemetrySkillGain("mining", invalid[0], invalid[1], EMITTED_AT);
				fail("expected " + Arrays.toString(invalid) + " to be refused");
			}
			catch (IllegalArgumentException expected)
			{
				// expected
			}
		}
		assertEquals(10, new TelemetrySkillGain("mining", 10, 10, EMITTED_AT).getGained());
	}

	// --- helpers ---------------------------------------------------------------------------------

	private static TelemetrySnapshot withNames(String itemName, String npcName)
	{
		List<TelemetryItemSlot> inventory = new ArrayList<>();
		inventory.add(TelemetryItemSlot.of(9001, 1, itemName));
		while (inventory.size() < TelemetrySnapshot.INVENTORY_SLOTS)
		{
			inventory.add(TelemetryItemSlot.EMPTY);
		}
		return TelemetrySnapshot.builder()
			.envelope(INSTANCE_ID, 0L, EMITTED_AT)
			.session(true, "LOGGED_IN", true, null, null, null)
			.combat(null, Collections.<String>emptyList(),
				TelemetryTarget.npc(1, npcName, 5, 1, 1, false))
			.inventory(1, 27, inventory)
			.build();
	}

	private static String repeat(char c, int times)
	{
		StringBuilder sb = new StringBuilder(times);
		for (int i = 0; i < times; i++)
		{
			sb.append(c);
		}
		return sb.toString();
	}

	private static int countOccurrences(String haystack, String needle)
	{
		int count = 0;
		int at = haystack.indexOf(needle);
		while (at >= 0)
		{
			count++;
			at = haystack.indexOf(needle, at + 1);
		}
		return count;
	}

	/**
	 * Hand-written so this test depends on nothing the plugin uses to serialize. Array brackets do
	 * not change depth, so the entries of an array of objects are counted at the depth of the
	 * objects themselves.
	 */
	private static Map<Integer, List<String>> keysByDepth(String json)
	{
		Map<Integer, List<String>> keys = new HashMap<>();
		int depth = 0;
		int i = 0;
		while (i < json.length())
		{
			char c = json.charAt(i);
			if (c == '{')
			{
				depth++;
				i++;
			}
			else if (c == '}')
			{
				depth--;
				i++;
			}
			else if (c == '"')
			{
				StringBuilder literal = new StringBuilder();
				i++;
				while (i < json.length() && json.charAt(i) != '"')
				{
					if (json.charAt(i) == '\\')
					{
						literal.append(json.charAt(i));
						i++;
					}
					literal.append(json.charAt(i));
					i++;
				}
				i++;
				if (i < json.length() && json.charAt(i) == ':')
				{
					keys.computeIfAbsent(depth, d -> new ArrayList<>()).add(literal.toString());
				}
			}
			else
			{
				i++;
			}
		}
		return keys;
	}
}
