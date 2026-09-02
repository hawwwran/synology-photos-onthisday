# Handoff - 2026-09-02, end of day

Read this, then `CLAUDE.md`, then `documents/decisions/index.md` and `documents/plans/index.md`.

Nothing is in flight. No process was left running, no file half-written, no NAS state touched
beyond read calls and a logout. The repo builds and its tests pass as committed.

## What this project is

`~/git/synology-photos-onthisday` - an Android app called **On This Day**. It shows the photos
the signed-in person took on today's calendar date in every year their Synology Photos library
covers, and when today holds nothing it shows the nearest day that does.

It talks to the Photos **web API** with each person's own Synology session, so Synology enforces
per-account access and the app implements no permission logic. That is
[decision 001](documents/decisions/001-web-api-is-the-only-source.md). The sibling repo
`~/git/synology-photos-companion` reads the Photos database directly; no code moves between them.

## What exists

| Area | State |
| --- | --- |
| Gradle build | Works. `./gradlew testDebugUnitTest assembleDebug` is green, 6 tests pass |
| App | Full path to the day screen: sign-in, day index in Room, and the day grid with per-year sections and Coil thumbnails over `X-SYNO-TOKEN`. Verified on the Vivo V2145 against the live NAS |
| Launcher icon | Adaptive, one vector petal rotated four times, three warm and one white |
| Day-selection logic | Written and tested. `core/DayIndex.kt` plus `DayIndexTest.kt` |
| Product spec | `documents/plans/plan.md`. §11 now carries the answers |
| Plans | 001-004 done. 005 is 9/11: viewer, settings, hardening done; download and release signing blocked |
| Decisions | 001-007. 002, 004 and 005 amended today; Q1, Q3, Q4 closed |
| **API research** | **`documents/research/photos-web-api.md`**, written from a real run today. The shape every later plan builds against |
| Observation tooling | `scripts/observe-photos-api.sh` and `scripts/summarise-observation.py`, both exercised against a local TLS mock, then run once for real |

The raw capture is in `documents/research/observation-2026-09-02-135141/`, gitignored, on this
machine only. The summariser can be rerun on it at any time.

## What the run found, in one paragraph

Photos 1.9.1-10928 on DSM 7.3.2. The timeline (`Browse.Timeline` `get` v6) returns the whole
library's day histogram as pages of about a hundred items, each listing its days; a day bigger
than a page repeats across pages with its full count, so days are deduplicated when flattened,
after which the histogram sums exactly to the item count in both namespaces (25,149 personal,
77,436 shared; 1,936 and 3,330 distinct days). The offset arithmetic from decision 005 was
checked live and held, then was retired: the item list **does** accept `start_time`/`end_time`
in epoch seconds (and silently ignores `time_start`/`time_end`), so a day is fetched by range. Thumbnails come from a GET, but only with the
`X-SYNO-TOKEN` header; without it the same URL returns a JSON error with HTTP 200. `time` is
seconds, `indexed_time` milliseconds. Timeline days are the UTC calendar date of `time`, and
`time` is the camera's wall clock stored as if UTC (owner confirmed a 20:22 photo carries 20:22
UTC), so a day is the date the photo was taken on wherever it was taken. The login's
trusted-device field is `device_id`, not `did`.

## What happens next

1. **One owner run of `scripts/observe-photos-api.sh`** settles the last unknowns: the
   `SYNO.Foto.Download` parameter and version (needed to switch the save from the `xl` rendition
   to the byte-original), whether the thumbnail GET works with a cookie-borne session, whether
   `end_time` is inclusive, and whether `device_id` returns without a two-factor code. All are
   reads; the download probe discards the body.
2. **Create the release keystore** (`keystore.jks` + the `OTD_*` gradle properties, see
   `CLAUDE.md`), then `assembleRelease` and confirm a release APK installs over a debug install.
   These close plan 005's two blocked items.
3. **Everything else in plan 005 is done and unit-tested**: the viewer, the save-to-gallery of
   the largest rendition, settings (base URL, refresh policy, cache size and clear, sign out),
   and the §2 hardening tests.

```bash
cd ~/git/synology-photos-onthisday
./scripts/observe-photos-api.sh https://nas.homedog.cz      # asks account, password, 2FA code
./scripts/summarise-observation.py documents/research/observation-<stamp>/
```

## How the NAS is reached

Not in the repo on purpose, in case it goes public. The base URL is in the memory directory of
the Claude session (`nas-access-path`), and the owner knows it. In shape: a hostname on the
owner's domain, TLS terminated by an nginx in an LXC container on the router with automatic
Let's Encrypt renewal, reverse-proxying to DSM on the LAN. Decision 004 records the topology
without the names.

## Decisions already made, so they are not reopened

- Photos web API is the only source. No backend, nothing deployed to the NAS.
- Personal **and** shared space, merged. Every call once per namespace; rows keep their namespace.
- Session id stored, password never. Expiry re-prompts. Trusted-device id kept. Built in plan 002.
- Viewer, save-to-gallery, settings and the §2 hardening tests are built (plan 005).
- HTTPS to a real certificate through the router's reverse proxy. No pinning, no cleartext, no
  TLS code. The auto-block-sees-the-proxy exposure is accepted (Q4).
- The day histogram lives in Room and answers day questions offline (built in plan 003). A day's
  photos are fetched by `start_time`/`end_time`; the offset arithmetic was verified once and retired.
- One account per install. An account change wipes the index and thumbnail cache first; a
  same-account sign-out keeps the thumbnail cache so a re-login does not re-download (006 amended).
- Photos' own calendar-day boundaries are authoritative: the UTC date of `time`.

## Three things worth not forgetting

**Sharing responses are live credentials.** An album response carries the share passphrase and
a working `sharing_link`. No response body is ever logged and observation output is gitignored.
No album call has been made from this repo and none is needed.

**The token is mandatory, including for images.** Coil's fetcher attaches `X-SYNO-TOKEN`, and
treats a non-image content type as a failure, or an error document gets cached as a picture.

**The summariser is the one that knows the timeline shape.** `buckets_of()` flattens the
sections and deduplicates repeated days; anything that reads a timeline capture should go
through it rather than reinvent the walk.
