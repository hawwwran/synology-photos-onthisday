# Plans

Numbered one per phase of `plan.md` §12. Plan numbers are not the execution order; the
dependency column is.

| Plan | Title | Status | Depends on | Progress |
| --- | --- | --- | --- | --- |
| [001](001-api-observation.md) | API observation | Done, U6 dropped | nothing | 8 / 9 |
| [002](002-foundation-and-auth.md) | Foundation and sign-in | Not started | 001 | 0 / 14 |
| [003](003-day-index.md) | Day histogram and day selection | Partly done | 001 | 3 / 12 |
| [004](004-day-screen.md) | Day screen, paging, thumbnails | Not started | 002, 003 | 0 / 14 |
| [005](005-viewer-and-hardening.md) | Viewer, download, hardening | Not started | 004 | 0 / 11 |

```
001 ─┬─> 002 ─┐
     └─> 003 ─┴─> 004 ──> 005
```

Plan 003's pure logic is already written and tested (`core/DayIndex.kt`), which is why it starts
partly done.

Plan 001 ran on 2026-09-02 and answered everything but U6, which the owner dropped: no
restricted account exists in this household's use, so the question has no one to affect. Plans
002 and 003 are unblocked; `documents/research/photos-web-api.md` is the shape they build
against.

## Working rules

Same protocol as the `synology-photos-companion` repo, which this repo deliberately mirrors:
tick a box only when the work is done and verified, keep the `Progress:` header in step, one
commit per coherent group of ticks, and append `> Blocked: <reason>` under anything that cannot
be finished rather than ticking it with a caveat.
