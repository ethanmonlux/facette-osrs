# Facette Companion export schema

The plugin writes one UTF-8 JSON object to a single local file. This document is the complete
contract for **schema version 2**.

| | |
|---|---|
| Path (Windows) | `%USERPROFILE%\.runelite\facette\state-v2.json` |
| Path (other platforms) | `~/.runelite/facette/state-v2.json` |
| Encoding | UTF-8, one JSON object, no trailing newline |
| Maximum size | 16,384 bytes |
| Configurable | No. There is one output path and it cannot be changed |

Two committed files are the canonical byte examples, and a test fails if the serializer and either
file disagree:

- [`src/test/resources/facette-osrs-state-v2.json`](src/test/resources/facette-osrs-state-v2.json), a populated snapshot
- [`src/test/resources/facette-osrs-state-v2-logged-out.json`](src/test/resources/facette-osrs-state-v2-logged-out.json), the same document carrying no player data

## Serialization rules

**Schema 2 contains only the fields documented below.** No other key appears. Future schema
versions may add or change fields after separate review.

**Key order is fixed** and matches the order of the sections and fields below. Each key is written
once, so a duplicate key cannot occur. Readers may rely on the order but do not have to.

**The document is bounded.** Every collection is fixed-size or bounded by a game enumeration, and
every exported string has a maximum length, so the document does not grow with play time.

**Serialization is deterministic.** The same state produces the same bytes, and collections follow
the game's own enumeration order.

## Envelope

| Field | Type | Bound | Meaning |
|---|---|---|---|
| `schema` | integer | n/a | Always `2`. |
| `source` | string | n/a | Always `"runelite"`. |
| `instanceId` | string | 36 chars | A random UUID generated fresh each time the plugin starts. Not derived from your account, profile, machine, or game state; it only lets a reader notice a restart. |
| `seq` | integer | n/a | Increases by one for each snapshot that reached the file, starting at `0`. A refused or failed write does not consume a number, so the next attempt reuses it. |
| `emittedAt` | integer | n/a | Unix time in milliseconds when the snapshot was built. |

## `session`

