# 005 - Viewer, download, hardening

- **Status:** Not started
- **Source:** plan.md §2, §7, §8.3, §8.4
- **Depends on:** 004
- **Blocks:** nothing
- **Decisions:** [003](../decisions/003-authentication-and-sessions.md), [006](../decisions/006-one-account-per-install.md)
- **Progress:** 0 / 11

## Goal

Fullscreen viewing across the day's years, the original file saveable, and every safety rule in
plan.md §2 backed by something that fails when it is broken.

## Tasks

### Viewer

- [ ] Fullscreen pager across the whole day, all years in one sequence, year and time shown.
- [ ] Larger thumbnail on open, original fetched on zoom.
- [ ] Download the original to the device's pictures collection, with a progress indication.

### Settings

- [ ] Base URL, refresh policy, sign out.
- [ ] Cache size shown, and a clear action.

### Hardening

- [ ] A test asserting the allowlist contains no method whose name implies a write.
- [ ] A test asserting no logging call receives a response body.
- [ ] A test asserting account change clears the day index and the image cache.
- [ ] `allowBackup=false` confirmed, so no session id leaves the device in a cloud backup.
- [ ] A pass over every string resource for anything that would leak a host or an account.
- [ ] Release build signed with the app's own keystore, and the keystore backup rule written
      into `CLAUDE.md`.

## Acceptance criteria

- [ ] Every rule in plan.md §2 maps to a test or to a signed-off item here, by name.
- [ ] A release APK installs over a debug install without uninstalling.
