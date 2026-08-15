# Facette Companion export schema

The plugin writes one UTF-8 JSON object to a single local file. This document is the complete
contract for **schema version 2**.

Two committed files are the canonical byte examples, and a test fails if the serializer and either
file ever disagree:

- [`src/test/resources/facette-osrs-state-v2.json`](src/test/resources/facette-osrs-state-v2.json) — a populated snapshot
- [`src/test/resources/facette-osrs-state-v2-logged-out.json`](src/test/resources/facette-osrs-state-v2-logged-out.json) — the same document carrying no player data

## Output

| | |
|---|---|
| Path (Windows) | `%USERPROFILE%\.runelite\facette\state-v2.json` |
| Path (other platforms) | `~/.runelite/facette/state-v2.json` |
| Encoding | UTF-8, one JSON object, no trailing newline |
| Maximum size | 16,384 bytes |
| Configurable | No. There is exactly one output path and it cannot be changed |

## Contract rules

**Schema 2 is closed.** The fields below are all of them. No other key ever appears, and adding
one is a deliberate change to this document and to the version number, not an implementation
detail.

**Key order is fixed** and is exactly the order in which the sections and fields appear below.
Every key is written literally, exactly once, so a duplicate key is impossible by construction.
A reader may rely on the order, but does not have to: the document is ordinary JSON.

**The document is bounded.** Every collection is fixed-size or bounded by a game enumeration —
eleven equipment slots, twenty-eight inventory slots, at most one entry per prayer, at most 32
per-skill experience entries — and every exported string has a maximum length, so the document
cannot grow with how long you play.

**Serialization is deterministic.** The same state produces the same bytes. Collections are
ordered by the game's own enumeration order, never by arrival, name, or size, so a reader diffing
two snapshots sees only real changes.

## Envelope

| Field | Type | Bound | Meaning |
|---|---|---|---|
| `schema` | integer | — | Always `2`. |
| `source` | string | — | Always `"runelite"`. |
| `instanceId` | string | 36 chars | A random UUID generated fresh each time the plugin starts. Not derived from your account, profile, machine, or any game state; it only lets a reader notice a restart. |
| `seq` | integer | — | Increases by one for each snapshot that actually reached the file, starting at `0`. A refused or failed write does not consume a number, so the next attempt reuses it. |
| `emittedAt` | integer | — | Unix time in milliseconds when the snapshot was built. |

## `session`

