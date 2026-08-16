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
 * What one fixed item slot holds: an item identity, a quantity, and a name, or nothing. The slot's
 * own position is not stored here, so no entry can disagree with where it sits. Item identity 0 is a
 * real item and the client signals an empty slot with a negative identity, so occupancy is decided
 * from nullability rather than from a positive identity.
 */
final class TelemetryItemSlot
{
	static final TelemetryItemSlot EMPTY = new TelemetryItemSlot(null, null, null);

	// The name the game cache reports for an item it has no name for. Treated as no name rather
	// than exported as the four characters null.
	private static final String ABSENT_NAME = "null";

	private final Integer itemId;
	private final Integer quantity;
	private final String name;

	private TelemetryItemSlot(Integer itemId, Integer quantity, String name)
	{
		this.itemId = itemId;
		this.quantity = quantity;
		this.name = name;
	}

	/**
	 * The slot as read from the client. Stack size never affects occupancy, so one slot holding a
	 * million coins is one occupied slot.
	 */
	static TelemetryItemSlot of(int itemId, int quantity, String name)
	{
		if (itemId < 0 || quantity <= 0)
		{
			return EMPTY;
		}
		return new TelemetryItemSlot(itemId, quantity, normalizeName(name));
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

	boolean isOccupied()
	{
		return itemId != null;
	}

	Integer getItemId()
	{
		return itemId;
	}

	Integer getQuantity()
	{
		return quantity;
	}

	String getName()
	{
		return name;
	}

	@Override
	public boolean equals(Object other)
	{
		if (this == other)
		{
			return true;
		}
		if (!(other instanceof TelemetryItemSlot))
		{
			return false;
		}
		TelemetryItemSlot that = (TelemetryItemSlot) other;
		return Objects.equals(itemId, that.itemId)
			&& Objects.equals(quantity, that.quantity)
			&& Objects.equals(name, that.name);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(itemId, quantity, name);
	}
}
