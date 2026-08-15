# Contributing

Thanks for looking at Facette Companion. This document covers how to build it and the boundaries
a change has to stay inside.

## Building and testing

Requires **JDK 11** (Eclipse Temurin recommended). The Gradle wrapper is included, so no separate
Gradle install is needed.

```sh
./gradlew clean test    # run the test suite
./gradlew clean build   # compile and test
./gradlew run           # launch a RuneLite development client with the plugin loaded
```

The tests need no account, credential, network service, Facette installation, or game session.

The project is a standard RuneLite plugin at the repository root, declared as `build=standard` in
`runelite-plugin.properties`. Under a standard build the Plugin Hub replaces `build.gradle` and
`settings.gradle` with its own, which compile production sources only against RuneLite, Lombok,
and JetBrains annotations. **A change must build under that replacement**, so production code
cannot depend on anything the local build file adds.

If your account requires Jagex Account authorization for the development client, that is yours to
handle privately in your own environment.

## What this plugin is allowed to do

It reads approved client state through the RuneLite API and writes one local file. That is the
whole of it, and the boundaries below are not negotiable within a contribution.

**Read-only.** No gameplay action of any kind: no clicks, keystrokes, menu actions, automation,
synthesized mouse, keyboard, touch, or controller input, and no moving or focusing a window.
There is no reverse path — the plugin reads no command channel, so nothing outside the game can
act on the game through it.

**No network.** No socket, HTTP, WebSocket, or UDP client or server, and no remote transmission of
game data. The plugin writes a local file; a separately installed reader picks it up on its own.

**No prohibited data.** No account name or hash, credentials, tokens, chat, friends, clan data,
nearby or interacted-with players, bank contents, Grand Exchange data, item prices, aggregate
wealth, or location history. The exported field list is a closed contract, not a starting point.

**No dynamic code.** No reflection, no JNI or native loading, no `Runtime.exec` or `ProcessBuilder`,
no runtime downloading, and no vendoring of code at runtime. Everything the plugin executes must
be readable in this repository.

**No hidden dependencies.** No private or unpublished build dependency, and no code fetched from
somewhere a reviewer cannot see.

A change that needs any of these is not a change to this plugin.

## Dependencies

Adding, removing, or upgrading a dependency requires explicit maintainer review before the work is
done. Please open an issue first rather than sending a pull request that adds one.

Third-party dependencies outside RuneLite's own transitives also make a Plugin Hub submission
materially slower to review, so the bar is high.

## Changing the exported schema

The exported document is a versioned contract that a separate application reads, so a schema
change is never a local edit. It requires:

1. a version bump — new fields, removed fields, renamed keys, changed types, changed nullability,
   and changed ordering are all schema changes;
2. [SCHEMA.md](SCHEMA.md) updated in the same change;
3. both committed fixtures under `src/test/resources/` regenerated, since tests hold the
   serializer and the fixtures to the same bytes;
4. coordination with the consuming application, which reads those fixtures as its contract.

Adding a field also means asking whether it belongs in a closed export at all. Anything derived
from another player, from account identity, or from wealth does not.

## Testing expectations

New behaviour needs a test. Lifecycle, privacy, and concurrency behaviour especially: the existing
suite pins startup on a non-client thread, disable and re-enable, world hops, logout nulling,
shutdown bounds, stale-write refusal, and the closed schema, and those tests should not get weaker.

Avoid `Thread.sleep` — the suite is deterministic and should stay that way.

## What CI does and does not prove

CI runs a Linux compile-and-unit-test job. **It is not evidence that the plugin loads in a real
RuneLite client, or that it behaves correctly on Windows**, and passing tests are not evidence
that the exported file is produced or consumed correctly in a live session.

Manual validation against a real client — the plugin loading, the file appearing and updating,
logout and disable leaving no stale gameplay data presented as live — is required before any
publication, and no amount of green CI substitutes for it.

## Reporting a security issue

If you believe you have found a security or privacy problem, open an issue describing what you
observed and how to reproduce it, or contact the maintainer privately if you would rather not
disclose it publicly first.

**Do not include an account credential, password, authentication token, session file, or any
private account material in a report** — none of it is ever needed to reproduce a problem here,
and nothing in this project will ever ask you for one. An exported snapshot file is safe to attach
by design, but read it first and satisfy yourself of that.
