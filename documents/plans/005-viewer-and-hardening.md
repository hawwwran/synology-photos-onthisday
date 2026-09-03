# 005 - Viewer, download, hardening

- **Status:** Done. v1.0.0 (versionCode 2) cut 2026-09-03 with the release keystore.
- **Source:** plan.md §2, §7, §8.3, §8.4
- **Depends on:** 004
- **Blocks:** nothing
- **Decisions:** [003](../decisions/003-authentication-and-sessions.md), [006](../decisions/006-one-account-per-install.md)
- **Progress:** 13 / 13

## Goal

Fullscreen viewing across the day's years, the original file saveable, and every safety rule in
plan.md §2 backed by something that fails when it is broken.

## Tasks

### Viewer

- [x] Fullscreen pager across the whole day, all years in one sequence, year and time shown.
- [x] Video playback in the viewer (added at the owner's request, amends plan.md §9): a video
      page uses Media3 ExoPlayer with the classic controls (seek bar, play/pause, 15 s skip),
      streamed from the download endpoint with the token header; only the on-screen page plays.
      Controls start hidden and never cover the video unasked: a tap shows them (auto-hiding while
      playing), a double-tap on the right half skips 15 s forward and on the left half 15 s back.
      In landscape the app goes immersive on a video, hiding the top bar and the system bars with
      the controls until the video is tapped. MainActivity handles rotation without recreation so
      the player keeps its position. Verified on device with a 2-minute clip.
- [x] Larger thumbnail on open, and pinch-zoom. Zoom shows the large rendition (`xl`); the
      byte-original on zoom awaits the Download endpoint, see the blocked item below.
- [x] Download the original to the device's pictures collection, with a progress indication.
      `SYNO.Foto.Download` `download` v2 with `unit_id=[<id>]`, settled by the second owner run
      (research, "Update, second run"). `ImageSaver` streams the original to MediaStore, images to
      Pictures and videos to Movies, with a spinner while it runs. Verified on device: the saved
      file is the 4.06 MB original, not the 338 KB rendition.
- [x] Share the original (added at the owner's request): a share button beside download downloads
      the original into a gitignored temp cache dir, hands it to the Android share sheet through a
      FileProvider (`${applicationId}.fileprovider`), and sweeps temp copies older than an hour on
      each share. Verified on device: the share sheet opens with the image and the temp copy is the
      4.06 MB original.

### Settings

- [x] Base URL, refresh policy, sign out.
- [x] Cache size shown, and a clear action.

### Hardening

- [x] A test asserting the allowlist contains no method whose name implies a write.
- [x] A test asserting no logging call receives a response body. (`HardeningTest` asserts the
      failure-message contract. The claim that `ApiLog` was the only logger was false when first
      ticked; since plan 011, `LoggingRuleTest` scans the source tree, allows `android.util.Log`
      only in `ApiLog`, `IndexLog` and `UpdateLog`, and asserts none of their functions takes a
      `String`.)
- [x] A test asserting account change clears the day index and the image cache. (`SessionManagerTest`
      asserts the index wipers run and the thumbnail wiper runs on account change.)
- [x] `allowBackup=false` confirmed in the manifest and asserted by `ManifestTest` (androidTest).
- [x] A pass over every string resource: none carries a host, account, or credential.
- [x] Release build signed with the app's own keystore, and the keystore backup rule written
      into `CLAUDE.md`. `keystore.jks` (alias `onthisday`) was generated 2026-09-03; the release
      script built v1.0.0 with `assembleRelease`, verified the signature with `apksigner` and
      published it (commit `f499168`). The backup rule is in `CLAUDE.md`, "Release signing".

## On-device verification, 2026-09-02

The second owner run also let the thumbnail GET move its session into a `Cookie: id=` header, so
thumbnail URLs no longer carry `_sid` and the reverse proxy's access log never sees it. The
download keeps `_sid` in the query, the only form observed to work for it.


Verified on the Vivo V2145 against the live NAS: tapping a grid photo opens the fullscreen
viewer with the large rendition, the year and taken time overlaid and clear of the status bar
(an inset collision was found and fixed), pinch-zoom, and Save writing `OnThisDay-<id>.jpg` into
the gallery (confirmed in MediaStore). Settings shows the NAS, account, the refresh policy, the
thumbnail cache size and a clear action, and sign out. Screenshots are in the session scratchpad,
not committed.

## plan.md §2 safety rules, mapped

- **Read-only Photos.** `HardeningTest`: every allowlisted method is a read verb, and no
  allowlisted api or method name implies a write.
- **Only allowlisted triples.** `SynologyClient.call` calls `Allowlist.require` before building a
  request; `SynologyClientTest` asserts a triple off the list never reaches the network.
- **Never log a response body.** `ApiLog` is the only logger and takes a call and a code;
  `HardeningTest` asserts failure messages carry the call name and code and never body fields.
- **Never store the password.** `SessionStore` has no password key; `SessionManagerTest` and a
  source search confirm no path writes one. HTTPS-only enforced in `parseBaseUrl` (`BaseUrlTest`).
- **HTTPS with a trusted certificate.** No `usesCleartextTraffic`, no network-security-config, no
  custom trust manager (manifest, `AppGraph`); `parseBaseUrl` refuses `http://`.
- **One account's photos never survive into another.** `SessionManager` wipes the index and the
  thumbnail cache on account change before the new account's data shows; `SessionManagerTest`
  covers both wiper groups, and `DayIndexRepositoryTest` covers the index wipe.

## Acceptance criteria

- [x] Every rule in plan.md §2 maps to a test or a signed-off item, by name (see the mapping below).
- [x] A release APK installs over a debug install without uninstalling. 2026-09-03 20:06: the
      published v1.0.1 release APK installed over the co-signed debug build through the in-app
      updater (`InstallSuccess`), and a debug build installed back over it a minute later. Both
      directions carry certificate `8e89…e75c`.
