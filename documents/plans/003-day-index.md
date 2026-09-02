# 003 - Day histogram and day selection

- **Status:** Partly done
- **Source:** plan.md §6.1, §6.2, §7
- **Depends on:** 001
- **Blocks:** 004
- **Decisions:** [005](../decisions/005-day-index-on-device.md)
- **Progress:** 3 / 12

## Goal

The device holds a histogram of every day that has photos, per namespace, with running totals,
and answers "which day do I show" locally and instantly. The selection logic is pure and tested
without a NAS, which is why part of this plan was finishable before anything else existed.

## Tasks

### Selection logic (pure, no NAS)

- [x] `MonthDay` keyed on its position in a leap year, so 29 February keeps a slot.
- [x] `selectDay`: exact match across years, else nearest day, wrapping at the year boundary,
      ties to the past.
- [x] Unit tests: exact match, fallback distance, equidistant tie, year wrap, 29 February,
      empty index.

### Fetch

- [ ] Timeline call per namespace, using the shape plan 001 recorded.
- [ ] Merge the two namespaces into one per-day view without losing which namespace a count
      came from, because paging offsets are per namespace.
- [ ] Running totals per namespace in taken-time order, so §6.3 can compute an offset.
- [ ] A stale-index policy: refresh on open when older than a threshold, and on pull to refresh.

### Storage

- [ ] Room entities for day buckets and running totals, scoped to the account.
- [ ] Migration-safe schema (exported schema committed).
- [ ] Clear-on-account-change, exercised by a test.

### Wiring

- [ ] Repository exposing today's selection as a flow, cache first, network after.
- [ ] "No photos at all" state, distinct from "no photos today".

## Acceptance criteria

- [ ] With the network off and a populated index, the app still names the day it would show.
- [ ] The running total for a known day matches the offset that returns that day's first photo,
      verified against the NAS once by hand and recorded in the research file.
