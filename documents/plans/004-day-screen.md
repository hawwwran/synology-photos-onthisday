# 004 - Day screen, paging, thumbnails

- **Status:** Not started
- **Source:** plan.md §6.3, §8.2
- **Depends on:** 002, 003
- **Blocks:** 005
- **Decisions:** [005](../decisions/005-day-index-on-device.md), amended 2026-09-02
- **Progress:** 0 / 14

## Goal

The chosen day rendered: a year strip newest first, a photo grid per year, thumbnails loading
from the NAS through the signed-in session, and a header that says which day is on screen and
how far it is from today when it is not today.

## Tasks

### Items

- [ ] Item list call by `start_time`/`end_time` for the day in each year, per namespace, sorted
      by taken time; `offset`/`limit` inside the range for a day larger than a page.
- [ ] Range bounds: `start_time` = the day's midnight rendered as UTC, `end_time` = start +
      86399. `time` is the camera's wall clock stored as if UTC, so no zone conversion anywhere.
- [ ] A returned count that differs from the histogram's `item_count` is logged and schedules a
      histogram refresh. Nothing is retried or widened.
- [ ] Item rows cached per opened day.
- [ ] Videos: `type` is `"photo"` or `"video"`, same fields and a ready thumbnail (plan 001).
      Shown in the grid with a badge; playback stays out of scope (plan.md §9).

### Thumbnails

- [ ] Coil fetcher that adds `_sid` and the `X-SYNO-TOKEN` header, which the GET requires, with
      a cache key of unit id plus size so a new session does not invalidate the disk cache.
- [ ] A non-image content type from the thumbnail GET is a failure and is never cached: without
      the token DSM answers HTTP 200 with a JSON error body (plan 001).
- [ ] Thumbnail size chosen per grid density; a larger size for the viewer.
- [ ] Failure placeholder that does not look like an empty day.

### Screen

- [ ] Year strip, newest year first, each year showing its count.
- [ ] Grid per year, lazy, stable keys.
- [ ] Header: the day, and the fallback distance when there is one.
- [ ] Empty and error states that distinguish no session, no network and no photos.
- [ ] Pull to refresh triggers an index refresh, not just an item refetch.

## Acceptance criteria

- [ ] A day with photos in several years renders each year in its own section, newest first.
- [ ] Signing out and back in does not re-download thumbnails already on disk.
- [ ] Scrolling a day with hundreds of photos in one year does not load them all at once.
