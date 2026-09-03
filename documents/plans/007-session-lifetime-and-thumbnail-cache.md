# 007 - Session lifetime and the thumbnail cache

- **Status:** Code done 2026-09-03; the three device checks wait for the Vivo (no device attached
  in the executing session).
- **Source:** code review 2026-09-03: findings 1, 4, 8, 12, 16, and the wiper-on-main finding.
- **Depends on:** 005 (the code it fixes). Independent of 008-010; run it first, it holds the
  two most severe findings.
- **Blocks:** 011 (documentation follows the code).
- **Decisions:** [003](../decisions/003-authentication-and-sessions.md) (amend: an expiry is
  scoped to the session that saw it; DSM 105 is a permission answer, not an expiry),
  [006](../decisions/006-one-account-per-install.md) (amend: account identity is host plus
  account name).
- **Progress:** 12 / 14

## Goal

A session that ends can only sign out itself. A cached thumbnail can never hold anything but an
image, and can never outlive the NAS it came from. Decisions 003 and 006 promise both; the code
breaks both today.

## Findings this plan fixes

Line numbers are as of commit `c70ddb0`. Re-check them before editing.

### A. A stale `DayViewModel` signs the new session out

`ui/day/DayHost.kt:54` creates the view model with `viewModel(key = "day-${sid}")`. That key
lives in the Activity's `ViewModelStore`, which drops entries only when the Activity finishes.
`ui/AppRoot.kt:70-82` swaps composables on `SessionState`, so the old instance survives sign-out
and outlives the new one.

`ui/day/DayViewModel.kt:180-185` launches, in `viewModelScope`, a
`combine(dayView, reloadTick)...collectLatest` that never completes. It keeps
`repository.observeDays()` hot. When the new session's refresh writes `day_bucket`, the old
instance sees a new `(monthDay, years)` triple, runs `loadSections`, and calls
`repository.fetchDay(sessionA, ...)` with the dead sid. DSM answers 119.
`data/DayIndexRepository.kt:115-117` calls `onSessionExpired()`, and `SessionStore.markExpired()`
(`session/SessionStore.kt:104-110`) takes no argument: it removes whatever sid is stored, which
is by now the new one. The fresh login is thrown back to the sign-in screen with the expiry
notice. A same-account sign-out and sign-in hits it too whenever the shown day has any
shared-space photos, because the first `replace(PERSONAL)` after the wipe yields a PERSONAL-only
histogram and the triple differs.

Variant: if `logout` failed offline and sid A is still valid, the old instance writes account A's
rows into `item_row`, which has no account column (`data/db/Entities.kt:34`), and account B's
grid renders them. That is the decision 006 breach.

Fix, both halves, because either alone leaves a hole:

1. `SessionManager.onSessionExpired(sid: String)`: no-op when `sid` differs from the stored one.
   `DayIndexRepository` and `LikeRepository` pass the session they actually used. The
   `onSessionExpired` lambdas in `OnThisDayApp.kt:110,120` change shape accordingly.
2. The view model must not outlive `DayHost`. Preferred: a fixed key and a
   `ViewModelStoreOwner` owned by `DayHost` (`remember { ... }` a store, provide it with
   `CompositionLocalProvider(LocalViewModelStoreOwner provides owner)`, clear it in
   `DisposableEffect.onDispose`). A fixed key alone is not enough: the instance would then be
   reused across sessions with the old `session` baked into its constructor.

### B. Coil caches DSM's JSON error envelope as a thumbnail

Research U4 (`documents/research/photos-web-api.md:240-242`) records that a thumbnail GET with a
missing token answers HTTP 200 with a 38-byte `application/json` envelope, and says the fetcher
must treat a non-image content type as a failure. Nothing does. `OnThisDayApp.kt:56` installs the
stock `OkHttpNetworkFetcherFactory`; Coil 3's `NetworkFetcher` throws only on a non-2xx status,
then writes the body to the disk cache under `diskCacheKey` before decoding. `ui/day/Thumbnail.kt:52-53`
sets that key to `ref.cacheId` (`unitId-size`, session-independent by design). Decode fails, the
cell shows the error state, and the JSON stays on disk. Every later request for that key is served
from disk without a network call, so the cell stays grey. A same-account re-login keeps the cache on
purpose (decision 006 amendment), so only Settings > Clear cache recovers.

Trigger in normal use: the index is fresh (under 12 h), so opening the app makes no API call that
would notice an expired session before the grid requests thumbnails with the dead sid. Every
visible cell is poisoned before `fetchDay` sees the 119.

