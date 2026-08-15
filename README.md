# Facette Companion

A read-only RuneLite plugin that provides your own live game state to the separately installed
[Facette](https://github.com/ethanmonlux/facette) companion application through one local file.

Facette is a Windows-first second-screen companion for games. This plugin is the Old School
RuneScape side of it: while enabled, it keeps one small JSON file up to date with a bounded view
of your own character, so a companion app on the same machine can show it on a second screen.

## Publication status

This plugin is **not currently distributed through the RuneLite Plugin Hub**. Nothing here claims
Plugin Hub approval, and no submission has been reviewed. There is no prebuilt JAR in this
repository and no release to download. The source is published so that the plugin can be reviewed,
and so that anyone whose game state it reads can read the code doing the reading.

## Facette is a separate application

The Facette desktop application is one you install separately, yourself, from elsewhere. This
plugin writes one local file, and nothing else. Facette may read that file whenever it likes.

This plugin **does not launch, download, install, bundle, execute, or communicate with Facette**,
and it behaves exactly the same whether Facette is installed, running, or absent entirely.

## What it does and does not do

**Read-only.** The plugin reads game state through the RuneLite API and writes one local file. It
performs no clicks, no keystrokes, no menu actions, no automation, and no window manipulation, and
it reads no command channel, so nothing outside the game can act on the game through it. There is
no reverse path.

**No network communication.** The plugin opens no socket and makes no HTTP, WebSocket, or other
remote request. Your game state does not leave your computer because of this plugin.

**No account credentials.** The plugin never reads, stores, or exports your account name, account
hash, email, password, session token, or any other credential.

### Exported

Your own character only: session and world number, combat level, hitpoints, prayer points, run
energy, special attack energy, weight, selected attack style, active prayers, the NPC you are
interacting with, your eleven equipment slots, your twenty-eight inventory slots (item id,
quantity, name), and experience *gained during the current tracked session*.

### Not exported

Account name or hash, credentials, chat, friends, clan data, other players, any player target,
bank contents, Grand Exchange data, item prices, aggregate wealth, quest or Slayer state, total or
historical account experience, and location.

The exported field list is a closed contract, not a starting point. Every field, type, bound, and
lifecycle rule is documented in **[SCHEMA.md](SCHEMA.md)**, with two committed byte-exact examples:
[a populated snapshot](src/test/resources/facette-osrs-state-v2.json) and
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
than closed, no final snapshot is written and the file simply goes stale — it never pretends an
orderly shutdown happened.

To remove the exported data, delete the directory:

```text
%USERPROFILE%\.runelite\facette
```

Deleting it removes the exported file and nothing else — the plugin keeps no history, database, log
of your play, or copy of the data anywhere else. If the plugin is still enabled, it recreates the
directory and the file on its next publication.

## Contributor workflow

This section is for people working on the plugin. It is a development workflow, **not** an
installation path — if you want to use the plugin, you do not need any of it.

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
file into an issue, a pull request, a log, or this repository**, and note that nothing in this
project will ever ask you for one.

See [CONTRIBUTING.md](CONTRIBUTING.md) for the boundaries a change has to stay inside.

## Reporting a problem

Open an issue. When attaching a snapshot, the whole file is safe to share by design — it contains
no account identity or credential — but read it first and satisfy yourself of that rather than
taking this README's word for it.

## License and affiliation

Source code in this repository is licensed under the BSD 2-Clause License. See [LICENSE](LICENSE).

"Facette" and associated branding are not granted under this license.

Old School RuneScape is a trademark of Jagex Ltd. RuneLite is an independent open-source project.
This repository is **not affiliated with or endorsed by Jagex or RuneLite**, and nothing here is
approved or endorsed by Jagex, RuneLite, or the RuneLite Plugin Hub.
