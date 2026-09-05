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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * One immutable schema-3 telemetry snapshot and its canonical JSON form. The schema is closed: every
 * field named here is exported and nothing else is.
 *
 * The JSON is written by hand rather than reflected out of this object, both to fix the key order
 * and because reflection is not permitted in a Plugin Hub plugin. Every collection is fixed-size or
 * enum-bounded and every exported string is bounded, so the document stays under the writer's size
 * limit however long the player plays.
 *
 * Schema 3 is schema 2 plus a suffix. Every schema-2 key keeps its name, its position, and its
 * meaning, and the new groups are appended after them, so a reader written against schema 2 finds
 * everything it knows exactly where it was. The new groups each answer for themselves in
 * {@code capabilities}: a group is reported supported only while it is actually carrying values, and
 * a group that could not be read is null rather than filled in with a default.
 */
final class TelemetrySnapshot
{
	static final int SCHEMA = 3;

	static final String SOURCE = "runelite";

	// The eleven visible equipment slots. These names are the contract and the order is part of it:
	// the plugin maps RuneLite's own slots onto this list positionally.
	static final List<String> EQUIPMENT_SLOTS = Collections.unmodifiableList(Arrays.asList(
		"head", "cape", "amulet", "weapon", "body", "shield", "legs", "gloves", "boots",
		"ring", "ammo"));

	static final int INVENTORY_SLOTS = 28;

	static final int MAX_INSTANCE_ID_CHARS = 36;

	static final int MAX_GAME_STATE_CHARS = 32;

	static final int MAX_ATTACK_STYLE_CHARS = 32;

	static final int MAX_PRAYER_CHARS = 32;

	static final int MAX_SKILL_CHARS = 24;

	static final int MAX_ITEM_NAME_CHARS = 48;

	static final int MAX_NPC_NAME_CHARS = 48;

	static final int MAX_ACCOUNT_TYPE_CHARS = 32;

	static final int MAX_OFFER_STATE_CHARS = 24;

	// The schema-3 fact groups, in the order they appear under "capabilities" and in the order the
	// groups themselves appear in the document. Naming them once here is what stops a group being
	// exported without a capability entry, or given one it does not have.
	static final List<String> CAPABILITY_GROUPS = Collections.unmodifiableList(Arrays.asList(
		"skills", "account", "quests", "slayer", "grandExchange"));

	private final String instanceId;
	private final long seq;
	private final long emittedAt;

	private final boolean pluginActive;
	private final String gameState;
	private final boolean loggedIn;
	private final Integer world;
	private final Integer combatLevel;
	private final Long trackingStartedAt;

	private final Integer hitpointsCurrent;
	private final Integer hitpointsBase;
	private final Integer prayerCurrent;
	private final Integer prayerBase;
	private final Integer runEnergyPercent;
	private final Integer specialAttackPercent;
	private final Integer weightKg;

	private final String attackStyle;
	private final List<String> activePrayers;
	private final TelemetryTarget target;

	private final List<TelemetryItemSlot> equipmentSlots;

	private final Integer usedSlots;
	private final Integer freeSlots;
	private final List<TelemetryItemSlot> inventorySlots;

	private final String lastSkill;
	private final Integer lastDelta;
	private final Long lastChangedAt;
	private final List<TelemetrySkillGain> skillGains;

	private final List<TelemetrySkillState> skillStates;

	private final Integer accountTypeId;

	private final Integer questPoints;

	private final Integer slayerRemaining;
	private final Integer slayerTaskCreatureId;

	private final List<TelemetryGrandExchangeSlot> grandExchangeSlots;

