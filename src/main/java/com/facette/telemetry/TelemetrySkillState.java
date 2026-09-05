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
 * One skill's current standing: its base level, its boosted or drained level, and its lifetime
 * experience. All three are read straight from the client's own skill accessors, which is why this
 * holds no baseline, no history, and no interpretation of any kind.
 *
 * This is not {@link TelemetrySkillGain}. That type carries a difference between two readings this
 * plugin took itself during one session; this one carries the client's current totals. The two are
 * exported side by side because a reader that only wants "what happened while I was watching" and a
 * reader that wants "where is this account now" are asking different questions.
 */
final class TelemetrySkillState
{
	private final String skill;
	private final int level;
	private final int boostedLevel;
	private final int xp;

	TelemetrySkillState(String skill, int level, int boostedLevel, int xp)
	{
		this.skill = Objects.requireNonNull(skill, "skill");
		this.level = level;
		this.boostedLevel = boostedLevel;
		this.xp = xp;
		if (level < 0 || boostedLevel < 0 || xp < 0)
		{
			throw new IllegalArgumentException("a level or experience reading is never negative");
		}
	}

	String getSkill()
	{
		return skill;
	}

	int getLevel()
	{
		return level;
	}

	/** Equal to the base level when nothing is boosting or draining the skill. */
	int getBoostedLevel()
	{
		return boostedLevel;
	}

	int getXp()
	{
		return xp;
	}

	/**
	 * Value equality, because the publication decision is "did anything a reader would see change".
	 * Without it every sample would look like a change and the plugin would rewrite the file four
	 * times a second forever.
	 */
	@Override
	public boolean equals(Object other)
	{
		if (this == other)
		{
			return true;
		}
		if (!(other instanceof TelemetrySkillState))
		{
			return false;
		}
		TelemetrySkillState that = (TelemetrySkillState) other;
		return level == that.level
			&& boostedLevel == that.boostedLevel
			&& xp == that.xp
			&& skill.equals(that.skill);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(skill, level, boostedLevel, xp);
	}
}
