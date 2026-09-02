# 005 - The day index lives on the device, and paging uses running totals

- **Status:** Accepted
- **Date:** 2026-09-02
- **Origin:** Chosen

## Context

Two questions drive every screen. Which calendar days hold photos, and how do you fetch one
day's photos out of a library of a quarter of a million items.

The first has an answer in the API: the timeline endpoint reports days as year, month, day and
count. The second does not, reliably: the item list pages by `offset` and `limit`, and whether
it accepts a time range is unknown and version-sensitive (plan.md U3).

## Decision

**The day histogram is fetched once, stored in Room, and every day question is answered
locally.** Nearest-day search never touches the network.

**A day's photos are fetched by offset, computed from the histogram's running totals.** The
histogram is in the same taken-time order as the item list, so the running total before a day
*is* that day's offset: a day at cumulative position 41,320 holding 12 photos is
`offset=41320, limit=12`. Running totals are kept per namespace, because each namespace is its
own list ([[002-personal-and-shared-space]]).

An overlap read of one item either side verifies the arithmetic. On a mismatch the app widens
the read and filters on the returned taken time rather than trusting the offset.

**Photos' own day boundaries are authoritative.** The timeline returns calendar days already
resolved, so the app does no timezone arithmetic on photo timestamps. Only "what is today" is
answered locally, in the device's zone.

## Consequences

- Nearest-day fallback is instant and works offline, which is what makes an empty day a
  non-event rather than a spinner. This is the reason for the decision.
- No dependency on an undocumented time filter. If plan 001 finds one, it becomes an
  optimisation, not a rewrite.
- No timezone bug class at all. The alternative was choosing between the NAS zone, the device
  zone and the photo's own offset, and being wrong for photos taken near midnight. Inheriting
  Photos' answer means the app shows exactly what Photos shows, which is also what the user
  expects when they cross-check.
- The histogram goes stale. A photo uploaded after the last refresh is invisible until the next
  one, and worse, an upload *shifts every running total after it*. So a refresh has to rewrite
  the totals, and a stale total is a wrong offset rather than a missing photo. The overlap read
  is what catches that, and it is the reason it exists.
- Room holds one row per day with photos, so hundreds to low thousands of rows. Item rows are
  cached only for days actually opened.

## Alternatives considered

- **Query the item list per year with a time range.** One call per year, no arithmetic.
  Rejected as the primary route because the parameter may not exist on this Photos version; it
  is the first thing to reconsider if plan 001 finds it.
- **Binary search over offsets by taken time.** Works with only `offset` and `limit`, needs no
  histogram. Rejected: the histogram is one call and gives exact offsets, while a binary search
  is a dozen calls per day and still needs the day list from somewhere.
- **Fetch everything and index locally.** A quarter of a million rows over a phone connection.
  Rejected on the obvious grounds.

## Amendments

- 2026-09-02: **a day's photos are fetched by time range, not by offset.** Plan 001 found
  `Browse.Item` `list` honours `start_time` and `end_time` in epoch seconds, compared against
  `time`, and verified the offset arithmetic above once against the live list before retiring
  it. The fetch for one calendar day, per namespace, is `start_time` = that day's midnight
  rendered as UTC (`time` is the camera's wall clock stored as if UTC, research U7), `end_time` =
  `start_time + 86399`, `sort_by=takentime`, paged by `offset`/`limit` inside the range for a
  day larger than a page. Running totals, the offset computation and the overlap read are
  dropped; a stale histogram now means a missing new photo, never someone else's photo. The
  histogram remains the source of which days exist and how many items each holds; a returned
  count that differs from `item_count` is logged and schedules a histogram refresh, nothing is
  retried. Whether `end_time` is inclusive was not verified. The owner accepted the consequence:
  a photo taken at exactly 23:59:59 may be missed. Chosen by the owner; this is the
  reconsideration the alternatives section asked for.

## Related

[[002-personal-and-shared-space]], [[006-one-account-per-install]]
