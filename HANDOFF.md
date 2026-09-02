# Handoff - 2026-09-02

Written before a machine restart. Read this, then `CLAUDE.md`, then
`documents/decisions/index.md` and `documents/plans/index.md`.

Nothing is in flight. No process was left running, no file half-written, no NAS state touched.
The repo builds and its tests pass as committed.

## What this project is

`~/git/synology-photos-onthisday` - an Android app called **On This Day**. It shows the photos
the signed-in person took on today's calendar date in every year their Synology Photos library
covers, and when today holds nothing it shows the nearest day that does.

It was designed in a session that started from `~/git/synology-photos-companion` and concluded
that companion's access model (direct PostgreSQL over a Unix socket on the NAS) cannot serve a
phone and carries no notion of a signed-in viewer. This app talks to the Photos **web API**
instead, with each person's own Synology session, so Synology enforces per-account access and
the app implements no permission logic. That is
[decision 001](documents/decisions/001-web-api-is-the-only-source.md).

## What exists

Buildable skeleton, complete documents, one unrun script.

| Area | State |
| --- | --- |
| Gradle build | Works. `./gradlew testDebugUnitTest assembleDebug` is green, 6 tests pass, APK 20 MB |
| App shell | `OnThisDayApp`, `MainActivity` with a placeholder screen, Compose theme from the icon palette |
| Launcher icon | Adaptive, one vector petal rotated four times, three warm and one white |
| Day-selection logic | **Written and tested.** `core/DayIndex.kt` plus `DayIndexTest.kt` |
| Product spec | `documents/plans/plan.md`, 12 sections |
| Plans | 001-005 in `documents/plans/`, index with the dependency graph |
| Decisions | 001-007 in `documents/decisions/`, index with three open questions |
| Observation script | `scripts/observe-photos-api.sh` and `scripts/summarise-observation.py`. Run end to end against a local TLS mock of `entry.cgi`; three defects that would have wasted the real run are fixed in `a7e45a5`. **Never run against the NAS** |

`core/DayIndex.kt` is the heart of the product and it is pure: exact match across years, else
the nearest calendar day, wrapping at the year boundary, ties to the past, 29 February as a day
of its own. It needed no NAS to write and needs none to change.

## What happens next

**Plan 001, the API observation pass.** Everything else is blocked on it, and nothing in it may
be guessed: these Photos endpoints are undocumented and version-sensitive.

```bash
cd ~/git/synology-photos-onthisday
./scripts/observe-photos-api.sh                    # asks for base URL, account, password, 2FA code
# or: ./scripts/observe-photos-api.sh https://host:5001
```

It calls read endpoints only, logs out on exit, redacts every response before writing, and
writes to a gitignored `documents/research/observation-<timestamp>/`. One login attempt, never
retried, because DSM auto-block would ban the machine. It ends by running
`scripts/summarise-observation.py` on the directory, which prints the U1-U7 answers and the
decision 005 offset check; that summariser can be rerun on the kept directory at any time.

Two things gate the run:

- **A certificate curl trusts.** The base URL must be the DDNS hostname carrying the Let's
  Encrypt certificate from decision 004. DSM's self-signed certificate on the LAN address fails
  verification before the login is attempted, and the script has no insecure switch on purpose.
- **U6 needs a second run** as the restricted account (`test-user`, which owns nothing).
  Compare `item-count-FotoTeam.json` between the two directories: equal counts mean the shared
  space ignores folder permissions.

Then the findings get written into `documents/research/photos-web-api.md` (committed) and
`plan.md` §11 gets amended with the answers. The seven unknowns are U1 to U7 in that section:
which apis and versions exist, whether a timeline endpoint exists and its shape, whether the
item list takes a time range, the thumbnail endpoint's parameters and whether a plain GET
serves bytes, whether taken time is seconds or milliseconds, whether `SYNO.FotoTeam.*` filters
by folder permission, and which timezone the timeline's day fields use.

After that, plans 002 (sign-in) and 003 (histogram and storage) can run in either order.

## What needs you rather than me

1. **NAS prerequisites for off-LAN use**, once, by hand in DSM: a DDNS hostname, a Let's
   Encrypt certificate for it, and a reverse proxy or forwarded port. Until then the app can be
   developed against a LAN address, which is why the base URL is configuration.
   ([decision 004](documents/decisions/004-access-path-and-tls.md))
2. **Running the observation script.** It needs your Synology password typed in, so it is not
   something I run for you.
3. **A release keystore**, when a release build is first wanted. `CLAUDE.md` has the alias and
   the property names, and the warning about backing it up.

## Decisions already made, so they are not reopened

- Photos web API is the only source. No backend, nothing deployed to the NAS.
- Personal **and** shared space, merged. Running totals stay per namespace.
- Session id stored, password never. Expiry re-prompts. Two-factor device id kept.
- HTTPS to DDNS with a real certificate. No pinning, no cleartext, no TLS code.
- The day histogram lives in Room and answers day questions offline. A day's photos are fetched
  by an offset computed from running totals, so no undocumented time filter is required.
- One account per install. An account change wipes the index and every cache first.
- Photos' own calendar-day boundaries are authoritative, so the app does no timezone
  arithmetic on photo timestamps.

## Two things worth not forgetting

**The offset trick is the load-bearing idea.** The timeline histogram is in the same taken-time
order as the item list, so the running total before a day is that day's `offset`. It means the
app never needs a time-range parameter that may not exist. The cost is that an upload shifts
every total after it, so a stale index yields a wrong offset rather than a missing photo, which
is why plan 004 reads one item either side of the window and verifies.

**Sharing responses are live credentials.** An album response carries the share passphrase and
a working `sharing_link`. That is why no response body is ever logged and why observation
output is gitignored. The original finding is in companion's
`documents/research/api-observation.md`.
