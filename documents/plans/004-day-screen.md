# 004 - Day screen, paging, thumbnails

- **Status:** Not started
- **Source:** plan.md §6.3, §8.2
- **Depends on:** 002, 003
- **Blocks:** 005
- **Decisions:** [005](../decisions/005-day-index-on-device.md)
- **Progress:** 0 / 13

## Goal

The chosen day rendered: a year strip newest first, a photo grid per year, thumbnails loading
from the NAS through the signed-in session, and a header that says which day is on screen and
how far it is from today when it is not today.

## Tasks

### Items

- [ ] Item list call by `offset` and `limit`, per namespace, per year.
- [ ] Offsets from the running totals rather than a time-range parameter.
- [ ] Overlap read of one item either side, with a wider-read fallback when taken times
      disagree with the window.
- [ ] Item rows cached per opened day.
- [ ] Videos handled per whatever plan 001 found about the item type field: shown with a badge
      or filtered, decided once and written down.

### Thumbnails

- [ ] Coil fetcher that adds the session id, with a cache key of unit id plus size so a new
      session does not invalidate the disk cache.
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
