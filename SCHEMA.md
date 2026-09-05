# Facette Companion export schema

The plugin writes one UTF-8 JSON object to a single local file. This document is the complete
contract for **schema version 3**.

| | |
|---|---|
| Path (Windows) | `%USERPROFILE%\.runelite\facette\state-v3.json` |
| Path (other platforms) | `~/.runelite/facette/state-v3.json` |
| Encoding | UTF-8, one JSON object, no trailing newline |
| Maximum size | 24,576 bytes |
| Configurable | No. There is one output path and it cannot be changed |

Two committed files are the canonical byte examples, and a test fails if the serializer and either
file disagree:

- [`src/test/resources/facette-osrs-state-v3.json`](src/test/resources/facette-osrs-state-v3.json), a populated snapshot
- [`src/test/resources/facette-osrs-state-v3-logged-out.json`](src/test/resources/facette-osrs-state-v3-logged-out.json), the same document carrying no player data

## Serialization rules

**Schema 3 contains only the fields documented below.** No other key appears. Future schema
versions may add or change fields after separate review.

**Schema 3 is schema 2 plus a suffix.** Every schema-2 key keeps its name, its position, and its
meaning. The six groups schema 3 adds are appended after them, so a reader written against schema 2
finds everything it already knows exactly where it was. See
[Schema history](#schema-history) for what a reader has to do to move.

**Key order is fixed** and matches the order of the sections and fields below. Each key is written
once, so a duplicate key cannot occur. Readers may rely on the order but do not have to.

**The document is bounded.** Every collection is fixed-size or bounded by a game enumeration, and
every exported string has a maximum length, so the document does not grow with play time. The
maximum size above is a write guard rather than the bound: the largest document this schema can
produce is measured in the test suite and sits well below it.

**Serialization is deterministic.** The same state produces the same bytes, and collections follow
the game's own enumeration order.

## Envelope

| Field | Type | Bound | Meaning |
|---|---|---|---|
| `schema` | integer | n/a | Always `3`. |
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

Only an NPC can appear as a target. Schema 3 does not export a player target.

Schema 3 exports `healthRatio` and `healthScale`, not exact target hitpoints. Both are present or
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

Every number under `xp` is a difference between two readings this plugin instance took itself:
experience gained during the current tracked session, not a lifetime total. Starting totals,
historical experience, level history, and the aggregate `overall` sentinel are not exported here.

Lifetime totals are exported, once, under [`skills`](#skills), where they are the client's own
current figures rather than anything this plugin accumulated. The two are kept apart deliberately:
`xp` is what happened while a reader was watching, and `skills` is where the account stands.

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

## `capabilities`

Always present, never `null`, and always carrying exactly one entry per schema-3 group, in this
order: `skills`, `account`, `quests`, `slayer`, `grandExchange`.

| Value | Meaning |
|---|---|
| `"supported"` | The producer read this group and the group is carrying its values. |
| `"unavailable"` | The producer could not read this group. Every field in it is `null`. |

**A group says `supported` exactly when it is carrying values.** Nothing is ever filled in with a
default or a last-known reading to make a group look supported, and no group is reported unavailable
while it holds a value. A test asserts both halves of that for every group.

**A group whose fresh read fails becomes unavailable immediately**, even mid-session with the player
still logged in. It does not keep what the last successful read saw. This is deliberately different
from the schema-2 fields above, and the difference is worth stating plainly: an equipment or
inventory block that the client will not answer for keeps its last value, because those fields have
no way to say "not read this tick" and blinking to empty would read as the player having taken
everything off. The schema-3 groups do have a way to say it, so they say it.

**Groups fail independently.** One group the client would not answer for does not null the rest of
the document, and does not make the snapshot stop reporting player data. That is the whole reason
this block exists.

**Treat an unrecognized value as not supported.** A later schema may name a state this one does not,
so a reader should compare against `"supported"` rather than against `"unavailable"`.

Freshness is not one of the values. How old a document is, is what `emittedAt` answers, for the
whole document at once — see [Publication and freshness](#publication-and-freshness). A per-group
freshness claim would be an inference this plugin does not make.

## `skills`

| Field | Type | Bound | Meaning |
|---|---|---|---|
| `skills.entries` | array or null | one per skill | One entry per skill the client enumerates, in the client's own skill order. |

Each entry:

| Field | Type | Bound | Meaning |
|---|---|---|---|
| `skill` | string | 24 chars | Lowercase skill name, from the client's own enumeration. |
| `level` | integer | at least 1 | The base level, unaffected by boosts and drains. |
| `boostedLevel` | integer | n/a | The current level, boosted or drained. Equal to `level` when neither applies. |
| `xp` | integer | n/a | Lifetime experience in that skill, as the client reports it. |

This is not `xp.skills`. That collection carries differences between two readings this plugin took
itself during one session; this one carries the client's current totals. The two answer different
questions and are exported side by side.

The list is bounded by the client's own skill enumeration, so a skill added to the game grows it by
one entry and changes nothing else. **The set of skills is not fixed by this document** — read the
entries rather than assuming a count or an order beyond "the client's own".

`vitals.hitpointsCurrent` and `vitals.prayerCurrent` are the same readings as the `boostedLevel` of
the corresponding entries here, and `vitals.hitpointsBase` and `vitals.prayerBase` the same as their
`level`. They are kept because schema 2 exports them.

**One unreadable skill makes the whole group unavailable.** A skill whose level or experience the
client will not report cannot be told apart from a skill at zero, so `entries` becomes `null` rather
than carrying a list that looks complete.

**A base level of zero counts as unreadable.** Every skill starts at level 1, so zero is not a low
reading — it is the client's skill arrays before they have been filled in. A `boostedLevel` of zero
is real, because a skill can be drained to nothing, and an `xp` of zero is real, because an untrained
skill has none. Only the base level is treated this way.

## `account`

| Field | Type | Bound | Meaning |
|---|---|---|---|
| `account.type` | string or null | 32 chars | The account mode's name, or `null` when the exporting build has no name for the reading. |
| `account.typeId` | integer or null | n/a | The client's own account-mode reading. |

The names this build knows are `normal`, `ironman`, `ultimate_ironman`, `hardcore_ironman`,
`group_ironman`, `hardcore_group_ironman`, and `unranked_group_ironman`.

**The reading is exported beside the name on purpose.** A mode this build has never heard of reaches
a reader as an identifier with a `null` name, rather than being rounded down to `normal`. The group
is still `supported` in that case, because the reading is real.

This is the account's **mode**, never its identity. No name, hash, handle, or anything else that
could say which account this is passes through here or anywhere else in the document.

## `quests`

| Field | Type | Meaning |
|---|---|---|
| `quests.points` | integer or null | Your quest point total. |

**Per-quest state is deliberately not exported.** Reading it means running one client script per
quest, over two hundred of them, on every sample — a cost that grows with the game rather than with
what the player is doing, and a shape a reviewer would rightly question in a plugin that otherwise
only reads accessors and variables. The quest point total answers the bounded version of the same
question for one variable read.

## `slayer`

| Field | Type | Meaning |
|---|---|---|
| `slayer.remaining` | integer or null | Creatures remaining on the assigned task. `0` when no task is assigned. |
| `slayer.taskCreatureId` | integer or null | The assigned task's creature identifier, or `null` when no task is assigned. |

The client reports the assigned creature only while a task is running, so **`taskCreatureId` is
`null` whenever `remaining` is `0`**. A `remaining` of `0` is a supported answer — "no task" — not an
unavailable group.

**No task name is exported.** The client has no stable first-party mapping from the creature
identifier to a name, and the only other route to one is reading the chat, which this plugin does
not do. The identifier is the trustworthy fact; naming it belongs to the reader.

## `grandExchange`

| Field | Type | Bound | Meaning |
|---|---|---|---|
| `grandExchange.slots` | array or null | one per slot | One entry per Grand Exchange slot the client exposes, in the client's own slot order. Eight today. |

Each entry:

| Field | Type | Bound | Meaning |
|---|---|---|---|
| `slot` | integer | n/a | The position, written from the entry's own place in the list. |
| `state` | string or null | 24 chars | Lowercase offer state: `empty`, `buying`, `bought`, `selling`, `sold`, `cancelled_buy`, `cancelled_sell`. `null` only for a slot the client would not answer for. |
| `itemId` | integer or null | n/a | The item the offer is for. |
| `price` | integer or null | n/a | The offer's own price per item, as you set it. |
| `totalQuantity` | integer or null | n/a | The whole quantity the offer is for. |
| `quantityTransacted` | integer or null | n/a | How much has changed hands so far: bought for a buy offer, sold for a sell offer. |
| `spent` | integer or null | n/a | Coins exchanged so far on this offer. |

**An `empty` slot carries its state and nothing else**; its other fields are all `null`. The client
reports zeros for a slot holding no offer, and a zero price or quantity is a claim about an offer
that does not exist. A slot whose readings contradict each other — more transacted than ordered, a
negative price — is exported with every field `null`, including `state`, rather than as an offer
carrying a figure that cannot be true.

These are **your own open offers**, read from the client's own view of them. No price guide, market
history, other player's trading, or valuation of anything you own is looked up or derived. The
client updates this view when the server tells it to, so it is your offers as the client currently
understands them rather than a live query.

`price` appears here and nowhere else in the document. A test pins that.

## Logged-in completeness

**Whenever `session.loggedIn` is `false`, every player-derived value is `null`**: every scalar,
`combat.activePrayers`, `combat.target`, `equipment.slots`, all three inventory fields, all four
experience fields, and every field of every schema-3 group. Last known values are not carried past a
logout, and the transition is atomic. Every entry in `capabilities` reads `unavailable`.

`session.loggedIn` reports whether this document's player data is valid, which is not the same
question as which game state the client is in. For up to one game tick after a login or world hop,
`session.gameState` reads `LOGGED_IN` while `session.loggedIn` is still `false`, because the plugin
has not sampled that session yet.

A snapshot with `loggedIn: true` can legitimately carry no attack style, no active prayers, no
target, empty equipment slots, an empty inventory, and no session experience. Those are real states,
and an empty array is not `null`.

A snapshot with `loggedIn: true` can also carry an unavailable schema-3 group. The schema-3 groups
are read after the completeness check and are not part of it, precisely so that one group the client
would not answer for reports itself unavailable instead of nulling the whole document.

## Session boundaries

| Event | Behaviour |
|---|---|
| Login | The first live sample establishes the session's experience comparison points and stamps `trackingStartedAt`. `loggedIn` becomes `true` only once every required value has been read. |
| Logout | Every player-derived value is nulled in one atomic transition, and the session's comparison points and accumulated gains are discarded, so a later login cannot inherit them. Every schema-3 group goes with them. |
| World hop | The session survives. Player-derived values, including every schema-3 group, are nulled while the client is between states and re-read on the next live sample, but `xp.skills`, the latest-gain fields, and `trackingStartedAt` are kept. |
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

Schema 1 was used by earlier technical-alpha builds and is superseded. Schema 2 is superseded by
schema 3. Current versions write only `state-v3.json`.

**An earlier schema's file is left exactly as it was.** A `state-v1.json` or `state-v2.json`
alongside the current file is not read, migrated, or deleted. It simply stops being updated, and a
reader still watching it sees its `emittedAt` stop advancing — which is the staleness rule that
reader already implements. Nothing it reads is rewritten into a shape it does not understand. A test
pins this for both earlier versions.

### Moving from schema 2 to schema 3

For a reader, the move is:

1. Read `state-v3.json` instead of `state-v2.json`.
2. Everything schema 2 documented is present, unchanged, under the same key at the same position.
   `schema` reads `3`.
3. Consult `capabilities` before reading any of the six groups schema 3 adds, and treat anything
   other than `"supported"` as no value.

A reader that has not moved yet keeps working against whichever schema-2 build is installed for it.
The two builds are separate installs of the plugin, not two files from one build: a client running
this version writes `state-v3.json` and nothing else.
