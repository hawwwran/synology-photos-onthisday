# The Synology Photos web API, as this app uses it

Observed 2026-09-02 against **Synology Photos 1.9.1-10928 on DSM 7.3.2**, through the reverse
proxy decision 004 describes, signed in as the library owner. One run of
`scripts/observe-photos-api.sh`; the raw captures are in the gitignored
`documents/research/observation-2026-09-02-135141/` on the development machine and nowhere
else. Field *names*, shapes and counts are quoted here. No value that could identify a photo,
a person or a session is.

These endpoints are undocumented and version-sensitive. Everything below is true of this
Photos build and should be re-observed, not assumed, on another.

## Call shape

Every call is `POST https://<host>/webapi/entry.cgi` with a form-encoded body:

```text
api=SYNO.Foto.Browse.Item&method=list&version=7&offset=0&limit=100
  &sort_by=takentime&sort_direction=desc
  &additional=["thumbnail","resolution","orientation"]
  &_sid=<session id>
```

plus the header `X-SYNO-TOKEN: <synotoken>`. `SYNO.API.Info` marks the Photos apis
`requestFormat: JSON`; in practice bare string values were accepted (`sort_by=takentime`) and
`additional` has to be a JSON array. The response envelope is

```json
{"success": true,  "data": { ... }}
{"success": false, "error": {"code": 120}}
```

Personal and shared space are separate namespaces, `SYNO.Foto.*` and `SYNO.FotoTeam.*`, with
identical methods and shapes. Everything below holds for both unless it says otherwise.

## U1. Apis and versions

`SYNO.API.Info` `query` (v1, `query=all`, no session needed) returned 970 apis, 73 of them
`SYNO.Foto.*` or `SYNO.FotoTeam.*`. The ones this app touches:

| Api | Versions | Used at |
| --- | --- | --- |
| `SYNO.API.Info` | 1 | 1 |
| `SYNO.API.Auth` | 1-7 | 7 |
| `SYNO.Foto.Browse.Timeline`, `SYNO.FotoTeam.Browse.Timeline` | 1-6 | 6 |
| `SYNO.Foto.Browse.Item`, `SYNO.FotoTeam.Browse.Item` | 1-7 | 7 |
| `SYNO.Foto.Thumbnail`, `SYNO.FotoTeam.Thumbnail` | 1-2 | 2 |

Present but not observed and therefore not allowlisted yet: `SYNO.Foto.Download` and
`SYNO.FotoTeam.Download` (1-2), which plan 005's download of the original will need, and
`SYNO.Foto.UserInfo` (1). The full 79-line list is `api-versions.txt` in the capture directory.

### The allowlist

Plan.md §2 requires that only these `(api, method, version)` triples can be built. Anything
else throws before a request exists.

| Api | Method | Version | Purpose |
| --- | --- | --- | --- |
| `SYNO.API.Info` | `query` | 1 | Confirm the versions below still exist before first use |
| `SYNO.API.Auth` | `login` | 7 | Sign in |
| `SYNO.API.Auth` | `logout` | 7 | Sign out |
| `SYNO.Foto.Browse.Timeline` | `get` | 6 | Personal day histogram |
| `SYNO.FotoTeam.Browse.Timeline` | `get` | 6 | Shared day histogram |
| `SYNO.Foto.Browse.Item` | `list` | 7 | Personal items of a day |
| `SYNO.FotoTeam.Browse.Item` | `list` | 7 | Shared items of a day |
| `SYNO.Foto.Browse.Item` | `count` | 7 | Cross-check the histogram total |
| `SYNO.FotoTeam.Browse.Item` | `count` | 7 | Cross-check the histogram total |
| `SYNO.Foto.Thumbnail` | `get` | 2 | Personal thumbnails, GET |
| `SYNO.FotoTeam.Thumbnail` | `get` | 2 | Shared thumbnails, GET |

Every method above is a read. No album, sharing, folder, upload, setting or download method was
called, so nothing here describes them.

## Signing in

`SYNO.API.Auth` `login` v7, body:

```text
account, passwd, format=sid, enable_syno_token=yes, enable_device_token=yes,
device_name=<app name>, otp_code=<code, only when the user has one>
```

Response `data` keys: `sid`, `synotoken`, `device_id`, `account`, `is_portal_port` (false),
`ik_message` (empty). Two things to carry into plan 002:

- The trusted-device field is **`device_id`**, not the `did` older write-ups use. Whether it
  carried a value on this login is not known: the capture redacts it by name, and the run
  did not record whether a two-factor code was entered. To be settled on the next run.
