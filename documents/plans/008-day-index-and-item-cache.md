# 008 - Day index and item cache correctness

- **Status:** Not started. Written from the whole-project code review of 2026-09-03.
- **Source:** code review 2026-09-03: findings 2, 6, 9 and the efficiency findings on `fetchDay`,
  `refresh` and `item_row`.
- **Depends on:** 005 (the code it fixes). Independent of 007 and 010.
- **Blocks:** 009 (owns the schema bump to version 5, which 009's column removal rides on), 011.
- **Decisions:** [005](../decisions/005-day-index-on-device.md) (amend: paging counts server rows;
  a count mismatch is reported, not turned into a forced refresh loop).
- **Progress:** 0 / 12

## Goal

Opening a day never crashes, never truncates the day, and never puts the index into a refresh loop.
The database migrates instead of being dropped, because v1.0.0 is installed on real phones.

## Findings this plan fixes

Line numbers are as of commit `c70ddb0`. Re-check them before editing.

### A. `item_row` insert aborts, and the crash repeats on every open

`data/db/Entities.kt:34` keys `item_row` by `(namespace, id)`. `data/db/DayIndexDao.kt:21-27`
`replaceDayItems` deletes rows of the target `(namespace, year, month, day)` and then `@Insert`s
with the default `ABORT` strategy. A photo cached under one day and later fetched under another
(its taken date corrected in Photos, or an id landing on two pages of a large day because
`sort_by=takentime` has one-second ties and no secondary key) violates the primary key.
`SQLiteConstraintException` is a `RuntimeException`, not an `ApiFailure`; `fetchDay`'s catches
(`data/DayIndexRepository.kt:115-120`) let it through, it climbs out of the `launch` in
`DayViewModel.loadSections` (`ui/day/DayViewModel.kt:313`) into `viewModelScope`, which has no
handler, and the process dies. `@Transaction` rolls the delete back, so the stale row survives and
the crash repeats on every open of that day until sign-out clears the table.

`app/src/test/.../FakeDayIndexStore.kt:38-42` appends without any key check, so
`DayIndexRepositoryTest` cannot see this.

Fix: `@Upsert` for `insertItems` (a row that moved days is rewritten under the new day), and
de-duplicate a fetched page list by `(namespace, id)` before storing. Make the fake enforce the
primary key so the test suite would have caught it.

### B. Paging stops on the filtered page size, then loops the refresh

`api/ItemApi.kt:49` drops items without `additional.thumbnail` via `mapNotNull` inside the API
layer. `data/DayIndexRepository.kt:103` compares that filtered list's size to `PAGE_SIZE` (200). One
thumbnail-less item on a full page ends paging and silently truncates the day. Then
`expected != total` (line 108) runs `IndexLog.dayCountMismatch` and `store.setRefreshedAt(0L)`: the
next open re-downloads both timelines (about 760 KB) and both counts, and `fetchDay` resets the
timestamp again. The 12-hour throttle is defeated for as long as any item of any shown day lacks a
thumbnail. `0L` as a "needs refresh" sentinel also overloads a timestamp.

Fix: `ItemApi.list` returns the raw page too (or the DTO count), and the loop stops on the server's
page size. Move the thumbnail-less drop above the count check, and count dropped items so the
mismatch logic knows about them. Replace `setRefreshedAt(0L)` with an explicit
`needsRefresh` flag in `index_meta`, set at most once per mismatch and cleared by the next
successful `refresh()`. `expectedCount` should take the bucket the caller already holds
(`yearBucket.itemCount`) instead of re-reading every `day_bucket` row (line 124).

### C. Destructive migration still enabled after release

`OnThisDayApp.kt:92` keeps `fallbackToDestructiveMigration(dropAllTables = true)` with a comment
calling it pre-release. v1.0.0 (versionCode 2) shipped 2026-09-03 and the schema is at 4. The next
bump drops `item_like`, including any like whose push failed. Schemas 1 to 4 are exported in
`app/schemas`, so proper migrations are possible.

Fix: remove the fallback; write `MIGRATION_4_5` for this plan's schema change (see D) and register
it. Add `androidx.room:room-testing` and a `MigrationTest` under `androidTest` using
`MigrationTestHelper` over the exported schemas. Fix the KDoc at `data/db/AppDatabase.kt:7`, which
says "Version 2".

### D. Query and write shape

- `itemsForDay` (`DayIndexDao.kt:18`) filters on `(year, month, day)` with no index: a full scan
  per collector, and there is one collector per shown year. Room invalidation is table-level, so
  each `replaceDayItems` (two per year, one per namespace) re-runs every collector.
- `refresh()` (`DayIndexRepository.kt:135-145`) makes four sequential round trips and three
  separate writes, each re-emitting the whole histogram through `observeDays` and flipping the
  grid through a PERSONAL-only state in between.
- `fetchDay` runs for every year on every open, prev/next, picker choice and refresh, with no
  regard for how fresh the cached rows are.

Fix: an index on `item_row(year, month, day)` (the version 5 migration); both namespaces of a day
written in one transaction; `refresh()` runs the two namespaces concurrently (`async`) and writes
both plus `refreshedAt` in one `withTransaction`; `fetchDay` is skipped when the cached row count for
`(year, monthDay)` already equals the bucket's `itemCount` and the index was refreshed inside the
staleness window (pull-to-refresh forces it). Decision 005's amendment records the rule.

### E. Logging

`data/IndexLog.kt:7` says "never a day or a photo" and line 16 logs a date. The project rule is call
name and code only. Log the mismatch as counts and a namespace, no date. (The rest of the logging
rule is plan 011.)

## Tasks

### 1. Schema version 5, real migrations

- [ ] `MIGRATION_4_5`: index on `item_row(year, month, day)`; `index_meta` gains `needsRefresh`
      (or an equivalent column); `item_like` drops `pendingSync` (for plan 009; do it here so there
      is one migration). Export `5.json`.
- [ ] Remove `fallbackToDestructiveMigration`; register the migration; fix the `AppDatabase` KDoc.
- [ ] `room-testing` dependency and an instrumented `MigrationTest` from 4 to 5 that seeds a row per
      table and reads it back. Run on the Vivo.

### 2. Item rows

- [ ] `insertItems` becomes `@Upsert`; `replaceDayItems` takes both namespaces' rows and runs in one
      transaction; pages are de-duplicated by `(namespace, id)` before storing.
- [ ] `FakeDayIndexStore` enforces the `(namespace, id)` key the way SQLite does (throws on
      duplicate insert, or upserts if the DAO now upserts) so it mirrors the real store; a
      `DayIndexRepositoryTest` case moves an item from one day to another and asserts no exception
      and one row.
- [ ] `DayIndexDaoTest` (instrumented) covers the moved-item case against real Room.

### 3. Paging and the mismatch rule

- [ ] `ItemApi.list` exposes the server page size; the loop in `fetchDay` uses it. Test with
      `MockWebServer`: a 200-item page with one thumbnail-less item is followed by a second request.
- [ ] `expectedCount` takes the caller's bucket; the mismatch is logged without a date and sets
      `needsRefresh` once; `refresh()` clears it; `isStale()` honours it. Test: a mismatch triggers one
      refresh on the next open, not one per open.
- [ ] Amend decision 005 (dated line): paging counts server rows; dropped items are counted; a
      mismatch schedules one refresh.

### 4. Fewer round trips

- [ ] `refresh()` fetches both namespaces concurrently and writes once; `observeDays` emits one
      state per refresh. Test: the fake store records one `replace` batch.
- [ ] `fetchDay` skips the network when cached count equals the bucket count inside the staleness
      window; pull-to-refresh bypasses the skip. Test both branches.

### 5. Verify on device

- [ ] Install over the existing v1.0.0 build (`installDebug` co-signs with the release key), confirm
      likes and the index survive the 4 to 5 migration, open today, prev/next across a large day
      (the shared space has a 1,220-item day), no crash, no refresh loop in `logcat`
      (`adb logcat -s PhotosIndex PhotosApi`).

## Acceptance criteria

- [ ] A photo whose date moved between two cached days opens without a crash (reproduce by opening
      day X, editing one photo's date to day Y in Synology Photos, opening day Y).
- [ ] A day larger than one page loads completely; the count in the year header matches the grid.
- [ ] `logcat` shows at most one histogram refresh per app start.
- [ ] Upgrading from the shipped v1.0.0 keeps the local likes cache and the index.
- [ ] `./gradlew testDebugUnitTest` green; the instrumented DAO and migration tests pass on the Vivo.

## On completion

1. Tick as verified, keep `Progress:` in step in the same commit.
2. Update `index.md`.
3. Plan 009 can start (it relies on schema 5).
