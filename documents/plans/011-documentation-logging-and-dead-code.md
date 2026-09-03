# 011 - Documentation, the logging rule and dead code

- **Status:** Done 2026-09-03, except the release cut in "On completion", which needs `main` pushed
  (owner action).
- **Source:** code review 2026-09-03: the quality findings (stale comments, plan headers, logging
  rule, duplication, dead code).
- **Depends on:** 007, 008, 009, 010. Run last: it records the state the code is actually in.
- **Blocks:** the next release.
- **Decisions:** none new. Touches the index and Q3 wording.
- **Progress:** 14 / 14

## Goal

Every comment, plan header and research statement a future session reads as instructed by
`CLAUDE.md` is true. Nothing in production declares a contract no code honours.

## Findings this plan fixes

Line numbers are as of commit `c70ddb0`; several will have moved by the time plans 007-010 land.

### A. Comments that contradict the code or the research

| Where | Says | Truth |
| --- | --- | --- |
| `core/DayRange.kt:13-14` | `end_time` inclusivity "was not verified" | `documents/research/photos-web-api.md:65` records it verified inclusive on the second run |
| `documents/research/photos-web-api.md:209-211` | "Not established: whether the ends are inclusive" | contradicts line 65 of the same file; mark the paragraph as superseded by the update above it |
| `documents/decisions/index.md`, Q3 | "the owner accepted the unverified end-inclusivity" | verified since; reword |
| `data/db/AppDatabase.kt:7` | "Version 2" | version is 4 (5 after plan 008); plan 008 fixes this, verify it did |
| `api/Thumbnail.kt:19-22` | "the URL carries `_sid`" | thumbnails use the cookie; plan 007 fixes this, verify it did |
| `OnThisDayApp.kt:92` | destructive migration is "pre-release" | v1.0.0 shipped; plan 008 removes it, verify |
| `documents/research/photos-web-api.md:74-92` | the allowlist table, and "No album, sharing, folder, upload, setting or download method was called" | `Allowlist.kt` also holds `Browse.Folder get 2` (both namespaces), `SYNO.FileStation.Download download 2`, `SYNO.FileStation.Upload upload 2`; the download rows are in the table but the sentence still says download was not called |
| `data/IndexLog.kt:7` | "The data layer's only log line" | `OriginalFetch.kt:39` logs directly; plan 008 changes the line, plan 010 the other |

### B. Plan headers out of step

- Plan 005: `Progress: 10 / 11` and `Status` say the release build waits on a keystore; the Tasks
  section has 13 ticked and 2 open boxes, the keystore exists (`CLAUDE.md`, generated 2026-09-03),
  and v1.0.0 was cut (commit `f499168`). Tick the release-build box with the verification (the
  `apksigner` check the release script runs), count the boxes, fix the header and the `> Blocked`
  notes at lines 57 and 96.
- Plan 006: `Progress` says acceptance awaits the live test; `Status` says verified on device. Count
  the boxes, fill the number, and mark the section-1 observation boxes (lines 139-150) as dropped
  with a one-line reason (the File Station route needed no Photos-endpoint observation) rather than
  leaving them open. Plan 009 may have verified the two open acceptance items.
- `documents/plans/index.md`: rows for 005 and 006 are stale; add 007-011.
- `HANDOFF.md` (repo root) repeats the keystore claim; correct or delete the paragraph.

### C. The logging rule has no test and several breaches

`CLAUDE.md`: "Log the call name and the error code, nothing else." Plan 005 ticked "a test asserting
no logging call receives a response body" on the strength of "`ApiLog` is the only logger", which was
already false at the time (`IndexLog` existed). Today `android.util.Log` is called directly in
`likes/LikeRepository.kt`, `data/OriginalFetch.kt`, `data/IndexLog.kt`, `update/Installer.kt` and
`update/UpdateDownloader.kt`. Plans 008-010 fix the individual lines; this plan adds the guard that
keeps it fixed: a JVM test that scans `app/src/main/java` for `Log.` calls and fails on any file
outside an allowlist (`api/ApiLog.kt`, `data/IndexLog.kt`, one update logger), and asserts each
allowed logger's format strings take no free-text parameter. Cheap, and it is what the ticked box
claimed.

