# On This Day - product specification

The authority on requirements. Plans implement sections of this file and cite them.

## 1. What the app is

An Android app that shows the photos a person took on today's calendar date, in every year
their Synology Photos library covers. A vertical cut through the years rather than a browser:
one day, many years, newest year first.

When today holds nothing, the app shows the calendar day nearest to today that does hold
something, and says which day it is showing and how far away it is. An empty screen is a
failure state; a nearby day is the product.

The library lives in Synology Photos on the household NAS. Each person signs in with their own
Synology account, so what they see is what Synology already decided they may see. The app adds
no permission model of its own.

## 2. Safety rules

These override convenience. Violating one is a defect whether or not a test caught it.

- **Synology Photos is read-only.** The app calls no write, rename, delete, upload, share or
  settings endpoint, and does not call an endpoint whose effect is unknown.
- **Only allowlisted API calls exist.** An `(api, method, version)` triple not on the allowlist
  throws before the request is built. A blocklist would not do: some Photos read methods use
  POST, so the HTTP verb cannot classify safety, and an unknown endpoint must be refused rather
  than attempted.
- **Never log a response body.** Album and sharing responses carry share passphrases and live
  `sharing_link` values, which are working credentials that let anyone view an album from the
  internet without signing in. Redaction by field name is not enough, because the field names
  are not all known. Log status codes, error codes and call names only.
- **Never log or store the account password.** It is held in memory for the duration of one
  login call and then dropped. It is not written to disk, not put in a URL, and not passed as a
  process argument.
- **Never send credentials over anything but HTTPS with a publicly trusted certificate.** No
  cleartext exception, no "trust all certificates" client, not even behind a build flag.
- **Photos of one account never survive into another.** Signing out, or signing in as a
  different account, clears the day index and the image cache before the first byte of the new
  account's data is shown.

## 3. Whose photos, and which space

Two Photos namespaces are read and their results merged:

| Namespace | Space | Contains |
| --- | --- | --- |
| `SYNO.Foto.*` | the signed-in account's personal space | photos only that account can see |
| `SYNO.FotoTeam.*` | the shared space | photos the household shares, filtered by DSM permissions |

A photo belongs to exactly one space, because the folder holding it belongs either to a person
or to the shared space. So the merge is a union with no expected overlap. A file uploaded twice
appears twice, which is honest: they are two files on disk.

## 4. Signing in

- Synology account credentials, entered in the app. `SYNO.API.Auth` `login`.
- Two-factor codes supported. The trusted-device id the login returns is kept, so the code is
  asked for once per install rather than once per session.
- The session id is kept. The password is not: when DSM ends the session, the app asks for the
  password again. There is no refresh token in DSM for a third-party client, so the choice is
  between storing a password and asking for one, and the app asks.
- A failed login is never retried automatically. DSM auto-block bans the device's address after
  a few failures, and a retry loop would lock the household out of the NAS.
- One account per install (section 10).

## 5. Reaching the NAS

HTTPS to a DDNS hostname with a Let's Encrypt certificate issued through DSM, so Android's
default trust store validates it and the app needs no certificate handling of its own.

The base URL, host and port are configuration entered once, so a local address still works
during development.

Amended by decision 004 on 2026-09-02: in practice the hostname is on the owner's own domain
and TLS terminates at an existing reverse proxy in front of DSM. The app is unchanged by this.

## 6. The daily cut

### 6.1 The day histogram

The timeline endpoint answers "which calendar days hold photos, and how many", per namespace,
as year, month, day and count. It is fetched once, stored on the device, and refreshed on a
schedule. Every subsequent question about days is answered locally.

The day fields come from Photos itself, already resolved to calendar days. The app therefore
inherits Photos' own day boundaries and performs no timezone arithmetic on photo timestamps.
Only "what is today" is a local question, and it is answered in the device's zone.

### 6.2 Choosing the day

- Target is today's month and day. Year is ignored: the same month and day in any year matches.
- If no year holds that month and day, the nearest month and day that holds anything wins.
- Distance ignores the year and wraps at the year boundary, so on 2 January a 30 December photo
  is three days away, not 362.
- 29 February is a day in its own right. Only leap years hold it.
- Ties go to the past. A day that already happened reads as a memory; a day that has not reads
  as a bug.
- The screen states which day it is showing whenever that is not today.

### 6.3 Fetching a day's photos

The item list endpoint pages by `offset` and `limit` over a list sorted by taken time. The
histogram's running total is therefore the offset of any given day: a day at cumulative
position 41,320 holding 12 photos is `offset=41320, limit=12`.