| Field | Type | Bound | Meaning |
|---|---|---|---|
| `session.pluginActive` | boolean | — | Whether the plugin is running. `false` only in the final snapshot written as the plugin is disabled or the client shuts down. |
| `session.gameState` | string | 32 chars | The RuneLite game-state name, e.g. `LOGGED_IN`, `LOGIN_SCREEN`, `LOGGING_IN`, `HOPPING`. |
| `session.loggedIn` | boolean | — | Whether this snapshot carries valid live player data. See [Logged-in completeness](#logged-in-completeness) — this is not a copy of `gameState`. |
| `session.world` | integer or null | — | World number. |
| `session.combatLevel` | integer or null | — | Your combat level. `null` before the local player has resolved. |
| `session.trackingStartedAt` | integer or null | — | Unix time in milliseconds when **this plugin instance** established its comparison points for the current session. Not your account's login time and not claimed to be: enabling the plugin an hour into a session sets this to that moment. Never later than `emittedAt`. |

## `vitals`

| Field | Type | Meaning |
|---|---|---|
| `vitals.hitpointsCurrent` | integer or null | Current Hitpoints level, boosted or drained. |
| `vitals.hitpointsBase` | integer or null | Base Hitpoints level. |
| `vitals.prayerCurrent` | integer or null | Current Prayer points. |
| `vitals.prayerBase` | integer or null | Base Prayer level. |
| `vitals.runEnergyPercent` | integer or null | Run energy, `0`–`100`. Normalized from the client's 1/100th-of-a-percent reading. |
| `vitals.specialAttackPercent` | integer or null | Special attack energy, `0`–`100`. Normalized from the client's 1/10th-of-a-percent reading. |
| `vitals.weightKg` | integer or null | The weight the client reports, in kilograms. Negative when weight-reducing equipment outweighs what you carry. `null` if the client reports a value outside `-1000`–`1000000`, which no real load reaches — reported as unavailable rather than clamped. |

## `combat`

| Field | Type | Bound | Meaning |
|---|---|---|---|
| `combat.attackStyle` | string or null | 32 chars | The currently selected attack style, lowercased — for example `accurate`, `aggressive`, `controlled`, `defensive`, `ranging`, `longrange`, `casting`. |
| `combat.activePrayers` | array of strings, or null | 32 chars each | Lowercase RuneLite prayer names for the prayers currently active, in RuneLite's own prayer order, with no duplicates. An **empty array** means no prayer is active; `null` means the snapshot carries no player data at all. |
| `combat.target` | object or null | — | The NPC you are currently interacting with; `null` when there is none. |

### Attack-style limitations

The label is the game's own, read from the game's style data for your equipped weapon: weapon
category → style list → style entry → name parameter. Nothing maps a number onto a word.

`null` whenever no trustworthy reading exists, which is a normal steady state rather than an
error. It occurs when any step of that lookup fails, when the game's own data marks the position
as having no style, and for the handful of weapon categories the game's style enumeration has no
entry for — the client falls back to hardcoded lists for those, and this plugin reports no reading
instead of copying them.

### `combat.target`, when present

| Field | Type | Bound | Meaning |
|---|---|---|---|
| `target.kind` | string | — | Always `"npc"`. There is no other permitted kind. |
| `target.id` | integer | — | The NPC's identifier. |
| `target.name` | string or null | 48 chars | The NPC's current display name, or `null` when it has none. |
| `target.combatLevel` | integer or null | — | The NPC's combat level, or `null` when it has none. |
| `target.healthRatio` | integer or null | — | The health the server actually transmits, in `healthScale` units. |
| `target.healthScale` | integer or null | — | The maximum `healthRatio` can be for this actor. |
| `target.dead` | boolean | — | The observable dead state of the actor. |

### NPC-only targets

**Only the NPC you are interacting with can appear as a target.** A player can be interacted with
too — followed, traded, attacked — and that actor carries another person's display name. No player
target is ever exported, and no other player's name ever leaves the client. If what you are
interacting with is not an NPC, `combat.target` is `null` and nothing about it is read.

### Target health limitations

**No exact target hitpoints are exported or estimated.** The server does not transmit an NPC's
real health; it transmits the ratio and scale above, and that is all this file carries.
`healthRatio` and `healthScale` are always either both present or both `null` — a ratio without
its scale means nothing. Both are `null` when the server transmits no health for that actor, when
the scale is non-positive, or when the ratio exceeds its scale.

## `equipment`

`equipment.slots` is `null` when the snapshot carries no player data, and otherwise an array of
**exactly eleven** entries, always in this order:

`head`, `cape`, `amulet`, `weapon`, `body`, `shield`, `legs`, `gloves`, `boots`, `ring`, `ammo`

The three RuneLite equipment positions that only exist on the player model — arms, hair, and jaw —
are never read and never appear. Each entry's `slot` label comes from this contract list rather
than from the entry itself, so an exported slot name can never disagree with the position it sits
at.

## `inventory`

| Field | Type | Meaning |
|---|---|---|
| `inventory.usedSlots` | integer or null | Inventory slots holding an item, `0`–`28`. Slot occupancy, never item quantity. |
| `inventory.freeSlots` | integer or null | Empty inventory slots. `usedSlots` and `freeSlots` always sum to `28`. |
| `inventory.slots` | array or null | **Exactly twenty-eight** entries, in ascending slot order `0`–`27`. Each entry's `slot` is written from its own position. |

## Item slot entries

Every equipment and inventory entry has the same four fields, in this order:

| Field | Type | Bound | Meaning |
|---|---|---|---|
| `slot` | string or integer | — | The canonical slot name for equipment, or the slot's position `0`–`27` for inventory. |
| `itemId` | integer or null | — | The item's identifier. |
| `quantity` | integer or null | — | The stack size. A stack of a million coins is still **one** occupied slot. |
| `name` | string or null | 48 chars | The item's name as the game reports it, for presentation only. |

An **empty** slot has `itemId`, `quantity`, and `name` all `null`. An **occupied** slot has an
`itemId` of `0` or greater and a positive `quantity`. There is nothing in between.

### Item identity zero, and the negative-empty sentinel

> **Reading occupancy: decide from nullability — `itemId !== null` — never from `itemId > 0`.**

Item identity `0` is a real item in the game's own item enumeration, so a slot holding it is
occupied and counts towards `usedSlots`. The game client signals an *empty* slot with a
**negative** identity internally. That negative value never appears in this file, because an empty
slot is always exported as the three nulls above.

Identity is always the numeric `itemId`. The `name` is presentation metadata: nothing in the
plugin decides identity, occupancy, or control flow from it, and it is deliberately the item's
members' name so the same item reads the same on a free and a members world.

### Occupied with `name: null`

An occupied slot can legitimately carry `name: null`, in the one degenerate case where the client
has no name for an item it is holding. The slot is still occupied and still counts towards
`usedSlots`. A blank name, and the literal four characters the game cache uses to mean "no name",
are both reduced to `null` rather than exported as text.

### What is never in an item entry

No price, high-alchemy value, Grand Exchange data, tradeability, examine text, bank content, total
value, loadout history, sprite, or artwork is read or exported. Nothing in this file lets a reader
work out what your items are worth.

## `xp`

| Field | Type | Bound | Meaning |
|---|---|---|---|
| `xp.lastSkill` | string or null | 24 chars | Lowercase name of the skill that most recently gained experience. |
| `xp.lastDelta` | integer or null | — | Size of that most recent gain. |
| `xp.lastChangedAt` | integer or null | — | Unix time in milliseconds of that gain. Never later than `emittedAt`. |
| `xp.skills` | array or null | 32 entries | One entry per skill that has gained experience during the current tracked session, ordered by RuneLite's own skill order. Possibly empty. |

Each `xp.skills` entry:

| Field | Type | Bound | Meaning |
|---|---|---|---|
| `skill` | string | 24 chars | Lowercase skill name. |
| `gained` | integer | — | Experience gained in that skill **during this tracked session only**. Always positive. |
| `lastDelta` | integer | — | That skill's most recent single gain. Always positive and never larger than `gained`. |
| `lastChangedAt` | integer | — | Unix time in milliseconds of that gain. Never later than `emittedAt`. |

### Current state versus session-local experience

Two different things live in this file, and the difference matters:

- **Current state** — `session`, `vitals`, `combat`, `equipment`, and `inventory` describe the
  game *right now*. Each is replaced wholesale on the next sample. The inventory is a snapshot of
  what you are carrying, not a log of what you have carried; nothing here accumulates and nothing
  here becomes a history.
- **Session-local accumulated experience** — `xp.skills` is the one place anything adds up, and it
  only adds up within the session this plugin instance has been tracking.

**Total experience is never exported.** Neither is your starting total, your historical experience,
your level history, or any daily figure. Every number under `xp` is a difference between two
readings this plugin instance took itself, so a session gain of `130` says exactly the same thing
about a level 3 character and a maxed one. The aggregate `overall` sentinel is never exported.

### The unmeasurable first gain

An experience event carries a running *total*, not a delta. A gain is therefore the difference
between two totals, and the first observation for a skill in a session has nothing to subtract
from: it establishes the comparison point and reports no gain.

That first gain is genuinely unmeasurable and is left unreported rather than invented. The same
rule means a total of zero never becomes a comparison point — a skill's data can read zero while
the client is still initializing, and anchoring there would export the character's entire skill
total through a field only ever meant to carry a change.

### Startup-window experience

Startup is deferred onto RuneLite's client thread, so experience events can arrive before the
plugin has any comparison point. Those totals are retained — one aggregate entry per skill,
bounded however long startup is queued — so that experience earned while startup was waiting is
not silently absorbed when the comparison points are finally established.

If that window contained two or more distinct increasing totals, the span between them is a
genuine gain and is reported. `gained` receives the whole span; `lastDelta` receives only the most
recent single increase, because those are different questions and conflating them would overstate
one event. If the retained evidence disagrees with the total the live client then reports, nothing
is exported for that skill rather than a fabricated gain.

### Sequence, instance, and recency

`instanceId` changes on every plugin start and `seq` restarts at `0` with it, so a reader that
sees a lower `seq` under a new `instanceId` is looking at a restart, not at a rewind.

Which gain is "most recent" is decided by arrival order, not by `lastChangedAt`: two skills whose
events arrive in one game tick commonly share a millisecond, and a wall clock adjusted backwards
would make an older event look newer. Arrival order is never exported.

Three separate sources are involved, and no clock adjustment can disturb any of them: exported
timestamps come from wall-clock time, publication intervals from a monotonic clock, and event
ordering from a plain arrival counter that is not a clock at all.

## Lifecycle

### Logged-in completeness

**Whenever `session.loggedIn` is `false`, every player-derived value is `null`** — every scalar,
`combat.activePrayers`, `combat.target`, `equipment.slots`, all three inventory fields, and all
four experience fields. The plugin does not keep showing your last known values after you log out,
and the transition is atomic: there is no snapshot with `loggedIn: false` that still carries
gameplay values.

`session.loggedIn` reports **whether the player data in this document is valid**, which is not
quite the same question as which game state the client is in. For up to one game tick after a
login or a world hop, `session.gameState` reads `LOGGED_IN` while `session.loggedIn` is still
`false`, because the plugin has not yet sampled that session. The alternative would be a document
claiming a live session while asserting an empty inventory and no active prayers.

Conversely, a snapshot with `loggedIn: true` can legitimately contain no attack-style reading, no
active prayers, no target, empty equipment slots, an entirely empty inventory, and no session
experience. Those are real states, and each is distinguishable from the logged-out nulling above:
an empty array is not `null`.

### Login, logout, world hop, and session boundaries

| Event | Behaviour |
|---|---|
| Login | The first live sample establishes the session's experience comparison points and stamps `trackingStartedAt`. `loggedIn` becomes `true` only once every required value has been read. |
| Logout | Every player-derived value is nulled in one atomic transition, and the session's comparison points and accumulated gains are discarded, so a later login cannot inherit them. |
| World hop | The session survives. Player-derived values are nulled while the client is between states and re-read on the next live sample, but `xp.skills`, the latest-gain fields, and `trackingStartedAt` are kept — a hop must not erase the session's experience. |
| Enabling mid-session | Comparison points are seeded from the client's current totals, so the next real gain is exported rather than consumed. `trackingStartedAt` is that moment, not the login. |
| Disable / shutdown | One final snapshot is written with `pluginActive: false`, `loggedIn: false`, and every gameplay-derived field `null`. Nothing is written afterwards. |
| Client killed | No final snapshot is written. The file stays as it was and goes stale; it never pretends an orderly shutdown happened. |

Every login passes through the `LOGGING_IN` game state, which is what distinguishes a genuine
session boundary from a world hop or a brief loading screen.

### Freshness and staleness

The plugin resamples each game tick and republishes when something changed, at most four times per
second. An unchanged snapshot is republished at least every two seconds as a heartbeat.

A reader can therefore treat a file whose `emittedAt` has not advanced in appreciably more than
two seconds as stale — the producing client is gone, was killed, or the plugin was disabled
without an orderly shutdown. The heartbeat interval is measured against a monotonic clock, so a
system clock adjustment does not suspend it.

### Whole-file staging and replacement

The target file is never streamed into and never partially overwritten. Each publication is
serialized into a temporary sibling in the same directory, forced to disk, closed, and only then
moved over the target — atomically where the filesystem supports it, otherwise by a plain
replacing move of the already-complete sibling.

**A reader that opens the target therefore sees either the previous snapshot or the new one, never
a partial document.** That guarantee is about content: no partial-parse handling is needed, and a
successful read is always a complete document.

It is not a guarantee that the file is openable at every instant. Where the filesystem supports
`ATOMIC_MOVE` the replacement is indivisible, but the fallback is a plain replacing move, which
offers no continuous-availability guarantee — a reader can lose a race with it and get a
missing-file or sharing error. **Treat a failed open or a transient IO error as "try again
shortly", not as a fault.** A publication happens at most four times per second, so a retry a few
tens of milliseconds later succeeds; a reader that reports the first failed open as an error will
manufacture errors out of ordinary publication races.

Temporary files are named with this writer's own versioned prefix. Abandoned ones — only possible
when a client was killed mid-write — are swept once per run, and only when older than 60 seconds,
so a second client publishing at the same time never has its in-flight file removed.

### Schema 1 and `state-v1.json`

Schema 1 is **superseded**. The current source writes schema 2 only, to `state-v2.json`. It does
not write `state-v1.json`, and it does not write both.

If an earlier build left a `state-v1.json` behind, this one **does not read it, does not migrate
it, and does not delete it** — it is not even enumerated. It stays exactly as it was and simply
stops being updated, so anything still reading it can see for itself that it has gone stale.
Removing it is yours to do; deleting the whole `facette` directory removes it along with
everything else.
