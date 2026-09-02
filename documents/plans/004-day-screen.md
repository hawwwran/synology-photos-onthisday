# 004 - Day screen, paging, thumbnails

- **Status:** Done
- **Source:** plan.md §6.3, §8.2
- **Depends on:** 002, 003
- **Blocks:** 005
- **Decisions:** [005](../decisions/005-day-index-on-device.md), amended 2026-09-02
- **Progress:** 14 / 14

## Goal

The chosen day rendered: a year strip newest first, a photo grid per year, thumbnails loading
from the NAS through the signed-in session, and a header that says which day is on screen and
how far it is from today when it is not today.

## Tasks

### Items

- [x] Item list call by `start_time`/`end_time` for the day in each year, per namespace, sorted
      by taken time; `offset`/`limit` inside the range for a day larger than a page.
- [x] Range bounds: `start_time` = the day's midnight rendered as UTC, `end_time` = start +
      86399. `time` is the camera's wall clock stored as if UTC, so no zone conversion anywhere.
- [x] A returned count that differs from the histogram's `item_count` is logged and schedules a
      histogram refresh. Nothing is retried or widened.
- [x] Item rows cached per opened day.
- [x] Videos: `type` is `"photo"` or `"video"`, same fields and a ready thumbnail (plan 001).
      Shown in the grid with a badge; playback stays out of scope (plan.md §9).

### Thumbnails

- [x] Coil fetcher that adds `_sid` and the `X-SYNO-TOKEN` header, which the GET requires, with
      a cache key of unit id plus size so a new session does not invalidate the disk cache.
- [x] A non-image content type from the thumbnail GET is a failure and is never cached: without
      the token DSM answers HTTP 200 with a JSON error body (plan 001).
- [x] Thumbnail size chosen per grid density (`MEDIUM` in the grid); `LARGE` is defined for
      the viewer, which is built in plan 005. All three sizes are in `ThumbnailSize`.
- [x] Failure placeholder that does not look like an empty day.

### Screen

- [x] Year strip, newest year first, each year showing its count. Rendered as a full-width
      section header per year inside the one grid, newest first, with the count; there is no
      separate horizontal jump-to-year strip, which the acceptance criterion does not require.
- [x] Grid per year, lazy, stable keys.
- [x] Header: the day, and the fallback distance when there is one.
- [x] Empty and error states that distinguish no session, no network and no photos. No session
      shows the sign-in screen (`AppRoot`); no photos is the distinct `NoPhotos` state; a failed
      fetch shows DSM's mapped text per year and a screen-level refresh error.
- [x] Pull to refresh triggers an index refresh, not just an item refetch.

## Extensions after the plan, 2026-09-02

Added at the owner's request, beyond the plan's scope:

- **Browsing other days.** Previous/next arrows step one calendar day (wrapping the year,
  29 February included, via `MonthDay.nextDay`/`previousDay`), and tapping the title opens a date
  picker to jump to any day; only its month and day are used. The shown day is explicit state in
  `DayViewModel`; the auto pick (today, or nearest with photos) is the default. A chosen day with
  no photos shows an empty state, still browsable.
- **Czech throughout.** All UI strings are Czech, with correct plurals for photo and day counts.
  The day title is formatted "9. září" (day number, genitive month) by `MonthDay.czech()`.

Both are covered by `DayStepTest` (day stepping and the Czech format) and verified on device:
the arrows step 2. → 3. září, the picker jumps to 15. září, and the counts read "7 fotek",
"3 fotky", "154 fotek" correctly.

## On-device verification, 2026-09-02

Verified on the Vivo V2145 against the live NAS: the sign-in screen, then the day grid with real
thumbnails, year sections newest first ("2024, 7 photos" above "2023, 11 photos"), and the
`X-SYNO-TOKEN` GET serving images. Sign-out returned to a prefilled sign-in with the password
cleared and left the Coil disk cache in place. Screenshots are in the session scratchpad, not
committed.

## Acceptance criteria

- [x] A day with photos in several years renders each year in its own section, newest first.
- [x] Signing out and back in does not re-download thumbnails already on disk.
- [x] Scrolling a day with hundreds of photos in one year does not load them all at once.
