# 010 - Viewer, save, share and the update flow

- **Status:** Code done 2026-09-03 with minSdk 29 (the default); the device checks wait for the
  Vivo (no device attached in the executing session).
- **Source:** code review 2026-09-03: findings 7, 11, 13, 14, 15 and the `DayHost` duplication.
- **Depends on:** 005 (the code it fixes). Independent of 007-009; `DayHost` is edited by 007 too,
  so run 007 first to avoid a merge.
- **Blocks:** 011.
- **Decisions:** [004](../decisions/004-access-path-and-tls.md) (amend if minSdk changes: the
  "cleartext blocked since API 28" statement then covers every supported level).
- **Progress:** 10 / 14

## Goal

Save, share and video behave the same on every supported Android version, and the update flow
never tells the user something false.

## Owner decision needed first

**minSdk.** The app declares `minSdk = 26` and `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion=28`,
but never requests the permission at runtime, so Save crashes on Android 8 and 9 (finding A). Two
ways out:

- **Raise minSdk to 29.** Removes the permission, the `Build.VERSION` branch in `ImageSaver`, and
  the cleartext gap on API 26-27 (decision 004 says cleartext is blocked by the platform from 28).
  Household phones are recent; the test device is Android 15. **Default in this plan.**
- **Keep 26 and request the permission** at runtime before the first save on API 28 and below, with
  a rationale string and a denied path.

The executing session takes the default unless the owner says otherwise; note the choice in this
plan.

**Taken: minSdk 29** (executing session, 2026-09-03; the owner was not reachable and the plan named
the default). One consequence found while building: from minSdk 28 AGP packages dex uncompressed,
which took the debug APK from 24 MB to 74 MB with the same dex inside. `packaging.dex.useLegacyPackaging
= true` in `app/build.gradle.kts` keeps dex compressed, so the download over GitHub and the in-app
updater stays the size it was.

## Findings this plan fixes

Line numbers are as of commit `c70ddb0`. Re-check them before editing.

### A. Save crashes on API 26-28, and local write failures blame the NAS

`ui/day/ImageSaver.kt:50` calls `resolver.insert` on a MediaStore collection. Below API 29 that
needs `WRITE_EXTERNAL_STORAGE`, a runtime permission on API 23+; nothing requests it (`grep
checkSelfPermission` finds nothing), so it throws `SecurityException`, a `RuntimeException`.
`data/OriginalFetch.kt:46` catches `IOException` only, `DayHost.kt:96-107` and `:175-188` launch
with no handler, so the process dies. The KDoc at `ImageSaver.kt:21` claims the manifest covers it,
which is false for a dangerous permission. The file also declares package `...data` while living in
`ui/day/`.

Separately, `OriginalFetch.kt:43` runs `onBody` inside the network try. `ImageSaver` throws
`IOException("MediaStore refused the insert")` and disk-full copies throw `IOException`, all reported
as "NAS není dostupný." although the NAS answered. On API 29+ the MediaStore row is inserted without
`IS_PENDING` and never deleted on failure, so a failed copy leaves a broken, gallery-visible file;
repeated saves of the same item create `OnThisDay-N (1).jpg` duplicates.

Fix: minSdk decision above; `onBody` runs after the network block with its own failure mapping
("uložení selhalo" versus "NAS není dostupný"); `IS_PENDING=1` during the write, `0` on success,
`resolver.delete(uri)` on failure; skip the insert when a row with the same `DISPLAY_NAME` and
`RELATIVE_PATH` exists. Move `ImageSaver.kt` and `MediaSharer.kt` into `data/` or fix the package
line. Every user-facing string comes from `strings.xml`.

### B. Video plays on after Home or lock, and bypasses the app's HTTP policy

`ui/day/ViewerScreen.kt:256` ties `playWhenReady` to the pager page only; there is no lifecycle
observer in the file. Pressing Home or the power button keeps ExoPlayer decoding and the audio
audible. The data source at `:246-248` is media3's `DefaultHttpDataSource` (HttpURLConnection),
so `AppGraph.http`'s no-redirect, no-retry, timeout policy (`OnThisDayApp.kt:67-79`) does not apply
to a request that carries `_sid` in its URL, and HttpURLConnection follows same-protocol redirects.

