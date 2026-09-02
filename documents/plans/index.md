# Plans

Numbered one per phase of `plan.md` §12. Plan numbers are not the execution order; the
dependency column is.

| Plan | Title | Status | Depends on | Progress |
| --- | --- | --- | --- | --- |
| [001](001-api-observation.md) | API observation | Done, U6 dropped | nothing | 8 / 9 |
| [002](002-foundation-and-auth.md) | Foundation and sign-in | Done | 001 | 14 / 14 |
| [003](003-day-index.md) | Day histogram and day selection | Done | 001 | 12 / 12 |
| [004](004-day-screen.md) | Day screen, paging, thumbnails | Done | 002, 003 | 14 / 14 |
| [005](005-viewer-and-hardening.md) | Viewer, download, hardening | 10/11 (release build waits on keystore) | 004 | 10 / 11 |
| [006](006-likes.md) | Liking photos, stored on the NAS | Built, live test pending 2026-09-03 | 005, decision 008 | code complete |

```
001 ─┬─> 002 ─┐
     └─> 003 ─┴─> 004 ──> 005
```

001-004 are done. 005 is 10/11: viewer, settings, hardening and the original-file download are
done; only the release-signed build waits on a keystore.

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
