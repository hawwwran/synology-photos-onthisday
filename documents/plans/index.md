# Plans

Numbered one per phase of `plan.md` §12 to begin with; 007 and up are follow-ups and their numbers
are only order of writing. Plan numbers are not the execution order; the dependency column is.

A plan whose every box is ticked is deleted, not kept as a monument: what it decided lives in
`documents/decisions/`, what it learned about the API in `documents/research/photos-web-api.md`,
and what it did in the commits it names. `git log -- documents/plans` brings any of them back.

## Live

| Plan | Title | Status | Depends on | Progress |
| --- | --- | --- | --- | --- |
| [006](006-likes.md) | Liking photos, stored on the NAS | Built and verified; one acceptance item needs a second device | 005, decision 008 | 16 / 21 |
| [007](007-session-lifetime-and-thumbnail-cache.md) | Session lifetime and the thumbnail cache | Done bar one check that needs a session killed on the NAS side | 005 | 13 / 14 |
| [008](008-day-index-and-item-cache.md) | Day index and item cache correctness | Done; two live checks parked (a date edited in Photos, the 1,220-item day) | 005 | 12 / 12 |
| [012](012-likes-sync-502-on-a-second-account.md) | The likes sync answers HTTP 502 on a second account | Evidence collected 2026-09-04, diagnosis next | 009 | 1 / 9 |

## Done, and where the record went

| Plan | Title | Finished | The record |
| --- | --- | --- | --- |
| 001 | API observation | 2026-09-02, U6 dropped (no restricted account in this household) | `documents/research/photos-web-api.md` is the whole output |
| 002 | Foundation and sign-in | 2026-09-02 | decisions 003, 004, 007 |
| 003 | Day histogram and day selection | 2026-09-02 | decision 005; `core/DayIndex.kt` and its tests |
| 004 | Day screen, paging, thumbnails | 2026-09-02 | decisions 002, 006; research U2, U4 |
| 005 | Viewer, download, hardening | v1.0.0 cut 2026-09-03 | the safety mapping below; `CLAUDE.md` "Release signing" |
| 009 | Likes hardening | 2026-09-03, device-checked | decision 008's 2026-09-03 amendment |
| 010 | Viewer, save, share and the update flow | 2026-09-03, device-checked | decision 004's minSdk amendment; open question Q5 (Play Protect) |
| 011 | Documentation, the logging rule and dead code | 2026-09-03 | `LoggingRuleTest`, and every doc it corrected |

```
001 ─┬─> 002 ─┐
     └─> 003 ─┴─> 004 ──> 005 ──> 006
                           │
                           ├─> 007 ─┬─> 009 ──> 012
                           ├─> 008 ─┘
                           └─> 010
```

## Safety rules, mapped to what enforces them

From plan 005, kept here because `CLAUDE.md` sends every session to this file and the mapping is
how a reader checks that plan.md §2 is still true.

- **Read-only Photos.** `HardeningTest`: every allowlisted method is a read verb, and no
  allowlisted api or method name implies a write.
- **Only allowlisted triples.** `SynologyClient.call` calls `Allowlist.require` before building a
  request; `SynologyClientTest` asserts a triple off the list never reaches the network.
- **Never log a response body.** `LoggingRuleTest` allows `android.util.Log` only in `ApiLog`,
  `IndexLog` and `UpdateLog`, and asserts none of their functions takes free text; `HardeningTest`
  asserts failure messages carry the call name and a code and never body fields.
- **Never store the password.** `SessionStore` has no password key; `SessionManagerTest` and a
  source search confirm no path writes one.
- **HTTPS with a trusted certificate.** No `usesCleartextTraffic`, no network-security-config, no
  custom trust manager (manifest, `AppGraph`); `parseBaseUrl` refuses `http://` (`BaseUrlTest`),
  and minSdk 29 means the platform blocks cleartext on every supported level.
- **An account change wipes everything first.** `SessionManager` wipes the index, the thumbnail
  cache and the likes-folder setting on a change of `(base URL, account)` before the new account's
  data shows; `SessionManagerTest` covers all three groups and the same-account case that must not
  wipe.
- **A failed login is never retried.** `AuthApi.login` is one call, `SessionManagerTest` asserts one
  request per attempt, and no retry wraps it. A retry for the likes sync (plan 012) must not change
  this.

## Working rules

Same protocol as the `synology-photos-companion` repo, which this repo deliberately mirrors: tick a
box only when the work is done and verified, keep the `Progress:` header in step, one commit per
coherent group of ticks, and append `> Blocked: <reason>` under anything that cannot be finished
rather than ticking it with a caveat.

Releases are cut by `release-photos-onthisday.sh` (see `CLAUDE.md`), which pushes its
`chore(release)` bump commit to `main` along with the tag. A session that starts after a release
has to `git fetch` first, or it builds from the previous version.
