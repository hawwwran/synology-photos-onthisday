# 009 - Likes hardening

- **Status:** Not started. Written from the whole-project code review of 2026-09-03.
- **Source:** code review 2026-09-03: findings 3, 5, 10 and the smaller likes findings (folder
  setting surviving an account change, corrupt file overwritten, unserialized syncs, folder path
  logged).
- **Depends on:** 008 (schema 5 removes `pendingSync`), 007 (sid-scoped `onSessionExpired`).
- **Blocks:** 011.
- **Decisions:** [008](../decisions/008-writing-likes-to-the-nas.md) (amend: sync is serialized
  and transactional; a file the app cannot read is never overwritten).
- **Progress:** 0 / 13

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

- [ ] `parseLikeKey(key): Pair<Space, Int>?` next to `likeKey` in `likes/Likes.kt`; `toEntity` and
      `likedKeys` use it; bad rows are skipped and counted, never persisted. `LikesTest` covers
      `"abc"`, `"PERSONAL:x"`, `"FOO:5"`, `":5"`, and a good key.
- [ ] `likedKeys` tolerates a stored row with an unknown namespace (skips it) so an already-poisoned
      install recovers on update.

### 2. Serialized, transactional sync

- [ ] `LikeDao.reconcile(remote: List<LikeState>)` as one `@Transaction` that reads, merges via
      `LikesMerge`, writes, and returns the merged set to push. `replaceAll` goes.
- [ ] A `Mutex` and a dirty flag in `LikeRepository.sync`; concurrent callers coalesce into at most
      one follow-up run. Test with `runTest` and a fake DAO: a toggle between pull and write survives;
      three overlapping sync calls produce two runs.
- [ ] `pendingSync` removed from `LikeEntity` (schema 5, plan 008) and from all call sites.
- [ ] `likeSelected` writes one batch.

### 3. Envelope classification shared

- [ ] Extract the `{success, error.code}` classifier from `SynologyClient` into one function that
      returns `Success(data) | DsmError(code) | SessionExpired(code) | Malformed`; both clients use
      it. `HardeningTest` or a new `FileStationClientTest` with `MockWebServer`: HTML 200 on upload
      is `Malformed`; 119 on download is `SessionExpired`; a `likes.json` body without
      `Content-Disposition` is parsed as the file.
- [ ] `catch (e: Exception)` in `errorCode` narrowed or removed with the extraction.

### 4. Never overwrite what cannot be read

- [ ] A present but unparseable `likes.json` ends `sync` with `Failed` and no push. Test.
- [ ] `SyncResult.Failed` is surfaced once per session (toast from `DayHost`, text from
      `strings.xml`), not dropped. Amend decision 008 (dated line) with the serialization and
      no-overwrite rules.

### 5. Settings and logging

- [ ] `LIKES_FOLDER` resets to the default on account change (wiper registered in `AppGraph`).
      `SessionManagerTest` covers it.
- [ ] Likes logging goes through `ApiLog` (or a `LikesLog` with the same discipline): call name and
      outcome, no path, no count of entries.

### 6. Verify on device

- [ ] Rapid double-taps on several tiles while a sync is in flight; every heart state matches
      `likes.json` on the NAS afterwards (read it via File Station or the DSM file browser). Edit
      `likes.json` to contain a bad key, open the app: no crash, other likes shown, the file is not
      overwritten until the bad row is removed.

## Acceptance criteria

- [ ] No `Exception` other than `ApiFailure` can escape `LikeRepository.sync` (read the code; the
      tests above pin the known shapes).
- [ ] A like toggled during a sync is in the next `likes.json`.
- [ ] An unreadable `likes.json` is never overwritten, and the user is told once.
- [ ] `./gradlew testDebugUnitTest` green.

## On completion

1. Tick as verified, keep `Progress:` in step in the same commit.
2. Update `index.md`.
3. Plan 006's unticked acceptance items (second device, reinstall restore) can be re-tested now that
   sync is deterministic; tick them there if verified.
