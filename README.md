# Facette Companion

A read-only RuneLite plugin that exports a bounded view of your live game state to one documented
local JSON file. [Facette](https://facette.gg) can read that file as an optional separately
distributed companion.

While enabled, the plugin keeps one small JSON file up to date with a bounded view of your own
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

**Read-only.** The plugin reads supported RuneLite client state and writes one local snapshot
file. It performs no clicks, no keystrokes, no menu actions, no automation, and no window
manipulation, and it accepts no commands from Facette or from the file it writes.

**No network communication.** Your game state does not leave your computer because of this
plugin. The plugin contains no network client or server code and makes no network requests.

**No account credentials.** The plugin does not read, store, or export your account name, account
hash, email, password, session token, or any other credential.

### Exported under schema 2

Your own character only: session and world number, combat level, hitpoints, prayer points, run
energy, special attack energy, weight, selected attack style, active prayers, the NPC you are
interacting with, your eleven equipment slots, your twenty-eight inventory slots (item id,
quantity, name), and experience *gained during the current tracked session*.

### Not exported under schema 2

Account name or hash, credentials, chat, friends, clan data, other players, any player target,
bank contents, Grand Exchange data, item prices, aggregate wealth, quest or Slayer state, total or
historical account experience, and location.

The schema 2 field list is closed and documented in **[SCHEMA.md](SCHEMA.md)**, with two committed
byte-exact examples: [a populated snapshot](src/test/resources/facette-osrs-state-v2.json) and
[a logged-out snapshot](src/test/resources/facette-osrs-state-v2-logged-out.json).

## Where the file is written

On Windows:

```text
%USERPROFILE%\.runelite\facette\state-v2.json
```

On other platforms it is the same location relative to RuneLite's own data directory,
`~/.runelite/facette/state-v2.json`.

That is the only path the plugin writes to. It creates the `facette` directory if it is missing,
writes nothing inside your Old School RuneScape installation, does not scan your filesystem, and
has no configurable output path.

Values are resampled each game tick and republished when they change, at most four times per
second, plus a heartbeat at least every two seconds so a reader can tell a live plugin from a
stale file. The file is replaced whole each time, so a reader never sees a half-written document.

## Stopping the export and removing the data

To stop the export, **disable the Facette Companion plugin** in RuneLite's plugin list. The plugin
writes one final snapshot on the way out, reporting `pluginActive: false`, `loggedIn: false`, and
every gameplay-derived field `null`, and then writes nothing further. If RuneLite is killed rather
than closed, no final snapshot is written and the file goes stale. It does not report an orderly
shutdown that did not happen.

To remove the exported data, delete the directory:

```text
%USERPROFILE%\.runelite\facette
```

Deleting it removes the exported file and nothing else. The plugin keeps no history, database, log
of your play, or copy of the data anywhere else. If the plugin is still enabled, it recreates the
directory and the file on its next publication.

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

Then enable **Facette Companion** in the client's plugin list and confirm that `state-v2.json`
appears at the path above.

If your account requires Jagex Account authorization for the development client, that is yours to
handle privately in your own environment. **Never paste an account credential, token, or session
file into an issue, a pull request, a log, or this repository.** The plugin and this contributor
workflow do not require account credentials to be placed in any of them.

See [CONTRIBUTING.md](CONTRIBUTING.md) for the boundaries a change has to stay inside.

## Reporting a problem

Open an issue. Before attaching a snapshot, read it and decide whether you are comfortable sharing
the gameplay state it contains. Schema 2 does not include account identity or credentials, but it
does include the fields documented in [SCHEMA.md](SCHEMA.md).

## License and affiliation

Source code in this repository is licensed under the BSD 2-Clause License. See [LICENSE](LICENSE).

"Facette" and associated branding are not granted under this license.

Old School RuneScape is a trademark of Jagex Ltd. RuneLite is an independent open-source project.
Facette Companion is an independent third-party project and is **not affiliated with or endorsed
by Jagex Ltd. or the RuneLite project**.