This is deliberate. It means the app does not depend on the item endpoint accepting a time
range, which is undocumented and version-sensitive. Running totals are computed per namespace,
because each namespace is its own list.

An overlap read of one item either side of the window verifies the arithmetic; a mismatch falls
back to a wider read and filters on the returned taken time.

Plan 001 found that the range parameter does exist (`start_time`, `end_time`, U3) and verified
the arithmetic above once. Whether the range replaces the arithmetic is decision 005's pending
amendment, after the next observation run settles the boundary semantics.

## 7. Local storage

- Day histogram, per namespace: year, month, day, count, running total.
- Item rows for the days the user has opened: unit id, taken time, thumbnail cache key, plus
  what the grid needs.
- Session id and trusted-device id.
- Thumbnails on disk, keyed by unit id and size, never by a URL that carries a session id.

Everything above is scoped to one account and cleared when the account changes.

The cache exists so that the app opens to yesterday's answer while the network call runs, and so
that an expired session shows the last day rather than a blank screen behind a login prompt.

## 8. Screens

1. **Sign in.** Host, account, password, optional code. Errors state what DSM returned.
2. **Day.** The chosen day. A year strip, newest first; a grid of that year's photos under each
   year. A header naming the day, and naming the distance when it is a fallback.
3. **Viewer.** Fullscreen pager over the day's photos across all years, with the year and time
   visible, and a download of the original.
4. **Settings.** Base URL, refresh policy, sign out, cache size and a way to clear it.

## 9. Non-goals

Albums, search, faces, places, editing, upload, sharing, video playback beyond what the system
player does with a downloaded file, and any write to the NAS. Not in this app.

## 10. Decided

Recorded in `documents/decisions/`, and summarised here because they shape every plan:

- The Photos web API is the only data source. The Photos database is unreachable from a phone
  and carries no notion of a signed-in viewer.
- Personal and shared space, merged.
- Session id kept, password never stored; re-prompt on expiry.
- DDNS with a publicly trusted certificate.
- One account per install.

## 11. Unknowns

Answered by plan 001 on 2026-09-02 against Photos 1.9.1-10928. The detail, shapes and the
allowlist are in [`documents/research/photos-web-api.md`](../research/photos-web-api.md).
Nothing that depends on a still-open item may be guessed.

- **U1** Answered. `SYNO.API.Auth` 1-7, `Browse.Timeline` 1-6, `Browse.Item` 1-7, `Thumbnail`
  1-2, in both namespaces. The `(api, method, version)` allowlist is written down.
- **U2** Answered. `Browse.Timeline` `get` v6 returns `data.section[]`: pages of about a hundred
  items, each listing its days as `year, month, day, item_count`. A day larger than a page
  repeats across sections with its full count, so days are deduplicated when flattened.
  Flattened, the histogram sums exactly to the item count, in both namespaces.
- **U3** Answered: yes. `start_time` and `end_time`, epoch seconds. `time_start` and `time_end`
  are silently ignored. Whether the ends are inclusive is open until the next run, and decision
  005 is reconsidered then.
- **U4** Answered. `Thumbnail` `get` v2 as a GET with `id, cache_key, type=unit, size, _sid`
  serves JPEG bytes, but only with the `X-SYNO-TOKEN` header; without it the same URL returns a
  JSON error with HTTP 200. Whether the session can travel in a cookie instead of the query
  string is open until the next run.
- **U5** Answered. `time` is seconds. `indexed_time` is milliseconds.
- **U6** Dropped by the owner, unanswered: no restricted account exists in this household's use.
  Decision 002 records the acceptance.
- **U7** Answered. Days are the UTC calendar date of `time` on 35 of 35 days checked; the
  Prague date disagrees on 4. `time` is the camera's wall clock stored as if it were UTC (a
  photo taken at 20:22 local carries 20:22 UTC), so a Photos day is the date the photo was taken
  on wherever it was taken. The app derives days and clock readings from `time` as UTC and uses
  the device zone only to decide what today is.

## 12. Phases

| Phase | Plan | Depends on |
| --- | --- | --- |
| 1 | API observation, unknowns U1 to U7 answered and written down | nothing |
| 2 | Project foundation, sign-in, session handling | 1 |
| 3 | Day histogram, storage, day selection | 1 |
| 4 | Day screen, item paging, thumbnails | 2, 3 |
| 5 | Viewer, original download, cache lifecycle, hardening | 4 |

Phase 3's logic is pure and needs no NAS, so it is testable before phase 2 exists.