	private TelemetrySnapshot(Builder b)
	{
		this.instanceId = Objects.requireNonNull(b.instanceId, "instanceId");
		this.seq = b.seq;
		this.emittedAt = b.emittedAt;
		this.pluginActive = b.pluginActive;
		this.gameState = Objects.requireNonNull(b.gameState, "gameState");
		this.loggedIn = b.loggedIn;
		this.world = b.world;
		this.combatLevel = b.combatLevel;
		this.hitpointsCurrent = b.hitpointsCurrent;
		this.hitpointsBase = b.hitpointsBase;
		this.prayerCurrent = b.prayerCurrent;
		this.prayerBase = b.prayerBase;
		this.runEnergyPercent = b.runEnergyPercent;
		this.specialAttackPercent = b.specialAttackPercent;
		this.weightKg = b.weightKg;
		this.attackStyle = b.attackStyle;
		this.target = b.target;
		this.usedSlots = b.usedSlots;
		this.freeSlots = b.freeSlots;
		this.lastSkill = b.lastSkill;
		this.lastDelta = b.lastDelta;
		this.accountTypeId = b.accountTypeId;
		this.questPoints = b.questPoints;
		this.slayerTaskCreatureId = b.slayerTaskCreatureId;

		if (seq < 0)
		{
			throw new IllegalArgumentException("seq must be non-negative");
		}
		if (emittedAt < 0)
		{
			throw new IllegalArgumentException("emittedAt must be non-negative");
		}

		// A timestamp later than emission would describe the future.
		this.trackingStartedAt = atMost(b.trackingStartedAt, emittedAt);
		this.lastChangedAt = atMost(b.lastChangedAt, emittedAt);

		// Deduplicated by construction, so no arrangement of readings repeats a prayer.
		this.activePrayers = b.activePrayers == null
			? null
			: Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(b.activePrayers)));

		this.equipmentSlots = copyFixedSlots(b.equipmentSlots, EQUIPMENT_SLOTS.size(), "equipment");
		this.inventorySlots = copyFixedSlots(b.inventorySlots, INVENTORY_SLOTS, "inventory");
		this.skillGains = copySkillGains(b.skillGains, emittedAt);
		this.skillStates = copyOrNull(b.skillStates, "skill state");
		this.grandExchangeSlots = copyOrNull(b.grandExchangeSlots, "grand exchange slot");

		if (usedSlots != null || freeSlots != null)
		{
			if (usedSlots == null || freeSlots == null || usedSlots + freeSlots != INVENTORY_SLOTS)
			{
				throw new IllegalArgumentException(
					"used and free inventory slots must be reported together and sum to "
						+ INVENTORY_SLOTS);
			}
		}

		// A task creature without a remaining count would be a task nobody can say is still
		// assigned, so the identifier is only carried by a slayer group that has its count.
		this.slayerRemaining = b.slayerRemaining;
		if (slayerRemaining == null && slayerTaskCreatureId != null)
		{
			throw new IllegalArgumentException(
				"a slayer task creature cannot be reported without a remaining count");
		}
	}

	static Builder builder()
	{
		return new Builder();
	}

	String getInstanceId()
	{
		return instanceId;
	}

	long getSeq()
	{
		return seq;
	}

	long getEmittedAt()
	{
		return emittedAt;
	}

	boolean isPluginActive()
	{
		return pluginActive;
	}

	boolean isLoggedIn()
	{
		return loggedIn;
	}

	/** Key order and key set are fixed here and are the contract Facette reads. */
	String toJson()
	{
		StringBuilder sb = new StringBuilder(4_096);
		sb.append('{');
		num(sb, "schema", SCHEMA).append(',');
		str(sb, "source", SOURCE, SOURCE.length()).append(',');
		str(sb, "instanceId", instanceId, MAX_INSTANCE_ID_CHARS).append(',');
		num(sb, "seq", seq).append(',');
		num(sb, "emittedAt", emittedAt).append(',');

		key(sb, "session").append('{');
		bool(sb, "pluginActive", pluginActive).append(',');
		str(sb, "gameState", gameState, MAX_GAME_STATE_CHARS).append(',');
		bool(sb, "loggedIn", loggedIn).append(',');
		num(sb, "world", world).append(',');
		num(sb, "combatLevel", combatLevel).append(',');
		num(sb, "trackingStartedAt", trackingStartedAt);
		sb.append("},");

		key(sb, "vitals").append('{');
		num(sb, "hitpointsCurrent", hitpointsCurrent).append(',');
		num(sb, "hitpointsBase", hitpointsBase).append(',');
		num(sb, "prayerCurrent", prayerCurrent).append(',');
		num(sb, "prayerBase", prayerBase).append(',');
		num(sb, "runEnergyPercent", runEnergyPercent).append(',');
		num(sb, "specialAttackPercent", specialAttackPercent).append(',');
		num(sb, "weightKg", weightKg);
		sb.append("},");

		key(sb, "combat").append('{');
		str(sb, "attackStyle", attackStyle, MAX_ATTACK_STYLE_CHARS).append(',');
		key(sb, "activePrayers");
		appendPrayers(sb).append(',');
		key(sb, "target");
		appendTarget(sb);
		sb.append("},");

		key(sb, "equipment").append('{');
		key(sb, "slots");
		appendEquipmentSlots(sb);
		sb.append("},");

		key(sb, "inventory").append('{');
		num(sb, "usedSlots", usedSlots).append(',');
		num(sb, "freeSlots", freeSlots).append(',');
		key(sb, "slots");
		appendInventorySlots(sb);
		sb.append("},");

		key(sb, "xp").append('{');
		str(sb, "lastSkill", lastSkill, MAX_SKILL_CHARS).append(',');
		num(sb, "lastDelta", lastDelta).append(',');
		num(sb, "lastChangedAt", lastChangedAt).append(',');
		key(sb, "skills");
		appendSkillGains(sb);
		sb.append("},");

		// Everything from here down is what schema 3 adds. Schema 2's keys above are untouched, so
		// the difference between the two documents is exactly this suffix.
		key(sb, "capabilities").append('{');
		appendCapabilities(sb);
		sb.append("},");

		key(sb, "skills").append('{');
		key(sb, "entries");
		appendSkillStates(sb);
		sb.append("},");

		key(sb, "account").append('{');
		str(sb, "type", accountTypeName(), MAX_ACCOUNT_TYPE_CHARS).append(',');
		num(sb, "typeId", accountTypeId);
		sb.append("},");

		key(sb, "quests").append('{');
		num(sb, "points", questPoints);
		sb.append("},");

		key(sb, "slayer").append('{');
		num(sb, "remaining", slayerRemaining).append(',');
		num(sb, "taskCreatureId", slayerTaskCreatureId);
		sb.append("},");

		key(sb, "grandExchange").append('{');
		key(sb, "slots");
		appendGrandExchangeSlots(sb);
		sb.append('}');

		sb.append('}');
		return sb.toString();
	}

	/**
	 * The name for the exported account mode, or null when this build has no name for the reading.
	 * Derived rather than stored, so the name and the identifier beside it cannot disagree.
	 */
	private String accountTypeName()
	{
		return accountTypeId == null ? null : TelemetryAccountType.nameOf(accountTypeId);
	}

	/**
	 * Whether one schema-3 group is carrying values. This is the single definition of supported, and
	 * every group's answer is read off the same fields the serializer is about to write, so a
	 * capability cannot claim something the document does not contain.
	 */
	TelemetryCapability capability(String group)
	{
		switch (group)
		{
			case "skills":
				return TelemetryCapability.of(skillStates != null);
			case "account":
				// The identifier, not the name: an account mode this build cannot name is still a
				// mode the client reported, and reporting it unavailable would lose that.
				return TelemetryCapability.of(accountTypeId != null);
			case "quests":
				return TelemetryCapability.of(questPoints != null);
			case "slayer":
				// The count, not the creature: having no task assigned is a supported answer.
				return TelemetryCapability.of(slayerRemaining != null);
			case "grandExchange":
				return TelemetryCapability.of(grandExchangeSlots != null);
			default:
				throw new IllegalArgumentException("no such capability group: " + group);
		}
	}

	byte[] toJsonBytes()
	{
		return toJson().getBytes(StandardCharsets.UTF_8);
	}

	private StringBuilder appendPrayers(StringBuilder sb)
	{
		if (activePrayers == null)
		{
			return sb.append("null");
		}
		sb.append('[');
		for (int i = 0; i < activePrayers.size(); i++)
		{
			if (i > 0)
			{
				sb.append(',');
			}
			appendString(sb, activePrayers.get(i), MAX_PRAYER_CHARS);
		}
		return sb.append(']');
	}

	private StringBuilder appendTarget(StringBuilder sb)
	{
		if (target == null)
		{
			return sb.append("null");
		}
		sb.append('{');
		str(sb, "kind", TelemetryTarget.KIND, TelemetryTarget.KIND.length()).append(',');
		num(sb, "id", target.getId()).append(',');
		str(sb, "name", target.getName(), MAX_NPC_NAME_CHARS).append(',');
		num(sb, "combatLevel", target.getCombatLevel()).append(',');
		num(sb, "healthRatio", target.getHealthRatio()).append(',');
		num(sb, "healthScale", target.getHealthScale()).append(',');
		bool(sb, "dead", target.isDead());
		return sb.append('}');
	}

	private StringBuilder appendEquipmentSlots(StringBuilder sb)
	{
		if (equipmentSlots == null)
		{
			return sb.append("null");
		}
		sb.append('[');
		for (int i = 0; i < equipmentSlots.size(); i++)
		{
			if (i > 0)
			{
				sb.append(',');
			}
			sb.append('{');
			// The label comes from the contract list, not the entry, so it cannot disagree with
			// the position it sits at.
			String name = EQUIPMENT_SLOTS.get(i);
			str(sb, "slot", name, name.length()).append(',');
			appendItemBody(sb, equipmentSlots.get(i));
			sb.append('}');
		}
		return sb.append(']');
	}

	private StringBuilder appendInventorySlots(StringBuilder sb)
	{
		if (inventorySlots == null)
		{
			return sb.append("null");
		}
		sb.append('[');
		for (int i = 0; i < inventorySlots.size(); i++)
		{
			if (i > 0)
			{
				sb.append(',');
			}
			sb.append('{');
			num(sb, "slot", i).append(',');
			appendItemBody(sb, inventorySlots.get(i));
			sb.append('}');
		}
		return sb.append(']');
	}

	private static StringBuilder appendItemBody(StringBuilder sb, TelemetryItemSlot slot)
	{
		num(sb, "itemId", slot.getItemId()).append(',');
		num(sb, "quantity", slot.getQuantity()).append(',');
		return str(sb, "name", slot.getName(), MAX_ITEM_NAME_CHARS);
	}

	private StringBuilder appendSkillGains(StringBuilder sb)
	{
		if (skillGains == null)
		{
			return sb.append("null");
		}
		sb.append('[');
		for (int i = 0; i < skillGains.size(); i++)
		{
			if (i > 0)
			{
				sb.append(',');
			}
			TelemetrySkillGain gain = skillGains.get(i);
			sb.append('{');
			str(sb, "skill", gain.getSkill(), MAX_SKILL_CHARS).append(',');
			num(sb, "gained", gain.getGained()).append(',');
			num(sb, "lastDelta", gain.getLastDelta()).append(',');
			num(sb, "lastChangedAt", gain.getLastChangedAt());
			sb.append('}');
		}
		return sb.append(']');
	}

	private StringBuilder appendCapabilities(StringBuilder sb)
	{
		for (int i = 0; i < CAPABILITY_GROUPS.size(); i++)
		{
			if (i > 0)
			{
				sb.append(',');
			}
			String group = CAPABILITY_GROUPS.get(i);
			String state = capability(group).exported();
			str(sb, group, state, state.length());
		}
		return sb;
	}

	private StringBuilder appendSkillStates(StringBuilder sb)
	{
		if (skillStates == null)
		{
			return sb.append("null");
		}
		sb.append('[');
		for (int i = 0; i < skillStates.size(); i++)
		{
			if (i > 0)
			{
				sb.append(',');
			}
			TelemetrySkillState skill = skillStates.get(i);
			sb.append('{');
			str(sb, "skill", skill.getSkill(), MAX_SKILL_CHARS).append(',');
			num(sb, "level", skill.getLevel()).append(',');
			num(sb, "boostedLevel", skill.getBoostedLevel()).append(',');
			num(sb, "xp", skill.getXp());
			sb.append('}');
		}
		return sb.append(']');
	}

	private StringBuilder appendGrandExchangeSlots(StringBuilder sb)
	{
		if (grandExchangeSlots == null)
		{
			return sb.append("null");
		}
		sb.append('[');
		for (int i = 0; i < grandExchangeSlots.size(); i++)
		{
			if (i > 0)
			{
				sb.append(',');
			}
			TelemetryGrandExchangeSlot offer = grandExchangeSlots.get(i);
			sb.append('{');
			// The position is written from the loop, not from the entry, so a slot number cannot
			// disagree with where it sits.
			num(sb, "slot", i).append(',');
			str(sb, "state", offer.getState(), MAX_OFFER_STATE_CHARS).append(',');
			num(sb, "itemId", offer.getItemId()).append(',');
			num(sb, "price", offer.getPrice()).append(',');
			num(sb, "totalQuantity", offer.getTotalQuantity()).append(',');
			num(sb, "quantityTransacted", offer.getQuantityTransacted()).append(',');
			num(sb, "spent", offer.getSpent());
			sb.append('}');
		}
		return sb.append(']');
	}

	private static StringBuilder key(StringBuilder sb, String k)
	{
		appendString(sb, k, k.length());
		return sb.append(':');
	}

	private static StringBuilder num(StringBuilder sb, String k, Number value)
	{
		key(sb, k);
		return value == null ? sb.append("null") : sb.append(value.longValue());
	}

	private static StringBuilder bool(StringBuilder sb, String k, boolean value)
	{
		key(sb, k);
		return sb.append(value);
	}

	private static StringBuilder str(StringBuilder sb, String k, String value, int maxChars)
	{
		key(sb, k);
		return value == null ? sb.append("null") : appendString(sb, value, maxChars);
	}

	/**
	 * Appends a JSON string literal, bounded and escaped. Bounding lives here, the one place every
	 * exported string passes through, so no caller can opt out. A truncation that would split a
	 * surrogate pair steps back one character.
	 */
	private static StringBuilder appendString(StringBuilder sb, String value, int maxChars)
	{
		int length = value.length();
		if (length > maxChars)
		{
			length = maxChars;
			if (length > 0 && Character.isHighSurrogate(value.charAt(length - 1)))
			{
				length--;
			}
		}
		sb.append('"');
		for (int i = 0; i < length; i++)
		{
			char c = value.charAt(i);
			switch (c)
			{
				case '"':
					sb.append("\\\"");
					break;
				case '\\':
					sb.append("\\\\");
					break;
				case '\b':
					sb.append("\\b");
					break;
				case '\f':
					sb.append("\\f");
					break;
				case '\n':
					sb.append("\\n");
					break;
				case '\r':
					sb.append("\\r");
					break;
				case '\t':
					sb.append("\\t");
					break;
				default:
					if (c < 0x20)
					{
						sb.append(String.format("\\u%04x", (int) c));
					}
					else
					{
						sb.append(c);
					}
					break;
			}
		}
		return sb.append('"');
	}

	private static Long atMost(Long value, long ceiling)
	{
		return value == null ? null : Long.valueOf(Math.min(value, ceiling));
	}

	// Padding would claim empty slots the client never reported, and truncating would hide items.
	private static List<TelemetryItemSlot> copyFixedSlots(List<TelemetryItemSlot> slots,
		int expectedSize, String what)
	{
		if (slots == null)
		{
			return null;
		}
		if (slots.size() != expectedSize)
		{
			throw new IllegalArgumentException(
				what + " must have exactly " + expectedSize + " slots, got " + slots.size());
		}
		List<TelemetryItemSlot> copy = new ArrayList<>(expectedSize);
		for (TelemetryItemSlot slot : slots)
		{
			copy.add(Objects.requireNonNull(slot, "slot"));
		}
		return Collections.unmodifiableList(copy);
	}

	/**
	 * A defensive copy of a group whose length is bounded by the client's own enumeration rather
	 * than by a fixed number this file could assert. Null stays null, because "the group was not
	 * read" and "the group is empty" are different answers and only one of them is a capability.
	 */
	private static <T> List<T> copyOrNull(List<T> values, String what)
	{
		if (values == null)
		{
			return null;
		}
		List<T> copy = new ArrayList<>(values.size());
		for (T value : values)
		{
			copy.add(Objects.requireNonNull(value, what));
		}
		return Collections.unmodifiableList(copy);
	}

	private static List<TelemetrySkillGain> copySkillGains(List<TelemetrySkillGain> gains,
		long emittedAt)
	{
		if (gains == null)
		{
			return null;
		}
		List<TelemetrySkillGain> copy = new ArrayList<>(gains.size());
		for (TelemetrySkillGain gain : gains)
		{
			Objects.requireNonNull(gain, "gain");
			copy.add(gain.getLastChangedAt() <= emittedAt
				? gain
				: new TelemetrySkillGain(gain.getSkill(), gain.getGained(), gain.getLastDelta(),
					emittedAt));
		}
		return Collections.unmodifiableList(copy);
	}

	static final class Builder
	{
		private String instanceId;
		private long seq;
		private long emittedAt;
		private boolean pluginActive;
		private String gameState;
		private boolean loggedIn;
		private Integer world;
		private Integer combatLevel;
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
		private List<TelemetrySkillGain> skillGains;
		private List<TelemetrySkillState> skillStates;
		private Integer accountTypeId;
		private Integer questPoints;
		private Integer slayerRemaining;
		private Integer slayerTaskCreatureId;
		private List<TelemetryGrandExchangeSlot> grandExchangeSlots;

		private Builder()
		{
		}

		Builder envelope(String instanceId, long seq, long emittedAt)
		{
			this.instanceId = instanceId;
			this.seq = seq;
			this.emittedAt = emittedAt;
			return this;
		}

		Builder session(boolean pluginActive, String gameState, boolean loggedIn, Integer world,
			Integer combatLevel, Long trackingStartedAt)
		{
			this.pluginActive = pluginActive;
			this.gameState = gameState;
			this.loggedIn = loggedIn;
			this.world = world;
			this.combatLevel = combatLevel;
			this.trackingStartedAt = trackingStartedAt;
			return this;
		}

		Builder vitals(Integer hitpointsCurrent, Integer hitpointsBase, Integer prayerCurrent,
			Integer prayerBase, Integer runEnergyPercent, Integer specialAttackPercent,
			Integer weightKg)
		{
			this.hitpointsCurrent = hitpointsCurrent;
			this.hitpointsBase = hitpointsBase;
			this.prayerCurrent = prayerCurrent;
			this.prayerBase = prayerBase;
			this.runEnergyPercent = runEnergyPercent;
			this.specialAttackPercent = specialAttackPercent;
			this.weightKg = weightKg;
			return this;
		}

		Builder combat(String attackStyle, List<String> activePrayers, TelemetryTarget target)
		{
			this.attackStyle = attackStyle;
			this.activePrayers = activePrayers;
			this.target = target;
			return this;
		}

		Builder equipment(List<TelemetryItemSlot> slots)
		{
			this.equipmentSlots = slots;
			return this;
		}

		Builder inventory(Integer used, Integer free, List<TelemetryItemSlot> slots)
		{
			this.usedSlots = used;
			this.freeSlots = free;
			this.inventorySlots = slots;
			return this;
		}

		Builder xp(String lastSkill, Integer lastDelta, Long lastChangedAt,
			List<TelemetrySkillGain> skills)
		{
			this.lastSkill = lastSkill;
			this.lastDelta = lastDelta;
			this.lastChangedAt = lastChangedAt;
			this.skillGains = skills;
			return this;
		}

		/** Null for the whole group when no live sample has read the client's skill totals. */
		Builder skills(List<TelemetrySkillState> states)
		{
			this.skillStates = states;
			return this;
		}

		/** The account mode's own reading. The name is derived from it at serialization time. */
		Builder account(Integer typeId)
		{
			this.accountTypeId = typeId;
			return this;
		}

		Builder quests(Integer points)
		{
			this.questPoints = points;
			return this;
		}

		/** A null creature identifier with a remaining count is "no task assigned". */
		Builder slayer(Integer remaining, Integer taskCreatureId)
		{
			this.slayerRemaining = remaining;
			this.slayerTaskCreatureId = taskCreatureId;
			return this;
		}

		Builder grandExchange(List<TelemetryGrandExchangeSlot> slots)
		{
			this.grandExchangeSlots = slots;
			return this;
		}

		TelemetrySnapshot build()
		{
			return new TelemetrySnapshot(this);
		}
	}
}