- The **synotoken is not optional** once `enable_syno_token=yes` is sent. A thumbnail GET
  carrying a valid `_sid` but no `X-SYNO-TOKEN` header received a 38-byte JSON error envelope
  instead of bytes; the same GET with the header received the image. Every call, including
  image loads, attaches the header. Whether a session opened *without* `enable_syno_token`
  needs no token was not tested.

`logout` v7 with `_sid` ends the session. Both calls are on `entry.cgi`.

DSM's error codes for `SYNO.API.Auth`, from Synology's published Web API guide rather than from
this run, which saw none: 400 wrong account or password, 401 account disabled, 402 permission
denied, 403 two-factor code required, 404 wrong two-factor code, 406 two-factor enforced, 407
address blocked by auto-block, 409 password expired, 410 password must be changed. Common codes
that mean "the session is gone" and should re-prompt: 105 insufficient privilege, 106 session
timeout, 107 session replaced by another login, 119 sid not found. Treat this list as the
mapping's starting point and let the app show the raw code alongside the plain-language text.

## U2. The timeline

`SYNO.Foto.Browse.Timeline` `get` v6 with no parameter beyond `_sid`. Adding
`timeline_group_unit=day` was accepted and produced a byte-identical response, so the default
is already days. Versions 1-5 were not probed.

Response shape:

```text
data.section[]            one entry per page of the item list, newest first
  .offset   int           0, or the position inside a day that spans pages (see below)
  .limit    int           number of items on that page
  .list[]                 the days on that page, newest first
    .year .month .day     calendar day, see U7 for whose calendar
    .item_count           items on that day, in total
```

Sections are how Photos' own web client pages the library: pages of about a hundred items
(median 111 personal, 114 shared, range 1 to 234) that end on a day boundary. A day with more
items than a page spans consecutive sections; it then appears in each of them with its **full**
`item_count`, is the only day in those sections, and the section's `limit` is its share of the
day. This is why the flattened histogram must take each `(year, month, day)` once.

Flattened that way, the histogram is complete and exact:

| | Personal | Shared |
| --- | --- | --- |
| Sections | 218 | 645 |
| Day entries, before dedup | 1,959 | 3,447 |
| Distinct days | 1,936 | 3,330 |
| Sum of `item_count` over distinct days | 25,149 | 77,436 |
| `Browse.Item` `count` | 25,149 | 77,436 |
| Years covered | 2000-2026 | 2002-2026 |
| Largest day | 664 | 1,220 |
| Response size | 266 KB | 492 KB |

Days are in strictly descending order across the whole response, the same order as the item
list sorted by `takentime` descending. That is the property decision 005 rests on, and it is
also how Photos' client works: each section is exactly the `offset`/`limit` window the client
fetches next. Room will hold about 5,300 day rows for this library, not "hundreds", which is
still nothing.

## U3. The item list, and the time range

`SYNO.Foto.Browse.Item` `list` v7. Parameters used: `offset`, `limit`, `sort_by=takentime`,
`sort_direction=desc`, `additional=["thumbnail","resolution","orientation"]`. `count` v7 with
no parameters returns `data.count`.

An item:

```text
id              int     equals additional.thumbnail.unit_id on all 200 sampled items
filename        string  never shown, never logged
filesize        int     bytes
folder_id       int
owner_user_id   int     1 in the personal space, 0 in the shared space, in this sample
time            int     taken time, epoch seconds (U5)
indexed_time    int     epoch milliseconds
type            string  "photo" or "video"; 9 of each 100 sampled were videos
additional
  .orientation           int, EXIF orientation; 1 and 8 seen
  .orientation_original  int, same values seen
  .resolution            {width, height}
  .thumbnail             {cache_key, unit_id, sm, m, xl, preview}
```

`thumbnail.sm`, `.m`, `.xl` were `"ready"` on every sampled item and `.preview` was `"broken"`
on every one; the app never asks for `preview`. `cache_key` is `<unit_id>_<digits>`. Videos
carry the same fields as photos, including a ready thumbnail, so plan 004 can show them in the
grid with a badge without any other call.

**A time range is honoured, under `start_time` and `end_time`, in epoch seconds.** With the
window 1000000000..1100000000 (September 2001 to November 2004) both namespaces returned items
taken in 2004 instead of the newest ones. `time_start` and `time_end` were silently ignored: the
response was identical to the unfiltered call, which is the failure mode that makes the wrong
spelling dangerous. Not yet established: whether the ends are inclusive, and whether the filter
compares against `time` exactly as the day buckets are cut (U7). Both are one call each on the
next run. Until then the offset arithmetic below is the verified mechanism and the range is the
candidate replacement, which is the case decision 005 said would be the first thing to
reconsider.

