# 003 - Day histogram and day selection

- **Status:** Partly done
- **Source:** plan.md §6.1, §6.2, §7
- **Depends on:** 001
- **Blocks:** 004
- **Decisions:** [005](../decisions/005-day-index-on-device.md), amended 2026-09-02
- **Progress:** 3 / 12

## Goal

The device holds a histogram of every day that has photos, per namespace, and answers "which
day do I show" locally and instantly. The selection logic is pure and tested
without a NAS, which is why part of this plan was finishable before anything else existed.

## Tasks

### Selection logic (pure, no NAS)

- [x] `MonthDay` keyed on its position in a leap year, so 29 February keeps a slot.
- [x] `selectDay`: exact match across years, else nearest day, wrapping at the year boundary,
      ties to the past.
- [x] Unit tests: exact match, fallback distance, equidistant tie, year wrap, 29 February,
      empty index.

### Fetch

- [ ] Timeline call per namespace, using the shape plan 001 recorded: `data.section[]`, each
      section a page of the item list carrying its days.
- [ ] Flatten the sections into one day list, taking each `(year, month, day)` once, because a
      day larger than a page repeats across sections with its full `item_count`.
- [ ] Merge the two namespaces into one per-day view without losing which namespace a count
      came from, because the item fetch and the thumbnail api are per namespace.
- [ ] A stale-index policy: refresh on open when older than a threshold, and on pull to refresh.

### Storage

- [ ] Room entities for day buckets, scoped to the account. About 5,300 rows for this library.
- [ ] Migration-safe schema (exported schema committed).
- [ ] Clear-on-account-change, exercised by a test.

### Wiring

- [ ] Repository exposing today's selection as a flow, cache first, network after.
- [ ] "No photos at all" state, distinct from "no photos today".

## Acceptance criteria

- [ ] With the network off and a populated index, the app still names the day it would show.
- [ ] The flattened histogram's total per namespace equals `Browse.Item` `count`, which plan 001
      showed the data satisfies (25,149 and 77,436); the app's flatten must preserve it.