Fix: pause on `ON_STOP` (and resume only if the page is still active) via
`LocalLifecycleOwner`; use `androidx.media3:media3-datasource-okhttp` with `OkHttpDataSource.Factory`
over `AppGraph.http`. The cookie form of the session is verified only for thumbnails (research), so
`_sid` stays in the download and video URLs; note that in the code where the URL is built.

### C. The update flow tells the user the wrong thing

- Offline or HTTP error with no cached check: `update/UpdateChecker.kt:58` and `:85` resolve to
  `null`, which `UpdateViewModel.runCheck` (`update/UpdateViewModel.kt:138`) maps to `NoUpdate`.
  A user who taps "Zkontrolovat aktualizace" in airplane mode reads "Používáte nejnovější verzi".
- `Installer.InstallStartOutcome.MISSING_PERMISSION` is handled exactly like `LAUNCHED`
  (`UpdateViewModel.kt:120-125`): the modal closes after 800 ms while the system settings page opens,
  with no explanation. On return the banner is back and Install re-downloads the whole APK, because
  `UpdateDownloader.kt:57-59` never checks whether the target file already exists.
- English literals ("Update failed", "Downloaded file missing", "Could not start installer",
  "Download incomplete", raw `e.message`) reach `Text()` in a Czech UI via `UpdateUiState.Error`.
- `UpdateChecker` and `UpdateDownloader` each build their own `OkHttpClient` (`UpdateChecker.kt:37`,
  `UpdateDownloader.kt:48`). Derive from `AppGraph.http.newBuilder()` (GitHub asset downloads
  redirect, so `followRedirects(true)` is set on the derived client, deliberately).

Fix: a distinct `CheckFailed` state (or `NoUpdate(fromCache = false, offline = true)`) with its own
string; `MISSING_PERMISSION` keeps the modal open with a one-line explanation and keeps the file,
and after the permission is granted (`ON_RESUME`) Install reuses a complete target of the expected
size; typed failure reasons mapped to `strings.xml` at render time; both clients derived from the
app client.

### D. Banner and status bar

`update/UpdateBanner.kt:34` applies `statusBarsPadding()` to the banner. `windowInsetsPadding`
consumes insets for its own subtree only, so the sibling `Box` in `ui/AppRoot.kt:64` still sees the
full status-bar inset and `DayScreen`'s `TopAppBar`, the sign-in `Scaffold` and the viewer's top row
pad for it a second time. With a banner showing, a blank strip the height of the status bar sits
between the banner and the app bar. Memory notes the on-device banner check as still open, so this
is the moment to do it.

Fix: `Modifier.consumeWindowInsets(WindowInsets.statusBars)` on the `Box` while the banner is
visible, or provide the padding once at the `Column` and stop the children padding.

### E. The liked group can be missing on cold start

`ui/day/DayViewModel.kt:303` freezes `orderKeys.value = likedKeys.value`. `likedKeys` (line 103)
is `WhileSubscribed`, first subscribed by `DayHost.kt:58` after the first frame, and its Room query
races the `dayView` chain that init (line 180) subscribes immediately. If `dayView` wins, the first
`loadSections` runs with an empty liked set and no "Oblíbené" group appears until the day is
reloaded. Plausible, not deterministic.

Fix: `loadSections` reads the liked set directly (`likes.likedKeys.first()`, a DAO read) before
freezing it, or `likedKeys` becomes `Eagerly` so it is primed with `dayView`.

### F. `DayHost` duplication

Save and share are written twice (`DayHost.kt:96-134` grid multi-select, `:153-188` viewer
single), same launch, guard, `FileProvider.getUriForFile` with a literal authority, flags and
`createChooser`. The authority literal appears a third time in `update/Installer.kt:38`. The
single-share failure toast uses `result.reason` while the multi-share uses `R.string.selection_share_failed`.

Fix: `saveItems(List<PhotoItem>)` and `shareItems(List<PhotoItem>)` helpers (`ACTION_SEND` for
one, `ACTION_SEND_MULTIPLE` otherwise), the guards inside them; one `fileProviderAuthority(context)`
function.

