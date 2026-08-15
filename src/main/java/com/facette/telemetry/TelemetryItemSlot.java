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
 * What one fixed item slot holds — an item identity, a quantity, and a name, or nothing.
 *
 * <p>The slot's own position is not stored here. Equipment entries are labelled by the containing
 * collection's canonical slot names and inventory entries by their array position, so no entry can
 * disagree with where it sits.
 *
 * <p>Two states and no third: empty, where all three exported values are null, or occupied, where
 * the identity is zero or greater and the quantity is positive. Item identity {@code 0} is a real
 * item — the client signals an empty slot with a <em>negative</em> identity — so a reader decides
 * occupancy from nullability, never from {@code itemId > 0}.
 *
 * <p>Nothing here is priced, valued, or aggregated, and no RuneLite type is held, so every rule
 * is exercisable without a game client.
 */
final class TelemetryItemSlot
{
	/** The empty slot, shared because it carries no state and most slots are empty. */
	static final TelemetryItemSlot EMPTY = new TelemetryItemSlot(null, null, null);

	/**
	 * The name the game cache reports for an item it has no name for. Treated as no name rather
	 * than exported as the four characters {@code null} inside a JSON string.
	 */
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
	 * The slot as read from the client.
	 *
	 * @param itemId   the item identity; only a negative value is not an item, because the client
	 *                 signals an empty slot that way and zero is a real identity
	 * @param quantity the stack size; a non-positive value is not an item. Stack size never
	 *                 affects occupancy — one slot holding a million coins is one occupied slot
	 * @param name     the item's name as the client reports it, or null when it has none
	 * @return an occupied slot, or {@link #EMPTY} when the reading does not describe an item
	 */
	static TelemetryItemSlot of(int itemId, int quantity, String name)
	{
		if (itemId < 0 || quantity <= 0)
		{
			return EMPTY;
		}
		return new TelemetryItemSlot(itemId, quantity, normalizeName(name));
	}

	/**
	 * Trims the client's name and reduces a blank or absent one to null. The name is presentation
	 * metadata only: nothing here or downstream decides identity, occupancy, or any control flow
	 * from it. Length bounding is left to serialization, where every exported string is bounded.
	 */
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

	/**
	 * The item's name, or null when the client had none to give. Null is the one degenerate case
	 * an occupied slot can carry.
	 */
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