### Decision 005's arithmetic, checked once

Running total before a day, over the flattened histogram, equals that day's offset in the item
list. Checked in both namespaces with one item either side, which is plan 004's overlap read:

| Namespace | Day | `item_count` | Running total | Fetched | Returned days, newest first |
| --- | --- | --- | --- | --- | --- |
| Personal | 2026-08-28 | 2 | 8 | `offset=7 limit=4` | 08-31, 08-28, 08-28, 08-27 |
| Shared | 2026-05-23 | 1 | 2 | `offset=1 limit=3` | 05-28, 05-23, 05-11 |

Exactly the day's items inside, one neighbour on each edge outside.

## U4. Thumbnails

`SYNO.Foto.Thumbnail` `get` v2 as a **GET**:

```text
GET /webapi/entry.cgi?api=SYNO.Foto.Thumbnail&method=get&version=2
    &id=<item id>&cache_key=<cache_key>&type=unit&size=sm&_sid=<sid>
X-SYNO-TOKEN: <synotoken>
```

Serves `image/jpeg` bytes for `size=sm`, `m` and `xl`. For one 3000x4000 photo: 23.7 KB,
39.1 KB and 318 KB. Without the header the same URL returns `application/json`, a 38-byte error
envelope, with HTTP 200, so an image loader that ignores the content type would cache an error
document as a picture: Coil's fetcher must treat a non-image content type as a failure.

The namespace has to match the item: a shared-space item is fetched from
`SYNO.FotoTeam.Thumbnail`. The disk cache key is unit id plus size, never the URL, because the
URL carries the session id (plan.md §7).

Open, for the next run: the session id in a GET query string is written to the reverse proxy's
access log. DSM also reads the session from a cookie named `id`, and the token from a
`SynoToken` query parameter; if the cookie form works, thumbnail URLs can carry nothing secret
at all.

## U5. Taken time

`time` is epoch **seconds**. `indexed_time` is epoch **milliseconds**. Do not mix them up when
the two sit next to each other in the same object.

## U6. Shared space and folder permissions

**Open.** Needs a run as the restricted account (`test-user`, which owns nothing; a folder may
have to be shared to it first). The comparison is `Browse.Item` `count` on `SYNO.FotoTeam`
against the owner's 77,436: equal means the shared space ignores per-user folder permission and
decision 002's merge would show a household member everything.

## U7. Whose calendar the days are

The timeline's `year, month, day` equals the **UTC calendar date of `time`** on every one of
the 35 days fully covered by the two 100-item samples. Bucketing the same items by their
Europe/Prague date disagrees with the timeline on 4 of those days, always by one photo taken
within two hours of local midnight.

Two readings fit that, and the captures cannot tell them apart: Photos cuts days in UTC, or
`time` is the camera's wall clock encoded as if it were UTC (EXIF carries no zone, and Synology
has historically stored it that way), in which case the days are the camera's own. It does not
matter to the app. The rule is the same either way, and it is what decision 005 asked for:
**a photo belongs to the UTC date of its `time`, and the device zone is used for nothing but
"what is today".** Plan 004's fallback filter on taken time must use that rule.

One photo with a known local capture time would settle the reading: the newest personal item
has `time` 20:22:33 UTC on 2026-09-01. Taken at 22:22 local means true UTC; taken at 20:22
means wall clock.

## Scale, for the plans that page

| | Personal | Shared |
| --- | --- | --- |
| Items | 25,149 | 77,436 |
| Distinct days | 1,936 | 3,330 |
| Largest day | 664 items | 1,220 items |
| Timeline response | 266 KB | 492 KB |
| Videos in the newest 100 | 9 | 9 |

A day of 1,220 items across the two namespaces is the worst case a day screen has to page.

## What the next run should settle

Two logins, none retried:

1. As the owner: `start_time`/`end_time` boundary semantics (a range of one exact `time`
   returns one item if inclusive); a whole-day range against the histogram's `item_count`;
   thumbnail GET with the session in a `Cookie: id=` header and the token in `X-SYNO-TOKEN`,
   with nothing in the query string; `SynoToken` as a query parameter; whether `device_id`
   carries a value without a two-factor code.
2. As `test-user`: U6.

## Deliberately not done

No write, no album, no sharing, no folder, no download call. Timeline versions below 6 not
probed. Response bodies never printed; the summariser prints field names, counts, dates and
ids only.
