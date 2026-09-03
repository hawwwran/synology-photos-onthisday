# 008 - Day index and item cache correctness

- **Status:** Done 2026-09-03; verified on the Vivo the same evening (the 1,220-item day was not
  located, see the acceptance note).
- **Source:** code review 2026-09-03: findings 2, 6, 9 and the efficiency findings on `fetchDay`,
  `refresh` and `item_row`.
- **Depends on:** 005 (the code it fixes). Independent of 007 and 010.
- **Blocks:** 009 (owns the schema bump to version 5, which 009's column removal rides on), 011.
- **Decisions:** [005](../decisions/005-day-index-on-device.md) (amend: paging counts server rows;
  a count mismatch is reported, not turned into a forced refresh loop).
- **Progress:** 12 / 12

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

- [x] `data/db/Migrations.kt`: `MIGRATION_4_5` creates `index_item_row_year_month_day`, adds
      `index_meta.needsRefresh INTEGER NOT NULL DEFAULT 0`, and rebuilds `item_like` without
      `pendingSync` (copy, drop, rename: `DROP COLUMN` needs SQLite 3.35, API 34). `5.json` exported;
      its `createSql` matches the migration statement for statement.
- [x] `fallbackToDestructiveMigration` removed; `addMigrations(*MIGRATIONS)`; `AppDatabase` KDoc says
      version 5 and why migrations, not drops.
- [x] `room-testing` added; `androidTest/.../MigrationTest` seeds one row per table under 4.json,
      runs `runMigrationsAndValidate(5)`, reads every row back including a like with `pendingSync=1`.
      Passed on the Vivo (`connectedDebugAndroidTest`, 6 tests, 0 failures, 2026-09-03 19:42).
      Warning for the next run: that task **uninstalls the app afterwards**, data included; pass
      `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` (see `CLAUDE.md`).

### 2. Item rows

- [x] `upsertItems` is `@Upsert`; `DayIndexDao.replaceDayItems(namespaces, ...)` deletes each named
      namespace's day and upserts all rows in one `@Transaction`; `fetchDayItems` collects a page
      into a `LinkedHashMap` by id, so an item on the edge of two pages is stored once
      ("an item on the edge of two pages is cached once").
- [x] `FakeDayIndexStore` keys rows by `(Space, id)` and replaces under that key, as the DAO's upsert
      does. "an item that moved to another day is rewritten there, not inserted twice".
- [x] `DayIndexDaoTest` gains `an_item_that_moved_days_is_rewritten_under_the_new_day` and a
      `needsRefresh` case against real Room. Passed on the Vivo with the migration test.

### 3. Paging and the mismatch rule

- [x] `ItemApi.list` returns `ItemPage(items, serverCount)`; the loop stops on `serverCount`.
      "paging stops on the server page size, not on the filtered one": a 200-item page with one
      thumbnail-less item is followed by an `offset=200` request, and the server total matching the
      histogram schedules no refresh.
- [x] `fetchDay(session, year, monthDay, expectedCount, force)` takes the bucket's count from the
      caller (`DayViewModel.loadSections` passes `yearBucket.itemCount`); the mismatch line logs
      counts only; `markNeedsRefresh()` is an idempotent `UPDATE`; a stamped `replaceBuckets`
      clears it; `isStale()` honours it. "a count mismatch schedules one refresh, which the next open
      runs and clears": two mismatching fetches, one refresh, then none.
- [x] Decision 005 amended (2026-09-03).

### 4. Fewer round trips

- [x] Done in plan 007 for the histogram (`refresh()` is `async` per namespace, one `replaceBuckets`
      with the stamp in the same transaction; the fake records one write). This plan does the same
      for the day: `fetchDay` fetches both namespaces at once and writes them in one
      `replaceDayItems` ("... caches them newest first in one write").
- [x] Skip when `cachedCount == expectedCount` and the index is not stale, unless `force`.
      `DayViewModel` passes `force = true` when the reload tick changed (the user asked). Tests:
      the skip, the forced bypass, and "a stale index never skips the day fetch".

### 5. Verify on device

- [x] Installed over the running v1.0.0 (19:41): the grid came back identical, every heart in place,
      no crash, and zero `Browse.Item.list` calls because the migrated cache matched the histogram
      (the new skip rule). Prev/next across twelve days and one manual refresh: item fetches per
      year only, exactly one `Timeline.get` plus `Item.count` per namespace on the refresh, no
      `PhotosIndex` line, no `AndroidRuntime` line. The 1,220-item shared day was not opened: its
      date is not recorded anywhere, so the multi-page path rests on the JVM paging tests.

## Acceptance criteria

- [ ] A photo whose date moved between two cached days opens without a crash (reproduce by opening
      day X, editing one photo's date to day Y in Synology Photos, opening day Y).
      > Not reproduced live (it needs an edit in Synology Photos); the instrumented DAO test
      > `an_item_that_moved_days_is_rewritten_under_the_new_day` passed on the device.
- [ ] A day larger than one page loads completely; the count in the year header matches the grid.
      > Not verified live: the 1,220-item day's date is unknown. JVM tests cover the paging.
- [x] `logcat` shows at most one histogram refresh per app start (one per namespace after sign-in,
      one per namespace on manual refresh, none on navigation).
- [x] Upgrading from the shipped v1.0.0 keeps the local likes cache and the index (19:41).
- [x] `./gradlew testDebugUnitTest` green; the instrumented DAO and migration tests passed on the Vivo.

## On completion

1. Tick as verified, keep `Progress:` in step in the same commit.
2. Update `index.md`.
3. Plan 009 can start (it relies on schema 5).
