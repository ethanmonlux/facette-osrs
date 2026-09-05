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

/**
 * The account modes the client's own account-type variable distinguishes, and the names schema 3
 * exports for them.
 *
 * The numeric reading is exported beside the name, so a mode this build has never heard of still
 * reaches a reader truthfully as an identifier with a null name, rather than being rounded down to
 * "normal". That is the whole reason the raw value is in the document at all.
 *
 * This is the account's mode, not its identity. No name, hash, handle, or any other thing that
 * could say which account this is passes through here.
 */
enum TelemetryAccountType
{
	NORMAL(0, "normal"),
	IRONMAN(1, "ironman"),
	ULTIMATE_IRONMAN(2, "ultimate_ironman"),
	HARDCORE_IRONMAN(3, "hardcore_ironman"),
	GROUP_IRONMAN(4, "group_ironman"),
	HARDCORE_GROUP_IRONMAN(5, "hardcore_group_ironman"),
	UNRANKED_GROUP_IRONMAN(6, "unranked_group_ironman");

	private final int typeId;
	private final String exported;

	TelemetryAccountType(int typeId, String exported)
	{
		this.typeId = typeId;
		this.exported = exported;
	}

	int getTypeId()
	{
		return typeId;
	}

	String exported()
	{
		return exported;
	}

	/** The name for a reading, or null when this build has no name for it. */
	static String nameOf(int typeId)
	{
		for (TelemetryAccountType type : values())
		{
			if (type.typeId == typeId)
			{
				return type.exported;
			}
		}
		return null;
	}
}
