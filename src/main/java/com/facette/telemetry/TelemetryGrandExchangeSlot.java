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
 * What one Grand Exchange slot holds: the offer's state, what it is for, and how far it has got.
 * The slot's own position is not stored here, so no entry can disagree with where it sits, exactly
 * as with {@link TelemetryItemSlot}.
 *
 * An empty slot carries its state and nothing else. The client reports zeros for the other readings
 * of an empty slot, and a zero price or a zero quantity is a claim about an offer that does not
 * exist, so those become null rather than being exported as figures a reader could believe.
 *
 * These are the player's own open offers, read from the client's own view of them. No price list,
 * no market history, no other player's trading, and no valuation of anything the player owns is
 * looked up or derived.
 */
final class TelemetryGrandExchangeSlot
{
	static final TelemetryGrandExchangeSlot UNAVAILABLE = new TelemetryGrandExchangeSlot(
		null, null, null, null, null, null);

	private final String state;
	private final Integer itemId;
	private final Long price;
	private final Integer totalQuantity;
	private final Integer quantityTransacted;
	private final Long spent;

	private TelemetryGrandExchangeSlot(String state, Integer itemId, Long price,
		Integer totalQuantity, Integer quantityTransacted, Long spent)
	{
		this.state = state;
		this.itemId = itemId;
		this.price = price;
		this.totalQuantity = totalQuantity;
		this.quantityTransacted = quantityTransacted;
		this.spent = spent;
	}

	/** A slot holding no offer: its state is known and every other reading is absent. */
	static TelemetryGrandExchangeSlot empty(String state)
	{
		return new TelemetryGrandExchangeSlot(
			Objects.requireNonNull(state, "state"), null, null, null, null, null);
	}

	/**
	 * A slot holding an offer. A reading the client cannot sensibly report for a live offer — a
	 * negative item, price, or quantity, or a transacted quantity larger than the whole offer —
	 * makes this an unavailable slot rather than a slot carrying a number nobody should trust.
	 *
	 * Price and spent are longs because the client reports them as longs. They are kept whole: a
	 * figure past the int range is a real reading, not one to clamp, wrap, or call unavailable.
	 */
	static TelemetryGrandExchangeSlot offer(String state, int itemId, long price, int totalQuantity,
		int quantityTransacted, long spent)
	{
		Objects.requireNonNull(state, "state");
		if (itemId < 0 || price < 0 || totalQuantity <= 0 || quantityTransacted < 0 || spent < 0
			|| quantityTransacted > totalQuantity)
		{
			return UNAVAILABLE;
		}
		return new TelemetryGrandExchangeSlot(
			state, itemId, price, totalQuantity, quantityTransacted, spent);
	}

	String getState()
	{
		return state;
	}

	Integer getItemId()
	{
		return itemId;
	}

	Long getPrice()
	{
		return price;
	}

	Integer getTotalQuantity()
	{
		return totalQuantity;
	}

	Integer getQuantityTransacted()
	{
		return quantityTransacted;
	}

	Long getSpent()
	{
		return spent;
	}

	/**
	 * Value equality, for the same reason as every other exported value type: an offer that has not
	 * moved must not look like one that has, or the file is rewritten on every tick.
	 */
	@Override
	public boolean equals(Object other)
	{
		if (this == other)
		{
			return true;
		}
		if (!(other instanceof TelemetryGrandExchangeSlot))
		{
			return false;
		}
		TelemetryGrandExchangeSlot that = (TelemetryGrandExchangeSlot) other;
		return Objects.equals(state, that.state)
			&& Objects.equals(itemId, that.itemId)
			&& Objects.equals(price, that.price)
			&& Objects.equals(totalQuantity, that.totalQuantity)
			&& Objects.equals(quantityTransacted, that.quantityTransacted)
			&& Objects.equals(spent, that.spent);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(state, itemId, price, totalQuantity, quantityTransacted, spent);
	}
}