Fix: a Coil interceptor or a wrapped fetcher that rejects a response whose `Content-Type` does not
start with `image/` before anything reaches the disk cache, and, as a belt, purges the disk and
memory entry for the key when decode fails. Put the content-type rule where the fetcher factory is
built, so it holds for the viewer's `ZoomableImage` request too (`ui/day/ViewerScreen.kt:315-320`).

### C. Account identity ignores the NAS

`session/SessionManager.kt:66-67` compares `store.lastAccount()` with the trimmed account name only.
The same user name on a different `baseUrl` skips `wipeAll()` and the thumbnail wipe, so the old
NAS's histogram, `item_row` rows, likes cache and thumbnails are shown for the new NAS's ids until a
refresh happens to overwrite them. The wipe is what decision 006 rests on.

Fix: identity is `(host, account)`. Store both, compare both. Amend decision 006 to say so.

### D. DSM 105 is treated as session expiry

`api/ApiFailure.kt:34` puts 105 (insufficient privilege) in `SESSION_GONE_CODES`. On the data path
that becomes `SessionExpired`, `onSessionExpired()`, and a sign-out with "NAS ukončil relaci".
`SessionManager.kt:58-60` already un-classifies 105 on login as "not permitted", so the two paths
disagree. The research doc (`photos-web-api.md:120-122`) lists 105 among re-prompt codes, following
Synology's guide, and U6 was dropped because no restricted account exists in this household. The
classification is still wrong: an account without shared-space access would be signed out on every
refresh, in a loop, with the wrong message, and `DsmErrorText` already has the right text for 105.

Fix: remove 105 from `SESSION_GONE_CODES`; it becomes `DsmError(105)` everywhere, mapped by
`DsmErrorText`. Drop the special case at `SessionManager.kt:58-60`. Amend decision 003 and correct
the research doc's list. A `DayIndexRepository.refresh` that hits 105 on one namespace should keep
the other namespace's result and report the error, not abort the refresh.

### E. Unguarded `.jsonObject` on `data`

`decodeEnvelope` (`api/SynologyClient.kt:79-98`) returns `root["data"] ?: JsonNull` on success.
`api/AuthApi.kt:42`, `api/ItemApi.kt:21` and `api/FolderApi.kt:21` then call `.jsonObject` on it
outside any `ApiFailure` mapping. A success envelope with a missing or non-object `data` throws a
raw `IllegalArgumentException`. `FolderApi.path` documents "never throws to the caller" and is
awaited from a `LaunchedEffect` (`ui/day/ViewerScreen.kt:364`), so the app would crash on the info
sheet. (The review also claimed `decodeEnvelope` itself lets a non-object root body through; that
was refuted: kotlinx-serialization-json 1.9.0 throws `IllegalArgumentException`, which line 84
catches.)

Fix: a `SynologyClient.callObject(...)` (or an extension) that maps a non-object `data` to
`ApiFailure.Malformed(call, "data is not an object")`, used by the three sites. `FolderApi.path`
catches every `ApiFailure` and returns null, as its KDoc says.

### F. The thumbnail wipe runs on the main thread

`data/ThumbnailCacheWiper.kt:16` calls `loader.diskCache?.clear()`, synchronous file deletion,
from `SessionManager.signIn`, which `SignInViewModel` runs on `viewModelScope` (Main). An account
change after months of use freezes the sign-in screen. `DayHost.kt:203-208` re-implements the same
three lines under `withContext(Dispatchers.IO)`, which is the right shape.

Fix: `withContext(Dispatchers.IO)` inside `wipe()`; expose the wiper on `AppGraph` and have the
Settings button call it, deleting the copy in `DayHost`.

### G. Thumbnail cache key omits `cache_key`

`api/Thumbnail.kt:23` keys the cache by `unitId-size`. Photos changes `cache_key` when a photo is
edited (rotated, adjusted), so the app keeps showing the old rendition forever. The key must stay
session-independent (plan.md §7), and `cache_key` is. Add it. The review's other claim, that unit
ids collide across the personal and shared space, was refuted: the companion repo's schema research
(`synology-photos-companion/documents/research/synology-schema/membership.md`) shows one `unit`
table for the whole library.

## Tasks

### 1. Expiry is scoped to a session

- [x] `SessionManager.onSessionExpired(sid)` ignores a sid that is not the stored one;
      `SessionStore.markExpired(sid)` does the compare inside the same `edit`, so it is atomic.
      `DayIndexRepository` and `LikeRepository` pass `session.credentials.sid`. `SessionManagerTest`:
      "an expiry seen by an old session leaves the current one signed in" and "an expiry seen by the
      current session signs it out".
