# 012 - The likes sync answers HTTP 502 on a second account

- **Status:** Not started. Evidence collected 2026-09-03 and 2026-09-04; the app text that produced
  it is already corrected (task 3, first box).
- **Source:** owner report, a second household account (`julinka`) on v1.0.4.
- **Depends on:** 009 (its error texts are what made the evidence readable).
- **Blocks:** likes on every account but the owner's.
- **Decisions:** [004](../decisions/004-access-path-and-tls.md) (the nginx in the router's LXC that
  reverse-proxies DSM; a 502 comes from there), [008](../decisions/008-writing-likes-to-the-nas.md)
  (likes are one File Station file).
- **Progress:** 1 / 9

## Goal

Likes sync for every household account, not just the owner's. Failing that, the app says what is
wrong in words that point at the real cause.

## What is known

- Settings on the second account's phone (v1.0.4, account `julinka`, likes folder the default
  `/home/OnThisDay`) reads: *"Nepodařilo se: NAS odmítl soubor s lajky (HTTP 502)."*
- 502 is not a DSM error code. It is an HTTP status the app read off the response, raised as
  `ApiFailure.Malformed` with `MalformedDetail.http(502)` by `FileStationClient` when the response
  is not 2xx. Both the download and the upload can raise it, and the message does not yet say
  which.
- The same NAS, the same proxy, the same app version and the same default folder sync fine on the
  owner's account, and a first run into a folder that had never existed works: the download answers
  DSM 408 and the upload creates the folder and the file (research, "File Station" section).
- Three DSM prerequisites were checked by the owner for that account and are in place: user home
  service on, File Station allowed, full rights on the home folder.
- The app does not retry. `AppGraph.http` sets `retryOnConnectionFailure(false)` and nothing above
  it retries, so a single 502 fails the whole sync; the notice then shows once per app start.

## Hypotheses, most likely first

1. **The reverse proxy fails on this one request.** 502 means the upstream closed the connection or
   answered unusably. Candidates: a response header larger than the proxy's buffer (a File Station
   download sets `Content-Disposition` with the file name), an upstream timeout while DSM creates a
   home folder for the first time, or a buffer or body limit that only the multipart upload hits.
   The proxy's own error line names which; the app cannot tell them apart.
2. **DSM answers 5xx for this account.** A home folder on a volume that is not mounted, or a File
   Station worker refusing for that user, can surface as a gateway error rather than an envelope.
3. **Transient.** A gateway hiccup at the moment of the first sync would look exactly like this,
   and with no retry it becomes a permanent-looking failure on screen.

## Tasks

### 1. Which call, and does it persist

- [ ] On that phone: `adb logcat -c && adb logcat -s PhotosApi`, then open the app. Record the
      failing line, which names the call (`SYNO.FileStation.Download.download v2` or
      `.Upload.upload v2`) and the detail. Write it into this plan.
- [ ] Force two or three more syncs (pull to refresh, or like and unlike one photo) and record
      whether the 502 comes every time or now and then. This separates hypothesis 3 from 1 and 2.

### 2. What the proxy and DSM say

- [ ] Read the nginx error log in the router's LXC around that timestamp. Its reason string
      (`upstream sent too big header`, `recv() failed`, `upstream timed out`) decides the fix.
      Record it here; if the proxy configuration changes, amend decision 004.
- [ ] Check DSM's Log Center and the File Station log for the same minute, signed in as that
      account. Record whether DSM saw the request at all.

### 3. What the app does about it

- [x] A 5xx is not a permission problem, and the text said it was. Corrected 2026-09-04:
      500-599 now says the proxy or NAS did not answer and to retry, and names the proxy as the
      place to look; 401 and 403 name the File Station permission; other statuses keep the
      folder-and-permission wording. `DsmErrorTextTest` covers all three.
- [ ] Say which call failed. The likes texts read as one thing ("soubor s lajky") whether the
      download or the upload raised them, which costs a diagnostic step; carry the call's method
      into the text, or split the two texts.
- [ ] If the evidence says transient: one bounded retry for the likes sync only. The upload is an
      idempotent overwrite and the download is a read, so a second attempt is safe. Never for the
      login (plan.md §2, DSM auto-block). Unit test with `MockWebServer`: 502 then 200 succeeds,
      two 502s fail with the same text as today.

### 4. Verify

- [ ] That account's likes sync succeeds: `likes.json` is in its home folder, a like survives an
      app restart, and Settings reads "V pořádku".
- [ ] The owner's account is unaffected (it syncs today; a retry must not change that).

## Acceptance criteria

- [ ] The cause of the 502 is written down, in the proxy's or DSM's own words, not inferred.
- [ ] The second account either syncs, or this plan records exactly what has to change on the NAS
      and why the app cannot do it.
- [ ] Whatever the cause turns out to be, the text the user reads names it correctly.

## On completion

1. Tick as verified, keep `Progress:` in step in the same commit.
2. Update `index.md`.
3. If the proxy or DSM configuration changed, amend decision 004; if a File Station shape was
   learned, add it to `documents/research/photos-web-api.md`.
