# Facette Companion

A read-only RuneLite plugin that exports a bounded view of your live game state to documented local
JSON files. [Facette](https://facette.gg) can read those files as an optional separately
distributed companion.

While enabled, the plugin keeps a small set of JSON files up to date with a bounded view of your own
character. Any local tool on the same machine can read it. Facette is a Windows-first second-screen
companion for games, and this plugin is the Old School RuneScape side of it.

## Distribution

Facette Companion is intended to be installed through the RuneLite Plugin Hub. This repository
does not distribute prebuilt JAR files.

Contributor build instructions are included below for development and review. They are not an
end-user installation path.

The source is published so that anyone whose game state the plugin reads can read the code doing
the reading.

## Facette is an optional reader

Facette is an optional reader of the exported file. It is distributed separately and is not
required for the plugin to load, run, or write its documented output. Any local tool can read the
documented JSON file.

This plugin **does not launch, download, install, bundle, execute, or communicate with Facette**,
and it behaves the same whether Facette is installed, running, or absent.

## What it does and does not do

**Read-only.** The plugin reads supported RuneLite client state and writes one local snapshot as
schema-versioned files, all in the plugin's own RuneLite-managed directory, written through
RuneLite's managed file API. It performs no clicks, no keystrokes, no menu actions, no
automation, and no window manipulation, never opens an interface, and accepts no commands from
Facette or from the files it writes. Per-quest state is read through RuneLite's own `Quest`
API, which runs the game's read-only quest-status script; those reads are limited to a small batch
per game tick.

**No network communication.** Your game state does not leave your computer because of this
plugin. The plugin contains no network client or server code and makes no network requests.

**No account credentials.** The plugin does not read, store, or export your account name, account
hash, email, password, session token, or any other credential.

### Exported under schema 3

Your own character only: session and world number, combat level, hitpoints, prayer points, run
energy, special attack energy, weight, selected attack style, active prayers, the NPC you are
interacting with, your eleven equipment slots, your twenty-eight inventory slots (item id,
quantity, name), and experience *gained during the current tracked session*.

Schema 3 adds, still about your own character only: every skill's level, boosted level, and
lifetime experience; your account's mode, such as normal or ironman; your quest point total; your
current Slayer task's remaining count and creature id; and your own Grand Exchange offer slots.
Each of those groups reports whether it is carrying values, so a reader can tell "there is nothing
there" from "this could not be read".

### Exported under schema 4

Everything schema 3 exports, plus two identifiers on your current Slayer task: which boss, on a
boss task, and which area, when the task was assigned to one. They are identifiers only, never
names. Schema 4 is written to its own file beside schema 3's, so a reader of schema 3 keeps reading
exactly the document it read before.

### Exported under schema 5

Everything schema 4 exports, written so that a logged-in character is recognized even before the
client has provided its worn-items or backpack container: each group says whether it is current, a
retained earlier reading, not yet observed, or unavailable, together with when it was observed.
A random character-session identifier, made fresh at each login and never derived from your
account, ties the readings together.

Schema 5 also writes a separate account document, updated only when something in it changes:

- your **bank** and **Seed Vault** item ids and quantities (with placeholders and bank fillers
  labelled), recorded only when the game sends them because you opened the bank or the Seed Vault
  yourself. Until you do, they are reported as not observed, which says nothing about what they
  hold. They are not a list of everything you own;
- the state of each quest in RuneLite's quest list (not started, in progress, finished, or
  unavailable when it could not be read);
- whether each tier of the twelve Achievement Diary regions is complete, and whether each of the
  six Combat Achievement tiers is complete. No individual diary or Combat Achievement task, and no
  Combat Achievement points total, is exported.

All of it belongs to the current login only. It is cleared when you log out or change to a
different RuneScape profile, and as you disable the plugin (that last write is best-effort, like the
final snapshot described below), and nothing is carried into the next login. If RuneLite closes or
crashes while you are logged in, the last account document can stay on disk until the plugin next
starts and can write its folder, when its first write replaces it with one in which nothing is
observed. To remove it sooner, delete the file (see below).

### Not exported

Account name or hash, RuneLite profile keys, credentials, chat, friends, clan data, other players,
any player target, collection log, individual diary or Combat Achievement tasks, item prices or
valuations, aggregate wealth, Grand Exchange trade history, other storage, and location. Bank,
Seed Vault and per-quest state appear only in schema 5's account document, and only as described
above.

