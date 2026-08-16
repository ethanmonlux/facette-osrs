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

import java.util.Objects;

/**
 * The NPC the local player is interacting with, reduced to what any observer of the game world can
 * already see. Only an NPC can be represented, which is why the kind is a constant rather than a
 * discriminator: a player target has no representation here to reach even by mistake. Health is the
 * ratio and scale the server transmits, never a real hitpoints figure.
 */
final class TelemetryTarget
{
	static final String KIND = "npc";

	// The name the game cache reports for an actor it has no name for. Treated as no name rather
	// than exported as the four characters null.
	private static final String ABSENT_NAME = "null";

	private final int id;
	private final String name;
	private final Integer combatLevel;
	private final Integer healthRatio;
	private final Integer healthScale;
	private final boolean dead;

	private TelemetryTarget(int id, String name, Integer combatLevel, Integer healthRatio,
		Integer healthScale, boolean dead)
	{
		this.id = id;
		this.name = name;
		this.combatLevel = combatLevel;
		this.healthRatio = healthRatio;
		this.healthScale = healthScale;
		this.dead = dead;
	}

	/**
	 * The interacted-with NPC. A negative identity is the client's signal that this is no NPC at all.
	 * A non-positive combat level still describes a real NPC, one that simply has no level.
	 */
	static TelemetryTarget npc(int id, String name, int combatLevel, int healthRatio,
		int healthScale, boolean dead)
	{
		if (id < 0)
		{
			return null;
		}
		// Both or neither, because a ratio without its scale means nothing: a non-positive scale is
		// no scale, and a negative ratio is the client's own "not transmitted" signal.
		boolean healthReported = healthScale > 0 && healthRatio >= 0 && healthRatio <= healthScale;
		return new TelemetryTarget(
			id,
			normalizeName(name),
			combatLevel > 0 ? Integer.valueOf(combatLevel) : null,
			healthReported ? Integer.valueOf(healthRatio) : null,
			healthReported ? Integer.valueOf(healthScale) : null,
			dead);
	}

	int getId()
	{
		return id;
	}

	String getName()
	{
		return name;
	}

	Integer getCombatLevel()
	{
		return combatLevel;
	}

	Integer getHealthRatio()
	{
		return healthRatio;
	}

	Integer getHealthScale()
	{
		return healthScale;
	}

	boolean isDead()
	{
		return dead;
	}

	private static String normalizeName(String raw)
	{
		if (raw == null)
		{
			return null;
		}
		String trimmed = raw.trim();
		return trimmed.isEmpty() || ABSENT_NAME.equals(trimmed) ? null : trimmed;
	}

	@Override
	public boolean equals(Object other)
	{
		if (this == other)
		{
			return true;
		}
		if (!(other instanceof TelemetryTarget))
		{
			return false;
		}
		TelemetryTarget that = (TelemetryTarget) other;
		return id == that.id
			&& dead == that.dead
			&& Objects.equals(name, that.name)
			&& Objects.equals(combatLevel, that.combatLevel)
			&& Objects.equals(healthRatio, that.healthRatio)
			&& Objects.equals(healthScale, that.healthScale);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(id, name, combatLevel, healthRatio, healthScale, dead);
	}
}
