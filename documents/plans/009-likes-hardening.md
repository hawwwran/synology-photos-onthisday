# 009 - Likes hardening

- **Status:** Code done 2026-09-03; the device check waits for the Vivo (no device attached in the
  executing session).
- **Source:** code review 2026-09-03: findings 3, 5, 10 and the smaller likes findings (folder
  setting surviving an account change, corrupt file overwritten, unserialized syncs, folder path
  logged).
- **Depends on:** 008 (schema 5 removes `pendingSync`), 007 (sid-scoped `onSessionExpired`).
- **Blocks:** 011.
- **Decisions:** [008](../decisions/008-writing-likes-to-the-nas.md) (amend: sync is serialized
  and transactional; a file the app cannot read is never overwritten).
- **Progress:** 12 / 13

## Goal

A like is never lost and never crashes the app. Decision 008 says "a failed sync keeps the local
like and retries; it never silently drops a like". Today a race drops it, a malformed file crashes
the app on every launch, and a proxy page counts as a successful upload.

## Findings this plan fixes

Line numbers are as of commit `c70ddb0`. Re-check them before editing.

### A. A malformed key in `likes.json` crashes the app, possibly forever

`likes/LikeRepository.kt:80-81` destructures `key.split(":", limit = 2)` and calls `id.toInt()`;
lines 35 and 61 call `Space.valueOf(it.namespace)`. `sync` catches only `ApiFailure` (lines
67-74). `LikesNasStore.pull` (`likes/LikesNasStore.kt:18-22`) catches `SerializationException`
only, and any string is a valid `key`. So `{"key":"abc"}` throws `IndexOutOfBoundsException` out
of `sync`, out of `viewModelScope.launch { likes.sync(session) }` (`ui/day/DayViewModel.kt:178`),
and kills the process on every open until the file is fixed. `{"key":"FOO:5"}` passes `toEntity`,
`replaceAll` persists `namespace = "FOO"`, and from then on `likedKeys` throws inside its
`stateIn` collector on every launch regardless of the file: only an account-change wipe recovers.

The file is user-editable, lives at a free-text path, and may be written by another device or a
future version. Fix: parse a key into `(Space, Int)?` in one place, drop and count rows that fail,
never persist a namespace that is not a `Space`, and make `likedKeys` tolerant of a bad stored row.

### B. Sync races a toggle and deletes it

`LikeRepository.kt:60-64`: `nas.pull`, then `dao.all()` (a plain query), then merge in memory,
then `dao.replaceAll(merged)` which is `clear()` plus `upsertAll` in one transaction
(`data/db/LikeDao.kt:30-34`), then `nas.push`. A `dao.upsert` from a toggle that commits after
`all()` returns and before `clear()` runs is deleted and never re-inserted; the toggle's own sync
then reads the table without it, and the heart flips back with no error. The window is the merge
plus a dispatcher hop, small but hit routinely: init, every toggle and every batch like each launch
a sync (`DayViewModel.kt:178,227,274`) with nothing serializing them (no `Mutex`, no job
tracking). `pendingSync` is written at lines 50 and 81 and read nowhere.