| Field | Type | Bound | Meaning |
|---|---|---|---|
| `session.pluginActive` | boolean | n/a | Whether the plugin is running. `false` only in the final snapshot queued as the plugin is disabled or the client shuts down. That snapshot is best-effort and is not guaranteed to be written; see [Session boundaries](#session-boundaries). |
| `session.gameState` | string | 32 chars | The RuneLite game-state name, for example `LOGGED_IN`, `LOGIN_SCREEN`, `LOGGING_IN`, `HOPPING`. |
| `session.loggedIn` | boolean | n/a | Whether this snapshot carries valid live player data. Not a copy of `gameState`; see [Logged-in completeness](#logged-in-completeness). |
| `session.world` | integer or null | n/a | World number. |
| `session.combatLevel` | integer or null | n/a | Your combat level. `null` before the local player has resolved. |
| `session.trackingStartedAt` | integer or null | n/a | Unix time in milliseconds when this plugin instance established its comparison points for the current session. This is not your login time: enabling the plugin an hour into a session sets it to that moment. Never later than `emittedAt`. |

## `vitals`

| Field | Type | Meaning |
|---|---|---|
| `vitals.hitpointsCurrent` | integer or null | Current Hitpoints level, boosted or drained. |
| `vitals.hitpointsBase` | integer or null | Base Hitpoints level. |
| `vitals.prayerCurrent` | integer or null | Current Prayer points. |
| `vitals.prayerBase` | integer or null | Base Prayer level. |
| `vitals.runEnergyPercent` | integer or null | Run energy, `0` to `100`, normalized from the client's 1/100th-of-a-percent reading. |
| `vitals.specialAttackPercent` | integer or null | Special attack energy, `0` to `100`, normalized from the client's 1/10th-of-a-percent reading. |
| `vitals.weightKg` | integer or null | Weight in kilograms, negative when weight-reducing equipment outweighs what you carry. `null` when the client reports a value outside `-1000` to `1000000`, which is reported as unavailable rather than clamped. |

## `combat`

| Field | Type | Bound | Meaning |
|---|---|---|---|
| `combat.attackStyle` | string or null | 32 chars | The selected attack style, lowercased, for example `accurate`, `aggressive`, `controlled`, `defensive`, `ranging`, `longrange`, `casting`. |
| `combat.activePrayers` | array of strings, or null | 32 chars each | Lowercase RuneLite prayer names for the active prayers, in RuneLite's prayer order, with no duplicates. An empty array means no prayer is active; `null` means the snapshot carries no player data. |
| `combat.target` | object or null | n/a | Populated only when the local player is interacting with an NPC. Otherwise `null`. |

`combat.attackStyle` is the game's own label, read from the style data for your equipped weapon.
It is `null` when no trustworthy reading exists, which is a normal state rather than an error: when
any step of that lookup fails, when the game's data marks the position as having no style, and for
the weapon categories the game's style enumeration has no entry for.

### `combat.target`, when present

| Field | Type | Bound | Meaning |
|---|---|---|---|
| `target.kind` | string | n/a | Always `"npc"`. There is no other permitted kind. |
| `target.id` | integer | n/a | The NPC's identifier. |
| `target.name` | string or null | 48 chars | The NPC's display name, or `null` when it has none. |
| `target.combatLevel` | integer or null | n/a | The NPC's combat level, or `null` when it has none. |
| `target.healthRatio` | integer or null | n/a | The health the server transmits, in `healthScale` units. |
| `target.healthScale` | integer or null | n/a | The maximum `healthRatio` can be for this actor. |
| `target.dead` | boolean | n/a | The observable dead state of the actor. |

Only an NPC can appear as a target. Schema 2 does not export a player target.

Schema 2 exports `healthRatio` and `healthScale`, not exact target hitpoints. Both are present or
both are `null`, since a ratio without its scale means nothing. Both are `null` when the server
transmits no health for that actor, when the scale is non-positive, or when the ratio exceeds its
scale.

## `equipment`

`equipment.slots` is `null` when the snapshot carries no player data, and otherwise an array of
**exactly eleven** entries, always in this order:

`head`, `cape`, `amulet`, `weapon`, `body`, `shield`, `legs`, `gloves`, `boots`, `ring`, `ammo`

Each entry's `slot` label comes from this list rather than from the entry, so a slot name cannot
disagree with its position. The three RuneLite positions that exist only on the player model (arms,
hair, and jaw) are never read and never appear.

## `inventory`

| Field | Type | Meaning |
|---|---|---|
| `inventory.usedSlots` | integer or null | Inventory slots holding an item, `0` to `28`. Slot occupancy, never item quantity. |
| `inventory.freeSlots` | integer or null | Empty inventory slots. `usedSlots` and `freeSlots` always sum to `28`. |
| `inventory.slots` | array or null | **Exactly twenty-eight** entries, in ascending slot order `0` to `27`. Each entry's `slot` is written from its own position. |

## Item slot entries

Every equipment and inventory entry has the same four fields, in this order:

| Field | Type | Bound | Meaning |
|---|---|---|---|
| `slot` | string or integer | n/a | The canonical slot name for equipment, or the position `0` to `27` for inventory. |
| `itemId` | integer or null | n/a | The item's identifier. |
| `quantity` | integer or null | n/a | The stack size. A stack of a million coins is still one occupied slot. |
| `name` | string or null | 48 chars | The item's name as the game reports it, for presentation only. |

An **empty** slot has `itemId`, `quantity`, and `name` all `null`. An **occupied** slot has an
`itemId` of `0` or greater and a positive `quantity`. There is nothing in between.

> **Decide occupancy from nullability, `itemId !== null`, never from `itemId > 0`.**

Item identity `0` is a real item, so a slot holding it is occupied and counts towards `usedSlots`.
The client's internal negative identity for an empty slot never appears here.

An occupied slot can carry `name: null` when the client has no name for the item. A blank name, and
the four characters the game cache uses to mean "no name", both become `null`. Identity is always
the numeric `itemId`; `name` is the item's members' name, so the same item reads the same on a free
and a members world. Item entries carry no price, value, Grand Exchange data, tradeability, examine
text, or artwork.

## `xp`

| Field | Type | Bound | Meaning |
|---|---|---|---|
| `xp.lastSkill` | string or null | 24 chars | Lowercase name of the skill that most recently gained experience. |
| `xp.lastDelta` | integer or null | n/a | Size of that most recent gain. |
| `xp.lastChangedAt` | integer or null | n/a | Unix time in milliseconds of that gain. Never later than `emittedAt`. |
| `xp.skills` | array or null | 32 entries | One entry per skill that has gained experience during the current tracked session, in RuneLite's skill order. Possibly empty. |

Each `xp.skills` entry:

| Field | Type | Bound | Meaning |
|---|---|---|---|
| `skill` | string | 24 chars | Lowercase skill name. |
| `gained` | integer | n/a | Experience gained in that skill during this tracked session. Always positive. |
| `lastDelta` | integer | n/a | That skill's most recent single gain. Always positive and never larger than `gained`. |
| `lastChangedAt` | integer | n/a | Unix time in milliseconds of that gain. Never later than `emittedAt`. |

## Session experience behavior

Schema 2 exports experience gained during the current tracked session, not lifetime XP totals.
Every number under `xp` is a difference between two readings this plugin instance took itself.
Starting totals, historical experience, level history, and the aggregate `overall` sentinel are not
exported.

`xp.skills` is the only place anything accumulates. The other sections describe the game right now
and are replaced wholesale on the next sample.

**An observation for a skill with no comparison point yet is not reported as a gain.** Events carry
a running total rather than a delta, so an observation with nothing to subtract from can only set
the comparison point. This is not the common case: the first live sample of a session seeds a
comparison point for every skill from the client's current totals, including when the plugin is
enabled mid-session, so the next real gain is exported. A total of zero never becomes a comparison
point, because a skill can read zero while the client is still initializing.

**Experience arriving while the plugin is starting is preserved only when it can be measured.**
Startup runs on RuneLite's client thread, so events can arrive before a comparison point exists,
and those totals are retained. A retained window is exported only when it holds two or more
distinct increasing totals, since the span between them is the only measurable part: `gained`
receives the whole span and `lastDelta` only the most recent increase. A window holding exactly one
total exports nothing, and experience earned between the last retained total and the live seed is
absorbed into the baseline rather than exported. If the retained evidence disagrees with the total
the live client then reports, nothing is exported for that skill.

**Recency follows arrival order, not `lastChangedAt`.** Events arriving in one tick commonly share
a millisecond, and a backward clock adjustment would make an older event look newer. Arrival order
is not exported. Exported timestamps come from wall-clock time; publication intervals are measured
against a monotonic clock.

`instanceId` changes on every plugin start and `seq` restarts at `0` with it, so a lower `seq`
under a new `instanceId` is a restart rather than a rewind.

## Logged-in completeness

**Whenever `session.loggedIn` is `false`, every player-derived value is `null`**: every scalar,
`combat.activePrayers`, `combat.target`, `equipment.slots`, all three inventory fields, and all four
experience fields. Last known values are not carried past a logout, and the transition is atomic.

`session.loggedIn` reports whether this document's player data is valid, which is not the same
question as which game state the client is in. For up to one game tick after a login or world hop,
`session.gameState` reads `LOGGED_IN` while `session.loggedIn` is still `false`, because the plugin
has not sampled that session yet.

A snapshot with `loggedIn: true` can legitimately carry no attack style, no active prayers, no
target, empty equipment slots, an empty inventory, and no session experience. Those are real states,
and an empty array is not `null`.

## Session boundaries

| Event | Behaviour |
|---|---|
| Login | The first live sample establishes the session's experience comparison points and stamps `trackingStartedAt`. `loggedIn` becomes `true` only once every required value has been read. |
| Logout | Every player-derived value is nulled in one atomic transition, and the session's comparison points and accumulated gains are discarded, so a later login cannot inherit them. |
| World hop | The session survives. Player-derived values are nulled while the client is between states and re-read on the next live sample, but `xp.skills`, the latest-gain fields, and `trackingStartedAt` are kept. |
| Enabling mid-session | Comparison points are seeded from the client's current totals, so the next real gain is exported rather than consumed. `trackingStartedAt` is that moment, not the login. |
| Disable or shutdown | One final snapshot is queued with `pluginActive: false`, `loggedIn: false`, and every gameplay-derived field `null`. Nothing is written afterwards. That write is asynchronous and best-effort: it is handed to the plugin's own publisher thread so the client is never made to wait on the filesystem, and that thread is a daemon, so during an orderly client exit the JVM may terminate before the write completes and the file is left at its last active snapshot. Do not treat an inactive snapshot as guaranteed; fall back to the staleness rule in [Publication and freshness](#publication-and-freshness). |
| Client killed | No final snapshot is written. The file stays as it was and goes stale. |

Every login passes through the `LOGGING_IN` game state, which is what distinguishes a genuine
session boundary from a world hop or a loading screen.

## Publication and freshness

The plugin resamples each game tick and republishes when something changed, at most four times per
second. An unchanged snapshot is republished at least every two seconds as a heartbeat, measured
against a monotonic clock so a system clock adjustment does not suspend it. A reader can treat a
file whose `emittedAt` has not advanced in appreciably more than two seconds as stale.

That staleness rule is also how a reader learns the plugin has stopped. The final inactive snapshot
is best-effort and may never be written — see [Session boundaries](#session-boundaries) — so a reader
must not wait for `pluginActive: false` before concluding that a file is no longer live.

Each publication is written to a temporary file and then moved over the target, so **a reader that
opens the target sees either the previous snapshot or the new one, never a partial document.** No
partial-parse handling is needed.

That guarantee is about content, not availability. Where the filesystem supports `ATOMIC_MOVE` the
replacement is indivisible; otherwise it is a plain replacing move, and a reader can lose a race
with it and get a missing-file or sharing error. **Treat a failed open or a transient IO error as
"try again shortly", not as a fault.** A retry a few tens of milliseconds later succeeds.

## Schema history

Schema 1 was used by earlier technical-alpha builds and is superseded by schema 2. Current versions
write only `state-v2.json`. Existing `state-v1.json` files are left unchanged: they are not read,
migrated, or deleted, and they simply stop being updated.