### G. Grid double-tap (owner call, optional)

`ui/day/DayScreen.kt:337` sets `onDoubleClick` on every tile, so Compose holds each single tap for
the double-tap timeout (about 300 ms) before opening the viewer. The viewer already has a like
button. Either accept the latency knowingly (note it in the code) or drop the grid double-tap. Not
a defect; recorded so the trade-off is a decision rather than an accident.

## Tasks

### 1. minSdk and Save

- [x] minSdk 29 in `build.gradle.kts`; `WRITE_EXTERNAL_STORAGE` gone from the manifest; the
      `Build.VERSION` branch gone from `ImageSaver`; decision 004 amended (2026-09-03); `CLAUDE.md`
      device workflow names the level.
- [x] `OriginalFetch.fetch` returns a `FetchFailure?` (`NOT_A_FILE`, `TRANSPORT`, `LOCAL`). The
      network call is caught on its own; `onBody` runs outside it over a stream wrapper that tags a
      read failure as transport, so a `MediaStore` refusal or a full disk is `LOCAL` while a broken
      connection mid-copy is still `TRANSPORT`. Logging through `ApiLog`. `OriginalFetchTest`: five
      cases, including "a write that fails on the device is LOCAL, not TRANSPORT" and "a stream that
      breaks while copying is TRANSPORT".
- [x] `ImageSaver` inserts with `IS_PENDING=1`, clears it after the copy, deletes the row on any
      `IOException`, and skips the copy when a row with the same `DISPLAY_NAME` and `RELATIVE_PATH`
      exists. Moved to `data/ImageSaver.kt` (`git mv`); `MediaSharer` already lived there.
- [x] `SaveResult.Failed` and `ShareResult.Failed` carry a `FetchFailure`; `DayHost` maps it to
      `fetch_failed_not_a_file`, `fetch_failed_transport`, `fetch_failed_local`. No literal reaches a toast.

### 2. Video

- [ ] `VideoPage` observes `LocalLifecycleOwner`: `playWhenReady = isActive && started`, `started`
      flipping on `ON_START`/`ON_STOP`. Device check: play a video, press power, no audio.
      > Blocked: no device attached in the executing session; the code is in.
- [x] `OkHttpDataSource.Factory(http)` over `AppGraph.http`, passed down from `DayHost`; the `_sid`
      comment sits at both `DownloadUrls.original` call sites (`OriginalFetch`, `VideoPage`).

### 3. Update flow

- [x] `UpdateChecker.check` returns `CheckOutcome` (`Found`, `NoRelease`, `Unreachable`);
      `Unreachable` becomes `UpdateUiState.CheckFailed` with its own title and body
      (`update_title_check_failed`, `update_body_check_failed`). `UpdateViewModelTest` "a forced
      check that cannot reach GitHub says so, not up to date". The view model's collaborators are
      interfaces (`UpdateChecking`, `UpdateDownloading`, `UpdateInstalling`, `SkippedVersions`) with
      `UpdateViewModel.factory` wiring the real ones; `AppRoot` creates it and hands it to Settings.
- [x] `UpdateUiState.NeedsPermission(info, file)`: the modal stays open with an explanation and an
      "Otevřít nastavení" button; `onAppOpen` retries the install only once `canInstall()` is true, so
      returning without the permission does not bounce back into Settings. `UpdateInfo.apkSize` from
      GitHub's asset `size`; `UpdateDownloader.download(url, version, expectedSize)` emits `Done` for
      an existing target of that size with no request and no wake lock. Tests: `UpdateViewModelTest`
      "a missing install permission keeps the modal, the file and the state, and resumes once
      granted"; `UpdateDownloaderTest` "a complete target of the expected size is reused without a
      request".
- [x] `UpdateUiState.Error(reason: UpdateFailure)`; `UpdateModal.errorText` maps to five strings;
      `UpdateLog` logs the reason and an exception class name only; `DownloadProgress.Failed` carries
      the enum. No `e.message` anywhere in `update/`.
