# On This Day

Android app showing the photos a person took on today's date in every year their Synology
Photos library covers. A daily cut through the years. When today holds nothing, it shows the
nearest day that does.

Sibling project, different problem: `~/git/synology-photos-companion` archives albums from the
NAS and reads the Photos PostgreSQL database directly. **No code moves between the repos.** Its
research documents are worth reading; its access model cannot work from a phone. See
[decision 001](documents/decisions/001-web-api-is-the-only-source.md).

## Read these before you write code

1. `documents/decisions/index.md` - every decision in force. Start here every session.
2. `documents/plans/index.md` - what is built, what is next, and the dependency graph.
3. The plan file you are working on, in full.
4. `documents/plans/plan.md` - the product specification and the authority on requirements.
5. `documents/research/photos-web-api.md` - the endpoint specification, from a real run against
   Photos 1.9.1. Nothing about the API may be assumed beyond what it records.

## Safety rules that override convenience

Full list in `plan.md` §2. The ones easiest to break by accident:

- **Synology Photos is read-only.** No write, rename, delete, upload, share or settings call,
  and no call whose effect is unknown.
- **Only allowlisted `(api, method, version)` triples may be called.** A triple that is absent
  throws before the request is built. Not a blocklist: some Photos read methods use POST, so
  the verb cannot classify safety.
- **Never log a response body.** Album and sharing responses carry share passphrases and live
  `sharing_link` values, which let anyone view an album from the internet without signing in.
  Log the call name and the error code, nothing else.
- **Never store the password.** It exists in memory for one login call. Not on disk, not in a
  URL, not in a process argument. The session id and the two-factor device id are stored; the
  password is not.
- **HTTPS with a publicly trusted certificate only.** No cleartext exception, no custom
  `TrustManager`, no trust-all client behind a build flag.
- **An account change wipes everything first.** Day index, item rows, thumbnail cache, session.
  One household member must never see another's photos out of a stale cache.
- **A failed login is never retried automatically.** DSM auto-block bans the address and would
  lock the household out of its own NAS.

## Build and test

```bash
./gradlew testDebugUnitTest    # JVM unit tests; core/ logic is pure and lives here
./gradlew assembleDebug        # debug APK in app/build/outputs/apk/debug/
./gradlew assembleRelease      # needs the keystore below
```

`connectedDebugAndroidTest` **uninstalls the app when it finishes**, and the uninstall takes the
app's data with it: session, trusted-device id, index, caches (likes are safe on the NAS). Run it
with `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`, or expect to sign in again.

The Gradle daemon runs on JDK 21 via `org.gradle.java.home` in `gradle.properties`. The
machine default is Temurin 25, which this AGP line does not support. `local.properties` points
at `~/Android/Sdk` and is gitignored.

## Release signing

The keystore exists: `keystore.jks` at the repo root (gitignored), alias `onthisday`, generated
2026-09-03. Its passwords are `OTD_KEYSTORE_PASSWORD` / `OTD_KEY_ALIAS` / `OTD_KEY_PASSWORD` in
`~/.gradle/gradle.properties`. The keystore **and** those lines **must be backed up**: losing
either means installed copies can never be upgraded in place again. Debug builds co-sign with the
release key when the keystore is present, so `installDebug` upgrades a release install without
uninstalling.

## Releasing

`~/SynologyDrive/Development/linux/hwntools-custom-packages/releases/release-photos-onthisday.sh`
(a hwntools launcher package) cuts a release: it clones `origin/main` fresh, so **main must be
pushed first**, bumps `versionName`/`versionCode` in `app/build.gradle.kts`, builds a signed
`assembleRelease`, verifies it with `apksigner`, tags `vX.Y.Z`, and uploads the APK to a GitHub
release (release notes drafted by `claude` from the commit log). The build is local; nothing is
built on GitHub. The APK is attached as `OnThisDay-<ver>.apk`.

## Device workflow

Test device: Vivo V2145, Android 15, same as strumbook. minSdk is 29 (decision 004, amended
2026-09-03): no storage permission, cleartext blocked by the platform. `adb install -r`. Screenshots with
`adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png` - the device has multiple
displays and `exec-out screencap` pollutes stdout with a warning.

## Talking to the NAS during development

`scripts/observe-photos-api.sh` is the only thing that touches the real NAS from this machine.
It asks for the base URL, the account, the password and a two-factor code, calls read endpoints
only, redacts every response before writing it, and logs out on exit. Its output directory is
gitignored and stays that way: see `documents/research/README.md` for why.

## Application id

`com.hawwwran.photosonthisday`. Namespace, application id, `.desktop`-equivalent identifiers,
everything. **Never `cz.simpleway.*` or `com.simpleway.*`**: simpleway is the employer, and
this is personal work.

## Icon

`app/src/main/res/drawable/ic_launcher_foreground.xml` is one petal path rotated four times
around (54, 54). Up is white for the day being shown, left and right are warm (rose, amber), and
the down petal is turquoise, the one cool note.

The petal tapers to a point at the centre and rounds off outward. Pointed the other way it
reads as a spike rather than a flower, which is what the first attempt looked like. Body radius
14 centred 20dp out puts the outer edge at 34dp; narrowing the body would open a dark cross in
the middle because adjacent petals stop touching, so the whole flower is instead shrunk by a
single outer `scaleX/scaleY 0.85` group about (54, 54). That keeps the petals touching each
other but pulls the outer edge in to ~29dp, off the mask edge, so nothing is clipped on round
launchers. `ic_launcher_monochrome.xml` carries the same scale.

The adaptive background is a solid `#FF665C` (`drawable/ic_launcher_background.xml`, a filled
rect, not the `@color` of the same name which the mipmap does not reference). `ui/theme/Theme.kt`
uses the same four petal colours.

## Documents protocol

Same as the companion repo, deliberately.

- **Plans** are numbered per `plan.md` §12. Tick a box only when the work is done and verified.
  Never tick because code exists. Keep the `Progress:` header in step in the same commit.
  Append `> Blocked: <reason>` under anything unfinished rather than ticking it with a caveat.
- **Decisions** are numbered ADRs. Amend in place for a refinement, with a dated line under
  `## Amendments`. Supersede with a new record when the decision is reversed, and never edit a
  superseded record's reasoning to look right in hindsight.
- Cross-references between records use `[[0NN-slug]]`; links from plans use relative Markdown.

## Conventions

- **Commits:** Conventional Commits. Subject says what changed, body says why. No
  AI-attribution trailers.
- **Prose:** no emojis, no marketing adjectives, no filler under headings. State the fact and
  stop.
- **Comments:** for non-obvious *why*, tricky mechanics, and references that save a search. Not
  for restating the next line.
- **Errors:** the user sees what DSM actually returned, mapped to plain language; the log gets
  the call name and the error code.
