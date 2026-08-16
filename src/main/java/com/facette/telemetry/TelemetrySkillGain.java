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
 * How much experience one skill gained during the current tracked session. Every number here is a
 * difference between two readings this plugin instance took itself, so no account total, starting
 * total, or level history is held or derivable.
 */
final class TelemetrySkillGain
{
	private final String skill;
	private final int gained;
	private final int lastDelta;
	private final long lastChangedAt;

	/** {@code lastDelta} is one of the gains {@code gained} counts, so it can never exceed it. */
	TelemetrySkillGain(String skill, int gained, int lastDelta, long lastChangedAt)
	{
		this.skill = Objects.requireNonNull(skill, "skill");
		this.gained = gained;
		this.lastDelta = lastDelta;
		this.lastChangedAt = lastChangedAt;
		if (gained <= 0 || lastDelta <= 0)
		{
			throw new IllegalArgumentException("a session gain is positive by definition");
		}
		if (lastDelta > gained)
		{
			throw new IllegalArgumentException("lastDelta cannot exceed the cumulative gain");
		}
	}

	String getSkill()
	{
		return skill;
	}

	int getGained()
	{
		return gained;
	}

	int getLastDelta()
	{
		return lastDelta;
	}

	long getLastChangedAt()
	{
		return lastChangedAt;
	}

	@Override
	public boolean equals(Object other)
	{
		if (this == other)
		{
			return true;
		}
		if (!(other instanceof TelemetrySkillGain))
		{
			return false;
		}
		TelemetrySkillGain that = (TelemetrySkillGain) other;
		return gained == that.gained
			&& lastDelta == that.lastDelta
			&& lastChangedAt == that.lastChangedAt
			&& skill.equals(that.skill);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(skill, gained, lastDelta, lastChangedAt);
	}
}