- [x] `UpdateChecker(appClient)` and `UpdateDownloader(appClient)` build from `newBuilder()`; the
      downloader turns `followRedirects`/`followSslRedirects` back on with the GitHub 302 reason in
      its KDoc. `UpdateDownloaderTest` "... following GitHub's redirect". `UpdateChecker` now reads
      GitHub and its cache with kotlinx-serialization (plan 011's optional item), which is what made
      `UpdateCheckerTest` possible on the JVM.

### 4. Banner insets

- [ ] `AppRoot` applies `consumeWindowInsets(WindowInsets.statusBars)` to the `Box` below the banner
      while `updateBannerShown(state)`. Device screenshots with and without the banner still to take.
      > Blocked: no device attached in the executing session. Memory note updated to say so.

### 5. Liked group on first load

- [x] `loadSections` reads `likes.likedKeys.first()` (a DAO read) instead of the unsubscribed
      `StateFlow`'s initial value. `DayViewModelTest` "a cold start on a day with liked photos shows
      the liked group first": the first `display` emission with items leads with `Liked`.

### 6. `DayHost`

- [x] `DayHost` has one `saveItems` and one `shareItems` (`ACTION_SEND` for one, `ACTION_SEND_MULTIPLE`
      for many), used by the viewer and the selection bar; `data/FileProviderAuthority.kt` is the
      one authority, used by `DayHost` and `Installer`; every failure toast comes from the typed
      reason.
- [x] Kept (the plan's default, owner not reachable); the comment at `PhotoCell`'s `combinedClickable`
      names the ~300 ms hold.

## Addendum, 2026-09-03 evening: Play Protect, and the version bump that lived only on a tag

Two things the device session found that the review had not:

- **The failed update was Google Play Protect, not the app.** With v1.0.0 installed the in-app
  update to v1.0.1 ended in the system installer's "install failed" screen, both for the owner
  earlier in the day and in the session's own attempt. Logcat: `Finsky VerifyApps: Returning
  package verification result ... result=REJECT` after an 8 s "Apk Analysis scan", then
  `InstallFailed`. All three APKs (v1.0.0, v1.0.1, the co-signed debug build) carry the same
  release certificate, `REQUEST_INSTALL_PACKAGES` was granted, and the installer had staged the file:
  the verdict is Play Protect's alone, for an APK from a developer it does not know. With "scan
  apps with Play Protect" paused the same install went `result=ALLOW` → `InstallSuccess`. The app
  cannot learn the installer's outcome (`ACTION_VIEW` returns nothing), so at the owner's request
  the "update available" dialog now names Play Protect as the likely cause of a failed install and
  offers "Otevřít Play Protect", which opens the Play Store's Play Protect settings
  (`com.google.android.gms.settings.VERIFY_APPS_SETTINGS`, falling back to the security settings).
  The lasting fix would be distribution through Google Play or Google's developer verification for
  sideloaded apps; recorded as an open question in `decisions/index.md`.
- **`main` was behind the published version.** The release script commits the version bump in its
  own clone and pushes only the tag, so the v1.0.1 bump (versionCode 3) existed only on `v1.0.1`
  while `main` still said 1.0.0 / 2; the next release would have reused versionCode 3. The tag's
  commit is cherry-picked onto `main` (`chore(release): v1.0.1`), as `f499168` had done by hand
  for v1.0.0. The release script should push the bump commit too; noted in the index.

## Acceptance criteria

- [ ] Save works or fails with the right message on the Vivo; a failed save leaves no broken gallery
      entry (test by revoking storage space or killing Wi-Fi mid-copy of a large video).
      > Blocked: device check.
- [ ] Video stops when the phone locks.
      > Blocked: device check.
- [ ] Airplane mode, Settings, check for updates: the modal says the check failed, not that the app
      is current.
      > Blocked: device check; `UpdateViewModelTest` pins the state.
- [ ] Banner visible: no blank strip under it.
      > Blocked: device check.
- [x] Cold start on a day with liked photos shows the liked group at once (`DayViewModelTest`).
- [x] `./gradlew testDebugUnitTest` green: 115 tests.

## On completion

1. Tick as verified, keep `Progress:` in step in the same commit.
2. Update `index.md`; update `CLAUDE.md`'s device workflow section if minSdk changed.
3. Update the memory note about the update feature verification.