Fix: one `Mutex` in `LikeRepository` around `sync`; the read-merge-write becomes a single DAO
`@Transaction` that reads current rows, merges with the remote list, and writes, so a toggle
either lands before the read (and is merged) or after the write (and survives). Coalesce: a sync
requested while one runs marks it dirty and runs once more after. `likeSelected` does one
`upsertAll`. `pendingSync` goes away (plan 008's migration); last-writer-wins by `updatedAt` already
carries a failed push to the next sync.

### C. `FileStationClient.errorCode` misclassifies

`likes/FileStationClient.kt:121-130`: a body that is not a JSON object returns `null`, which the
upload path takes as success. A reverse-proxy HTML page with HTTP 200 therefore ends a sync as
`Success` while the rows are now marked as synced and the file was never written. On download, a
body without `Content-Disposition` is fed to `errorCode`, and a real `likes.json` (no `success` key)
becomes `DsmError(-1)`. DSM session codes (106, 107, 119) are thrown as `DsmError`, never
`SessionExpired`, so `LikeRepository.sync`'s expiry branch (line 67) is unreachable from the likes
path. `catch (e: Exception)` there is also wider than the project allows.

Fix: one envelope classifier shared with `SynologyClient.decodeEnvelope` (extract it), used by both
clients, with the same `SESSION_GONE_CODES` mapping (105 excluded after plan 007). Upload: a body that
is not an envelope is `Malformed`, not success. Download: decide by shape, not header: envelope with
`success` key, or a parseable `LikesFile`, else `Malformed`.

### D. Smaller

- `LikesNasStore.kt:21` treats a corrupt or foreign file as empty; `sync` then pushes with
  `overwrite=true` and destroys it. Decision 008 forbids silent loss. A file that exists but does not
  parse must stop the sync with `Failed`, and the failure must reach the user once (a toast or a
  Settings line), because `SyncResult` is currently ignored by every caller.
- `SessionStore.LIKES_FOLDER` (`session/SessionStore.kt:129`) survives sign-out and account change;
  it is in no wiper and no clear list. Account B then syncs into account A's custom folder if B can
  write there. Reset it to the default on account change (a `SessionStore` wiper or a call from
  `SessionManager`). Decision 006's amendment says nothing else is stored; make that true.
- `LikeRepository.kt:58` logs the folder path (`Log.i("PhotosLikes", "sync start, folder=$dir")`),
  a request parameter, against the logging rule. Lines 65, 69, 72 bypass `ApiLog`. Log the call name
  and the outcome only.
- `likeSelected` upserts one row per item; a batch should be one `upsertAll`.

## Tasks

### 1. Key parsing

- [x] `parseLikeKey` and `spaceOrNull` in `likes/Likes.kt`; `LikeRepository.toEntity` uses the
      first, `toState` and `likedKeys` the second. `SyncResult.Success(skippedKeys)` carries the
      count. `LikesTest` "a key is parsed, never destructured" covers eight bad shapes and two good;
      `LikeRepositoryTest` "a bad key in the file is skipped and counted, never persisted".
- [x] `LikeRepositoryTest` "a stored row with an unknown namespace is skipped by likedKeys".

### 2. Serialized, transactional sync

- [x] `LikeDao.reconcile(merge)` is one `@Transaction` default method: read `all()`, apply the
      caller's merge, `upsertAll`, return what was written. No `clear()` in the middle; `replaceAll`
      is gone. The DAO stays free of the `likes` package: the repository passes the merge as a lambda.
- [x] `Mutex` plus an `AtomicBoolean` request flag: a caller sets the flag, takes the lock, and loops
      while the flag is set. `LikeRepositoryTest`: "a toggle made while the file is being pulled is in
      the pushed set and stays liked"; "overlapping sync requests collapse into one follow-up run"
      (three calls, two pulls).
- [x] `pendingSync` gone from `LikeEntity` (plan 008's migration) and from every call site.
- [x] `LikeRepository.setLikedAll` is one `upsertAll` with one timestamp; `DayViewModel.likeSelected`
      calls it. Test "like selected writes one batch with one timestamp".

### 3. Envelope classification shared

- [x] `api/Envelope.kt`: `Json.classifyEnvelope(body)` returns `Success(data) | Error(code) |
      NotAnEnvelope(detail)`; `ApiFailure.fromDsmCode(call, code)` maps a code to `SessionExpired`
      or `DsmError` once. `SynologyClient.decodeEnvelope` and both `FileStationClient` paths use them.
      `FileStationClientTest`: HTML 200 on upload is `Malformed`; 119 on upload and on download is
      `SessionExpired`; a `likes.json` body with no `Content-Disposition` is the file; 404 and 408 are
      null; a success envelope where a file was expected is `Malformed`.
- [x] `errorCode` and its `catch (e: Exception)` are gone; the classifier catches
      `SerializationException` and `IllegalArgumentException` only.

### 4. Never overwrite what cannot be read

- [x] `LikesNasStore.pull` throws `Malformed(FS_DOWNLOAD, "likes file is not readable")` on a parse
      failure; `sync` catches it as `Failed` before any push. `FileStationClientTest` "a file that
      exists but is not the likes shape is Malformed"; `LikeRepositoryTest` "a file that exists but
      cannot be read stops the sync and pushes nothing".
- [x] `DayViewModel.syncLikes()` wraps every sync; the first `Failed` per view model lands in
      `likesNotice`, which `DayHost` shows as a toast (`R.string.likes_sync_failed`) and clears.
      Decision 008 amended (2026-09-03).

### 5. Settings and logging

- [x] `SessionStore.resetAccountSettings()` removes `LIKES_FOLDER`; `AppGraph` registers it among
      the account-change-only wipers. `SessionManagerTest` "the likes folder follows the account":
      kept across a same-account re-login, default after a change.
- [x] `LikeRepository` logs nothing; the two clients log through `ApiLog` (call name, code or
      detail). The folder path and the entry count no longer reach the log.

### 6. Verify on device

- [ ] Rapid double-taps on several tiles while a sync is in flight; every heart state matches
      `likes.json` on the NAS afterwards (read it via File Station or the DSM file browser). Edit
      `likes.json` to contain a bad key, open the app: no crash, other likes shown, the file is not
      overwritten until the bad row is removed.
      > Blocked: no device attached in the executing session. Note for the run: a bad *key* is now
      > skipped and the file *is* rewritten without it (decision 008 amendment); only a file that
      > does not parse at all is left alone.

## Acceptance criteria

- [x] Read: `syncOnce` calls the remote (throws `ApiFailure` only, by contract and by the client's
      catches), `parseLikeKey`/`spaceOrNull` (never throw), `LikesMerge` (pure) and the DAO. Every
      `ApiFailure` is caught. A Room exception on a full disk is the one path outside this, as for
      every other write in the app.
- [x] `LikeRepositoryTest` "a toggle made while the file is being pulled is in the pushed set".
- [x] Tests above; the toast path is by reading (`DayHost` `LaunchedEffect(likesNotice)`).
- [x] `./gradlew testDebugUnitTest` green: 98 tests.

## On completion

1. Tick as verified, keep `Progress:` in step in the same commit.
2. Update `index.md`.
3. Plan 006's unticked acceptance items (second device, reinstall restore) can be re-tested now that
   sync is deterministic; tick them there if verified.
