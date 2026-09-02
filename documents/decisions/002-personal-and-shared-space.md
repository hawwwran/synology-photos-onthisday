# 002 - Personal and shared space, merged

- **Status:** Accepted
- **Date:** 2026-09-02
- **Origin:** Chosen (project owner, 2026-09-02)

## Context

Photos splits its API by space rather than by parameter: `SYNO.Foto.*` is the signed-in
account's personal space, `SYNO.FotoTeam.*` is the shared space. "The photos I took that day"
could reasonably mean either, or both.

## Decision

Both, merged into one day view.

A photo belongs to exactly one space, because the folder holding it belongs either to a person
or to the shared space (`folder.id_user` is 0 for shared, else a `user_info.id`). So the merge
is a union and no deduplication is expected. A file uploaded to both spaces is two files on
disk and shows twice, which is the honest answer.

## Consequences

- Every call is made twice, once per namespace. Two timelines, two running totals, two paged
  item lists per day.
- **Running totals must stay per namespace.** Each namespace is its own sorted list, so a
  merged running total would compute an offset into a list that does not exist. This is the one
  place where the merge leaks into the design, and it is the easiest thing here to get wrong.
- Shared-space visibility is per-account in principle, but whether `SYNO.FotoTeam.*` actually
  filters by folder permission is unknown (plan.md U6). If it does not, a household member sees
  shared photos they would not see in Photos itself. That is a correctness question about the
  API, not about the merge, and plan 001 answers it before any UI ships.
- Merging at render rather than in storage keeps the namespace attached to every row, which is
  what paging needs anyway.

## Alternatives considered

- **Personal space only.** Simplest, one namespace. Rejected: on this household's NAS the
  shared space is where most family photos live, so the daily cut would be nearly empty.
- **Shared space only.** Rejected: then per-account sign-in would only affect folder
  permissions, and the app's premise is a personal cut.

## Related

[[001-web-api-is-the-only-source]], [[005-day-index-on-device]]