`ApiLog.dsmError/transport/malformed` also duplicate the message formats in `ApiFailure`
(`api/ApiLog.kt:18-26` versus `api/ApiFailure.kt:15-27`), and every throw site pairs the two calls.
One `ApiLog.failure(f: ApiFailure)` that logs `f.message`, with throw sites written
`throw ApiFailure.X(...).also(ApiLog::failure)`, removes the second copy.

### D. Duplication

- Three byte formatters: `ui/day/SettingsScreen.kt:162` (decimal, "kB"), `update/UpdateModal.kt:134`
  (binary, "KB"), `ui/day/ViewerScreen.kt:408` (binary, to TB). One function in `ui/`, one rounding
  rule.
- `update/UpdateBanner.kt:30-31` hardcodes `0xFFFFC24A` and `0xFF1A1628`, which are `Theme.kt`'s
  private `Amber` and `Night`. `res/values/colors.xml` carries the petal palette a third time and has
  drifted: `petal_rose` is `#FFC23B63`, `Theme.kt` `Rose` is `0xFFE0537A`, while `CLAUDE.md` says the
  theme uses the icon's four colours. Owner picks which rose is right (the icon is what the launcher
  shows; default: theme follows `colors.xml`). Expose the palette as an `internal object` in
  `ui/theme` and reference it from the banner; leave one comment in `colors.xml` saying `Theme.kt`
  must match.
- `ui/day/Thumbnail.kt:48-56` and `ui/day/ViewerScreen.kt:315-320` build the same Coil request;
  a `thumbnailRequest(context, ref, auth)` next to `ThumbnailAuth` owns the cache-key rule once. Plan
  007 may already have done this while adding the content-type guard; if so, tick.
- Five `Json { ignoreUnknownKeys = true }` instances and two identical `decode()` try/catch pairs
  (`ItemApi.kt:52-58`, `TimelineApi.kt:28-34`). One `Json` on `AppGraph`, one `decodeOrMalformed`.
- `DayViewModel`: `retry()` is an alias of `refresh()`; `PhotoItem`, `RefreshResult`, `Flow` and
  `currentMonthDay` are fully qualified at several sites although imported (lines 53, 92, 93, 148,
  176, 205, 224, 233, 237, 257, 315); `viewerSnapshot()` recomputes what `display` holds because
  `display` is `WhileSubscribed` (make it `Eagerly`, its inputs are hot `StateFlow`s, and the one-field
  `ViewerItem` wrapper can go).
- `UpdateChecker.readCache/writeCache` hand-roll `org.json` per field while the rest of the app uses
  kotlinx-serialization; `@Serializable` on `CachedRelease`/`ParsedRelease` collapses 40 lines.
  Optional.

### E. Dead

- `androidx.navigation:navigation-compose` (`build.gradle.kts:99`, `libs.versions.toml:9,27`): no
  `NavHost` or `rememberNavController` anywhere; `DayHost` says it deliberately avoids one.
