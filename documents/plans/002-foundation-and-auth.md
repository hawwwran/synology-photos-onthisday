# 002 - Foundation and sign-in

- **Status:** Done, bar one acceptance check that waits for plan 003's first authenticated call
- **Source:** plan.md §4, §5, §7, §8.1
- **Depends on:** 001
- **Blocks:** 004
- **Decisions:** [003](../decisions/003-authentication-and-sessions.md), [004](../decisions/004-access-path-and-tls.md), [006](../decisions/006-one-account-per-install.md)
- **Progress:** 14 / 14

## Goal

A person can enter a host, an account and a password, get past two-factor if it is on, and land
on an authenticated empty day screen. Session expiry re-prompts rather than failing silently.
No password is on disk at any point.

## Tasks

### Transport

- [x] OkHttp client: timeouts, no redirect to cleartext, no custom trust manager.
- [x] `entry.cgi` call layer, api, method and version in the body, form-encoded.
- [x] Error envelope decoding: Synology's `{success, error:{code}}`, mapped to typed failures.
- [x] The `(api, method, version)` allowlist from plan 001, refusing anything absent.
- [x] Logging that names the call and the error code and never touches the body.

### Session

- [x] Login call: account, password, `format=sid`, `enable_syno_token=yes`, optional code.
- [x] Keep the session id and the trusted-device id in DataStore; drop the password after use.
- [x] Attach the session id and the token to every subsequent call.
- [x] Expiry detection on the session error codes, surfacing as a re-prompt.
- [x] Sign out calls the logout endpoint, clears storage, clears caches.
- [x] No automatic retry of a failed login, and a visible note about DSM auto-block.

### UI

- [x] Sign-in screen: host, account, password, code when asked for, DSM's error text on failure.
- [x] Base URL validated as HTTPS, with a plain refusal of `http://` and the reason.
- [x] Navigation: sign-in when there is no session, day screen when there is.

## Notes for the next plan

- `AppGraph.accountDataWipers` is an empty mutable list. Plan 003's database and plan 004's
  thumbnail cache each add an `AccountDataWiper`, and decision 006's wipe then reaches them with
  no change to `SessionManager`.
- Whoever makes the first authenticated call catches `ApiFailure.SessionExpired` and calls
  `SessionManager.onSessionExpired()`; `AppRoot` reacts to the store, so no navigation call is
  needed.

## Acceptance criteria

- [x] A wrong password shows DSM's error and does not retry.
- [ ] Killing the session on the NAS makes the next call re-prompt, not crash.
      > Blocked: plan 002 makes no authenticated call, so there is no "next call" to exercise.
      > The chain is built and unit-tested (`SynologyClient` throws `SessionExpired` on 105/106/
      > 107/119, `SessionManager.onSessionExpired` marks the store, `AppRoot` then shows sign-in
      > with the expired notice). Plan 003's first timeline call catches `SessionExpired` and
      > calls `onSessionExpired`, which closes this.
- [x] A search of the source finds no path that writes a password anywhere.
- [x] MockWebServer tests cover the error envelope, expiry and the allowlist refusal.
