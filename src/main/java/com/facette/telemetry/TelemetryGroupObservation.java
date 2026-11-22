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
 * One schema-5 group's availability and the time of the observation it carries. Immutable. The
 * availability names are the closed set SCHEMA.md documents; nothing else is ever exported.
 */
final class TelemetryGroupObservation
{
	/** Read on the latest sample, with the same value since {@link #getObservedAt()}. */
	static final String CURRENT = "current";

	/** Retained from an earlier read in this character session and not re-read since. */
	static final String OBSERVED = "observed";

	/** Nothing has been read for this group yet. */
	static final String NOT_OBSERVED = "not_observed";

	/** The latest read failed, or there is no session to read. */
	static final String UNAVAILABLE = "unavailable";

	/** A first pass is still in progress, so only part of the group has been read. */
	static final String PARTIAL = "partial";

	static final TelemetryGroupObservation NONE_UNAVAILABLE =
		new TelemetryGroupObservation(UNAVAILABLE, null);

	static final TelemetryGroupObservation NONE_NOT_OBSERVED =
		new TelemetryGroupObservation(NOT_OBSERVED, null);

	private final String availability;
	private final Long observedAt;

	private TelemetryGroupObservation(String availability, Long observedAt)
	{
		this.availability = availability;
		this.observedAt = observedAt;
	}

	static TelemetryGroupObservation current(long observedAt)
	{
		return new TelemetryGroupObservation(CURRENT, observedAt);
	}

	static TelemetryGroupObservation observed(long observedAt)
	{
		return new TelemetryGroupObservation(OBSERVED, observedAt);
	}

	static TelemetryGroupObservation partial(long observedAt)
	{
		return new TelemetryGroupObservation(PARTIAL, observedAt);
	}

	String getAvailability()
	{
		return availability;
	}

	Long getObservedAt()
	{
		return observedAt;
	}

	boolean isCurrent()
	{
		return CURRENT.equals(availability);
	}

	/** The same observation, with a time no later than the given one. */
	TelemetryGroupObservation atMost(long ceiling)
	{
		if (observedAt == null || observedAt <= ceiling)
		{
			return this;
		}
		return new TelemetryGroupObservation(availability, ceiling);
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof TelemetryGroupObservation))
		{
			return false;
		}
		TelemetryGroupObservation that = (TelemetryGroupObservation) o;
		return availability.equals(that.availability) && Objects.equals(observedAt, that.observedAt);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(availability, observedAt);
	}
}