- `ACCESS_NETWORK_STATE` (`AndroidManifest.xml:7`): no `ConnectivityManager` caller.
- `UpdatePrefs.lastCheckAt` (written `UpdateViewModel.kt:143`, never read; the 24 h limit lives in
  the checker's cache).
- `UpdateInfo.releaseUrl` / `ParsedRelease.htmlUrl`: carried through parse, cache and info, never
  displayed. Either show a "release notes" link in the modal or drop the field.
- `DayIndexRepository.observe()` and `DayIndexState` (`data/DayIndexRepository.kt:20-30,65-73`):
  referenced only by `DayIndexRepositoryTest`; `DayViewModel.computeView` re-derives the same split.
  Delete and port the four test assertions to `observeDays()` plus `selectDay()`.
- `LikeEntity.pendingSync`: removed by plans 008/009; verify no mention remains, including the KDoc
  at `Entities.kt:54`.

## Tasks

### 1. Comments and research

- [x] Every row of table A corrected: `DayRange` says inclusive and cites the run; the research
      allowlist table carries both `Browse.Folder` rows, `FileStation.Download`, and a second table
      for the one write, with the sentence after it true again; the U3 paragraph carries a
      superseded note pointing at the U1 update; Q3 reworded. `AppDatabase`, `Thumbnail`,
      `OnThisDayApp` and `IndexLog` were fixed by plans 007 and 008 and re-checked.
- [x] The grep returns two hits, both true: decision 005's 2026-09-02 amendment says inclusivity
      "was not verified", which was so on that date and is answered by the dated 2026-09-03
      amendment under it (records are not rewritten in hindsight); the research says the download
      "still carries `_sid` in the query", which it does.

### 2. Plan headers

- [x] Plan 005: Status "Done", `13 / 13`, the release box ticked with the keystore date and the
      `apksigner` verification, the install-over-debug acceptance left open with an honest note.
- [x] Plan 006: `15 / 21`, the six section-1 boxes marked dropped with the reason, the two open
      acceptance items noted as blocked on a device (plan 009's device test did not run either).
- [x] `index.md` rows 005-011 current with the same-day execution; graph unchanged (it already held
      007-011); `HANDOFF.md`: keystore paragraph replaced, test count and plan/decision rows updated.

### 3. Logging guard

- [x] `LoggingRuleTest`: `android.util.Log` only in `api/ApiLog.kt`, `data/IndexLog.kt`,
      `update/UpdateLog.kt`, and no function in those files takes a `String`, `CharSequence` or
      `Any`. Verified the guard bites: a `Log.i("PhotosLikes", "sync start, folder=$dir")` added to
      `LikeRepository` failed the test, then was reverted. Plan 005's tick text names the test.
- [x] `ApiLog` is `ok(call)` and `failure(ApiFailure)`; the fourteen `dsmError`/`malformed`/`transport`
      call sites are `throw X.also(ApiLog::failure)` or `ApiLog.failure(X)` where nothing is thrown.

### 4. Duplication

- [x] `ui/Format.kt` `formatBytes` (binary, one decimal, dash for nothing) serves Settings, the
      update modal and the info sheet; the three privates are gone (done while plan 010 rewrote the
      modal).
- [x] `ui/theme/Palette.kt`; `Theme.kt` and `UpdateBanner` read it; rose follows `colors.xml`
      (`#FFC23B63`, the plan's default, owner not reachable); `colors.xml` says the two must match.
- [x] `thumbnailRequest(context, ref, auth)` in `Thumbnail.kt`, used by the grid cell and the viewer;
      `AppJson` in `api/Envelope.kt` is the one `Json`, the default of every client and store;
      `Json.decodeOrMalformed` replaces the two identical try/catch pairs.
- [x] `DayViewModel` uses its imports, `retry()` is gone (the Problem screen calls `refresh`),
      `display` is `Eagerly`, `viewerSnapshot()` is `display.value.flatMap { it.items }`;
      `ViewerItem` deleted, the viewer takes `List<PhotoItem>`.
- [x] Done in plan 010: `UpdateChecker` reads GitHub and its cache with kotlinx-serialization
      (`@Serializable CachedRelease`/`ParsedRelease`), which also made `UpdateCheckerTest` possible.

### 5. Dead code

- [x] `navigation-compose` gone from `build.gradle.kts` and the version catalog; the app's
      `ACCESS_NETWORK_STATE` declaration gone (media3-common still merges its own, see acceptance);
      `UpdatePrefs.lastCheckAt` gone (plan 010); `observe()`/`DayIndexState` gone, the four tests
      ported to `observeDays()` plus `selectDay`; `releaseUrl`/`htmlUrl` dropped (plan 010), the
      modal already shows the release notes body.
- [x] `./gradlew testDebugUnitTest assembleDebug` green: 117 tests, 24 MB debug APK.

## Acceptance criteria

- [x] Spot-checked `DayRange`, `AppDatabase`, `Thumbnail`, the research allowlist and plans 005/006
      against the code on 2026-09-03.
- [x] Passes; failed with the stray `Log.i` in `LikeRepository`; reverted.
- [x] `aapt dump permissions`: `INTERNET`, `REQUEST_INSTALL_PACKAGES`, `WAKE_LOCK`, and
      `ACCESS_NETWORK_STATE`, which the app no longer declares: `media3-common` 1.4.1 merges it in
      (manifest merger report) for ExoPlayer's own network-type checks, so it stays. No dependency in
      `build.gradle.kts` is unreferenced.

## On completion

1. Tick as verified, keep `Progress:` in step in the same commit.
2. Update `index.md`.
3. Cut a release with the release script (main pushed first); the release notes name the fixed
   findings by plan number.
