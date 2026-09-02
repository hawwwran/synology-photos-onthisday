# 007 - Stack, layout, and application id

- **Status:** Accepted
- **Date:** 2026-09-02
- **Origin:** Chosen

## Context

`~/git/strumbook` is a working Android app on this machine with a build that is known to work
here: a JDK 21 Gradle daemon (the machine default Temurin 25 is unsupported by this AGP line),
a release keystore co-signing debug builds, and a tested unit-test path. Rediscovering that was
not worth doing.

## Decision

The strumbook skeleton, with this app's dependencies:

| Concern | Choice |
| --- | --- |
| Language, UI | Kotlin 2.2, Jetpack Compose, Material 3 |
| HTTP | OkHttp 5, kotlinx.serialization for bodies |
| Images | Coil 3 with the OkHttp network layer |
| Storage | Room for the index, DataStore for the session |
| Build | AGP 8.13, `minSdk 26`, `targetSdk 35`, `compileSdk 36`, Gradle daemon on JDK 21 |

- Application id and namespace: **`com.hawwwran.photosonthisday`**, per `~/CLAUDE.md`. Never a
  `simpleway` namespace: that is the employer's, and this is personal work.
- Displayed name: **On This Day**. Gradle project name `OnThisDay`.
- Single `:app` module. The pure logic sits in `core/`, which needs no Android dependency and is
  where the tests are, so a module split can happen later if it earns itself.
- Documents mirror the companion repo: `documents/decisions/`, `documents/plans/`,
  `documents/research/`, same protocol, same numbering rules.

### The icon

An adaptive icon built from one vector petal rotated four times, on a night background. Three
petals warm, one white: a single day picked out of the years around it. It shares the
pinwheel-of-petals language of photo apps without reproducing Synology's own mark, which is
theirs. The app's Compose palette is taken from the same four colours, so the icon and the app
read as one thing.

## Consequences

- The build worked on the first attempt, including the unit-test path, because the awkward
  parts were already solved next door.
- The JDK 21 pin in `gradle.properties` is a machine fact, not a preference. It breaks on a
  machine without that JDK, and the error names the path, which is enough.
- `minSdk 26` means the adaptive icon needs no legacy PNG fallbacks. One vector, three files.
- No dependency injection framework. The graph is a client, a database and a session store,
  constructed in `OnThisDayApp`.

## Related

[[001-web-api-is-the-only-source]]
