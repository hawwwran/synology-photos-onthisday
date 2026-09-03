# 011 - Documentation, the logging rule and dead code

- **Status:** Not started. Written from the whole-project code review of 2026-09-03.
- **Source:** code review 2026-09-03: the quality findings (stale comments, plan headers, logging
  rule, duplication, dead code).
- **Depends on:** 007, 008, 009, 010. Run last: it records the state the code is actually in.
- **Blocks:** the next release.
- **Decisions:** none new. Touches the index and Q3 wording.
- **Progress:** 0 / 14

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

- [ ] Every row of table A corrected; the research doc's allowlist table lists all `Allowlist.kt`
      triples (read and write) and the sentence after it is true; the U3 "not established" paragraph
      is marked superseded; Q3 in `decisions/index.md` reworded.
- [ ] `git grep -n "not verified\|pre-release\|Version 2\|carries \`_sid\`"` returns nothing stale.

### 2. Plan headers

- [ ] Plan 005 header, boxes and `> Blocked` notes reflect the shipped release; `Progress` equals the
      box count.
- [ ] Plan 006 `Progress` filled; section-1 boxes resolved; acceptance items re-checked against
      plan 009's device test.
- [ ] `index.md` rows for 005-011 current; dependency graph extended; `HANDOFF.md` corrected.

### 3. Logging guard

- [ ] `LoggingRuleTest` (JVM): scans `app/src/main/java`, allows `Log.` only in the named logger
      files, and asserts their format strings; the 005 tick's claim is now true. Update the tick text
      in plan 005 to name the test.
- [ ] `ApiLog.failure(ApiFailure)` replaces the three per-kind loggers; throw sites use `.also`.

### 4. Duplication

- [ ] One byte formatter; the three privates deleted.
- [ ] Palette object in `ui/theme`; banner uses it; rose reconciled per the owner's choice;
      `colors.xml` comment added.
- [ ] `thumbnailRequest` helper (if 007 did not); one `Json`; one `decodeOrMalformed`.
- [ ] `DayViewModel` imports used, `retry()` removed, `display` eager, `viewerSnapshot` reads it,
      `ViewerItem` removed if nothing else needs it.
- [ ] `UpdateChecker` cache via kotlinx-serialization (optional; skip with a note if time is short).

### 5. Dead code

- [ ] Navigation dependency, `ACCESS_NETWORK_STATE`, `lastCheckAt`, `observe()`/`DayIndexState`
      removed with tests ported; `releaseUrl` shown or removed.
- [ ] `./gradlew testDebugUnitTest` and `assembleDebug` green after each removal commit.

## Acceptance criteria

- [ ] A fresh session following `CLAUDE.md`'s reading list finds no statement contradicted by the
      code (spot-check: `DayRange`, `AppDatabase`, `Thumbnail`, research allowlist, plans 005/006).
- [ ] `LoggingRuleTest` passes and fails when a `Log.i` with a string parameter is added to a
      repository (try it, then revert).
- [ ] No unused dependency or permission in the debug APK's manifest (`aapt dump permissions`).

## On completion

1. Tick as verified, keep `Progress:` in step in the same commit.
2. Update `index.md`.
3. Cut a release with the release script (main pushed first); the release notes name the fixed
   findings by plan number.
