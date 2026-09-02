# 005 - Viewer, download, hardening

- **Status:** Mostly done; two items blocked on external inputs (see below)
- **Source:** plan.md §2, §7, §8.3, §8.4
- **Depends on:** 004
- **Blocks:** nothing
- **Decisions:** [003](../decisions/003-authentication-and-sessions.md), [006](../decisions/006-one-account-per-install.md)
- **Progress:** 9 / 11

## Goal

Fullscreen viewing across the day's years, the original file saveable, and every safety rule in
plan.md §2 backed by something that fails when it is broken.

## Tasks

### Viewer

- [x] Fullscreen pager across the whole day, all years in one sequence, year and time shown.
- [x] Larger thumbnail on open, and pinch-zoom. Zoom shows the large rendition (`xl`); the
      byte-original on zoom awaits the Download endpoint, see the blocked item below.
- [ ] Download the original to the device's pictures collection, with a progress indication.
      > Blocked: `SYNO.Foto.Download` was not observed and this project does not guess an
      > endpoint. A save-to-gallery of the largest rendition (`xl`) is shipped as the interim,
      > with a progress spinner; `ImageSaver` streams to MediaStore. `observe-photos-api.sh`
      > now probes the download endpoint, so one owner run settles the parameter and version,
      > after which the saver switches to the original.

### Settings

- [x] Base URL, refresh policy, sign out.
- [x] Cache size shown, and a clear action.

### Hardening

- [x] A test asserting the allowlist contains no method whose name implies a write.
- [x] A test asserting no logging call receives a response body. (`HardeningTest` asserts the
      failure-message contract; `ApiLog` is the only logger and takes the same call-and-code inputs.)
- [x] A test asserting account change clears the day index and the image cache. (`SessionManagerTest`
      asserts the index wipers run and the thumbnail wiper runs on account change.)
- [x] `allowBackup=false` confirmed in the manifest and asserted by `ManifestTest` (androidTest).
- [x] A pass over every string resource: none carries a host, account, or credential.
- [ ] Release build signed with the app's own keystore, and the keystore backup rule written
      into `CLAUDE.md`.
      > Blocked: no keystore exists yet (owner action). The backup rule is already in `CLAUDE.md`
      > ("Release signing"), and `app/build.gradle.kts` co-signs debug with the release key when
      > the keystore is present. Creating `keystore.jks` and the `OTD_*` gradle properties is the
      > remaining owner step; then `assembleRelease` and the install-over-debug check can run.

## On-device verification, 2026-09-02

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
- [ ] A release APK installs over a debug install without uninstalling.
      > Blocked with the keystore item above: needs the release keystore to exist first.