- [x] `DayHost` owns its view model's lifetime: a private `DayViewModelStoreOwner` remembered per
      `session`, cleared in `DisposableEffect.onDispose`, passed to `viewModel(viewModelStoreOwner =)`.
      `git grep 'key = "day-'` returns nothing.
- [ ] Device check: sign out, sign in as the same account, wait for the grid; no expiry bounce.
      Then, with a second household account if available, sign out and in as the other account; the
      grid never shows the first account's photos and no expiry bounce.
      > Blocked: no device attached in the executing session (only an offline emulator).

### 2. Thumbnail bytes are images or nothing

- [x] `acceptsImageResponse(statusCode, contentType)` in `api/Thumbnail.kt` is the rule;
      `data/ImageLoading.kt` wires it as an OkHttp interceptor (`ImageResponseGuard`) on the image
      loader's client, so it holds for the grid and the viewer alike. `ImageLoadingTest`: the U4
      envelope fails the call, an image passes.
- [x] `FailedDecodePurge`, a Coil interceptor in the same file, evicts the request's disk and memory
      entries on any `ErrorResult`. This is also what heals a cache poisoned by v1.0.0.
- [ ] Device check: with a fresh index (under 12 h), invalidate the session on the NAS side (sign the
      same account in from a browser so DSM answers 107, or sign out of DSM there), then open the app
      so the grid requests thumbnails with the dead sid; after the forced re-login no cell may stay
      grey. Note the steps and outcome in this plan.
      > Blocked: no device attached in the executing session.

### 3. Account identity is host plus account

- [x] `SessionStore.lastIdentity()` returns `AccountIdentity(baseUrl, account)`; `signIn` compares
      both. `SessionManagerTest`: "the same account name on another NAS is another account and
      wipes"; the existing same-account test still passes with no wipe.
- [x] Decision 006 amended (2026-09-03): identity is the stored base URL plus the account name.

### 4. 105 is a permission error

- [x] 105 removed from `SESSION_GONE_CODES`; the login special case is gone. `SynologyClientTest`:
      "insufficient privilege is a DsmError, not an expiry"; the session-codes test now lists 106,
      107, 119.
- [x] `refresh()` fetches both namespaces concurrently and writes whatever arrived in one
      `replaceBuckets`. A `DsmError` on one namespace keeps the other, stamps the index and returns
      `Failed` with that text; a transport or shape failure keeps the other but leaves the stamp
      alone so the next open retries; a dead session writes nothing. Three
      `DayIndexRepositoryTest` cases. Tests route MockWebServer by call (`RoutedPhotosServer`),
      because the two namespaces are now in flight at once.
- [x] Decision 003 amended (2026-09-03); the research paragraph on re-prompt codes corrected.

### 5. Envelope `data` shape

- [x] `SynologyClient.callObject` maps non-object `data` to `Malformed`; `AuthApi`, `ItemApi.count`
      and `FolderApi.path` use it, and the inner reads are safe casts, so no cast exception can
      leave the api layer. `SynologyClientTest` covers `{"success":true}` and
      `{"success":true,"data":[]}`.

### 6. Wiper and cache key

- [x] `ThumbnailCacheWiper.wipe()` runs under `withContext(Dispatchers.IO)`; `AppGraph.thumbnailWiper`
      is public and Settings > Clear cache calls it; the inline copy in `DayHost` is gone.
- [x] `cacheId` is `unitId-cacheKey-size`; the KDoc says why and no longer mentions `_sid`. Note
      for the next install: every v1.0.0 thumbnail is re-fetched once, which also discards any
      cached error envelope.

## Acceptance criteria

- [ ] Sign out and sign in as the same account, on a day with shared-space photos: no expiry bounce,
      grid loads. Verified on the Vivo.
      > Blocked: no device attached in the executing session.
- [ ] A session expired on the NAS side, then re-login: no permanently grey cells.
      > Blocked: no device attached in the executing session.
- [x] `./gradlew testDebugUnitTest` green: 74 tests (64 before), the ones named above included.
- [x] Decisions 003 and 006 carry dated amendment lines; `decisions/index.md` summaries updated.

## On completion

1. Tick as verified, keep `Progress:` in step in the same commit.
2. Update `index.md`.
3. Note in `documents/research/photos-web-api.md` U4 that the content-type rule is now implemented.