The schema 3, 4 and 5 field lists are closed and documented in **[SCHEMA.md](SCHEMA.md)**,
with committed byte-exact examples: for schema 3,
[a populated snapshot](src/test/resources/facette-osrs-state-v3.json) and
[a logged-out snapshot](src/test/resources/facette-osrs-state-v3-logged-out.json); for schema 4,
[a populated snapshot](src/test/resources/facette-osrs-state-v4.json) and
[a logged-out snapshot](src/test/resources/facette-osrs-state-v4-logged-out.json); for schema 5,
[a ready snapshot](src/test/resources/facette-osrs-state-v5.json),
[a snapshot of a character whose worn items the client has not provided](src/test/resources/facette-osrs-state-v5-partial.json),
[a logged-out snapshot](src/test/resources/facette-osrs-state-v5-logged-out.json) and
[an account document](src/test/resources/facette-osrs-account-v5.json).

## Where the files are written

On Windows, through RuneLite's managed file API:

```text
%USERPROFILE%\.runelite\plugin-data\facette-companion\state-v3.json
%USERPROFILE%\.runelite\plugin-data\facette-companion\state-v4.json
%USERPROFILE%\.runelite\plugin-data\facette-companion\state-v5.json
%USERPROFILE%\.runelite\plugin-data\facette-companion\account-v5.json
```

On other platforms it is the same location relative to RuneLite's own data directory,
`~/.runelite/plugin-data/facette-companion/`.

Those are the only four paths the plugin writes to. It has RuneLite create the directory if it is
missing, writes nothing inside your Old School RuneScape installation, does not scan your
filesystem, and has no configurable output path.

Earlier versions wrote `state-v3.json` and `state-v4.json` to `.runelite/facette`. If that
directory exists and the plugin's own directory does not yet, RuneLite moves the old directory
into place when the plugin first writes. Otherwise the old directory is left as it was and no
longer updated; you can delete it.

Values are resampled each game tick and republished when they change, at most four times per
second, plus a heartbeat at least every two seconds so a reader can tell a live plugin from a
stale file. The file is replaced whole each time, so a reader never sees a half-written document.

## Stopping the export and removing the data

To stop the export, **disable the Facette Companion plugin** in RuneLite's plugin list. The plugin
queues one final snapshot on the way out, reporting `pluginActive: false`, `loggedIn: false`, and
every gameplay-derived field `null`, and then writes nothing further.

That final write is asynchronous and best-effort. It is handed to the plugin's own publisher thread
so that disabling the plugin never makes the client wait on the filesystem, and that thread is a
daemon, so it cannot hold the client open. Closing RuneLite is not disabling the plugin: RuneLite
does not stop plugins when it exits, so no final snapshot is written and the files stay at their
last active snapshot. If RuneLite is killed, no final snapshot is written either. In every one of those
cases the file simply stops being updated, so treat it as stale once `emittedAt` stops advancing
rather than waiting for an inactive snapshot that may never arrive. What the plugin never does is
report an orderly shutdown that did not happen.

To remove the exported data, delete the plugin's directory (and, if an earlier version left one,
`%USERPROFILE%\.runelite\facette`):

```text
%USERPROFILE%\.runelite\plugin-data\facette-companion
```

Deleting it removes the exported files, including the account document, and nothing else. The plugin keeps no history, database, log
of your play, or copy of the data anywhere else. If the plugin is still enabled, it recreates the
directory and the files on its next publication.

## Contributor workflow

This section is for people working on the plugin. It is a development and review workflow, **not**
an installation path.

Requires JDK 11 (Eclipse Temurin recommended). The Gradle wrapper is included, so no separate
Gradle install is needed.

```sh
./gradlew clean test    # run the test suite
./gradlew clean build   # build the plugin
./gradlew run           # launch a RuneLite development client with the plugin loaded
```

Then enable **Facette Companion** in the client's plugin list and confirm that `state-v3.json`,
`state-v4.json` and `state-v5.json` appear at the paths above. `account-v5.json` appears as the
plugin starts, with nothing observed, and fills in a few seconds after you log in.

If your account requires Jagex Account authorization for the development client, that is yours to
handle privately in your own environment. **Never paste an account credential, token, or session
file into an issue, a pull request, a log, or this repository.** The plugin and this contributor
workflow do not require account credentials to be placed in any of them.

See [CONTRIBUTING.md](CONTRIBUTING.md) for the boundaries a change has to stay inside.

## Reporting a problem

Open an issue. Before attaching a snapshot, read it and decide whether you are comfortable sharing
the gameplay state it contains. No file includes account identity or credentials, but each includes
the fields documented in [SCHEMA.md](SCHEMA.md), and `account-v5.json` includes your bank and Seed
Vault contents when you have opened them, your quest states, and your diary and Combat Achievement tier completion.

## License and affiliation

Source code in this repository is licensed under the BSD 2-Clause License. See [LICENSE](LICENSE).

"Facette" and associated branding are not granted under this license.

Old School RuneScape is a trademark of Jagex Ltd. RuneLite is an independent open-source project.
Facette Companion is an independent third-party project and is **not affiliated with or endorsed
by Jagex Ltd. or the RuneLite project**.
