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
| [007](007-session-lifetime-and-thumbnail-cache.md) | Session lifetime and the thumbnail cache | Code done 2026-09-03; device checks pending | 005 | 12 / 14 |
| [008](008-day-index-and-item-cache.md) | Day index and item cache correctness | Code done 2026-09-03; instrumented tests and device check pending | 005 | 9 / 12 |
| [009](009-likes-hardening.md) | Likes hardening | Code done 2026-09-03; device check pending | 007, 008 | 12 / 13 |
| [010](010-viewer-save-share-and-update.md) | Viewer, save, share and the update flow | Not started (code review 2026-09-03) | 005 (run after 007) | 0 / 14 |
| [011](011-documentation-logging-and-dead-code.md) | Documentation, the logging rule and dead code | Not started (code review 2026-09-03) | 007-010 | 0 / 14 |

```
001 ─┬─> 002 ─┐
     └─> 003 ─┴─> 004 ──> 005 ──> 006
                           │
                           ├─> 007 ─┬─> 009 ─┐
                           ├─> 008 ─┘        ├─> 011
                           └─> 010 ──────────┘
```

## Code review follow-up, 2026-09-03

Plans 007-011 come from a whole-project review run after v1.0.0. Execution order: **007, 008, 009,
010, 011.** 007 holds the two most severe findings (a stale view model that signs the new session
out, and the thumbnail cache storing DSM's JSON error envelope). 008 owns the schema bump to
version 5 and the end of destructive migration; 009 relies on it. 010 edits `DayHost`, which 007
also edits, so it follows 007. 011 records the final state and adds the logging-rule test, then a
release is cut.

Owner decisions the plans leave open, with the default each plan takes if unanswered: minSdk 29
versus keeping 26 with a runtime permission (010, default 29); which rose colour is right, icon or
theme (011, default the icon's); keep or drop the grid double-tap (010, default keep and comment).

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
