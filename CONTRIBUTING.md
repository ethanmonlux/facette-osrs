# Contributing

Thanks for looking at Facette Companion. [README.md](README.md) describes what the plugin does and
[SCHEMA.md](SCHEMA.md) is the exported contract.

## Building and testing

Requires **JDK 11** (Eclipse Temurin recommended). The Gradle wrapper is included.

```sh
./gradlew clean test    # run the test suite
./gradlew clean build   # compile and test
./gradlew run           # launch a RuneLite development client with the plugin loaded
```

The tests need no account, credential, network service, Facette installation, or game session.

## Plugin Hub build

The project is a standard RuneLite plugin at the repository root, declared as `build=standard` in
`runelite-plugin.properties`. The Plugin Hub replaces `build.gradle` and `settings.gradle` with its
own, compiling production sources against RuneLite, Lombok, and JetBrains annotations only. Your
change must build under that replacement, so production code cannot use anything the local build
file adds.

## Architecture

The plugin reads RuneLite client state on the client thread and writes one local JSON snapshot
file. A separately installed reader picks the file up on its own. Nothing flows back: the plugin
performs no gameplay input, accepts no commands, and makes no network requests.

Keep contributions inside that shape.

## Changing the exported schema

Schema 2 contains only the fields documented in [SCHEMA.md](SCHEMA.md). A future schema may add
fields after separate review, but contributors must not add them opportunistically.

Open an issue before writing the code. A schema change needs:

1. a version bump, since new fields, removed fields, renamed keys, changed types, changed
   nullability, and changed ordering are all schema changes;
2. [SCHEMA.md](SCHEMA.md) updated in the same change;
3. both fixtures under `src/test/resources/` regenerated, since tests hold the serializer and the
   fixtures to the same bytes;
4. coordination with the consuming application, which reads those fixtures as its contract.

## Dependencies

Adding, removing, or upgrading a dependency needs maintainer review first, so open an issue rather
than sending a pull request that adds one. Dependencies outside RuneLite's own transitives also
slow a Plugin Hub review, so the bar is high.

## Testing expectations

A meaningful behavior change needs a test. The existing suite pins lifecycle, privacy, and
concurrency behavior, including startup on a non-client thread, disable and re-enable, world hops,
logout nulling, shutdown bounds, stale-write refusal, and the closed schema. Those tests should not
get weaker. Avoid `Thread.sleep`; the suite is deterministic.

## CI and live validation

CI runs a Linux compile-and-unit-test job. It does not show that the plugin loads in a real
RuneLite client, that it behaves correctly on Windows, or that the exported file is produced and
consumed correctly in a live session. Manual validation against a real client covers that, and is
required before any publication.

## Reporting a security issue

Open an issue describing what you observed and how to reproduce it, or contact the maintainer
privately first if you prefer.

Never include an account credential, password, authentication token, session file, or other private
account material in a report, an issue, a pull request, or a log. Nothing here needs one.

Before attaching a snapshot, read it and decide whether you are comfortable sharing the gameplay
state it contains. Schema 2 does not include account identity or credentials, but it does include
the gameplay fields documented in [SCHEMA.md](SCHEMA.md).
