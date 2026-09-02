# 002 - Foundation and sign-in

- **Status:** Not started
- **Source:** plan.md §4, §5, §7, §8.1
- **Depends on:** 001
- **Blocks:** 004
- **Decisions:** [003](../decisions/003-authentication-and-sessions.md), [004](../decisions/004-access-path-and-tls.md), [006](../decisions/006-one-account-per-install.md)
- **Progress:** 0 / 14

## Goal

A person can enter a host, an account and a password, get past two-factor if it is on, and land
on an authenticated empty day screen. Session expiry re-prompts rather than failing silently.
No password is on disk at any point.

## Tasks

### Transport

- [ ] OkHttp client: timeouts, no redirect to cleartext, no custom trust manager.
- [ ] `entry.cgi` call layer, api, method and version in the body, form-encoded.
- [ ] Error envelope decoding: Synology's `{success, error:{code}}`, mapped to typed failures.
- [ ] The `(api, method, version)` allowlist from plan 001, refusing anything absent.
- [ ] Logging that names the call and the error code and never touches the body.

### Session

- [ ] Login call: account, password, `format=sid`, `enable_syno_token=yes`, optional code.
- [ ] Keep the session id and the trusted-device id in DataStore; drop the password after use.
- [ ] Attach the session id and the token to every subsequent call.
- [ ] Expiry detection on the session error codes, surfacing as a re-prompt.
- [ ] Sign out calls the logout endpoint, clears storage, clears caches.
- [ ] No automatic retry of a failed login, and a visible note about DSM auto-block.

### UI

- [ ] Sign-in screen: host, account, password, code when asked for, DSM's error text on failure.
- [ ] Base URL validated as HTTPS, with a plain refusal of `http://` and the reason.
- [ ] Navigation: sign-in when there is no session, day screen when there is.

## Acceptance criteria

- [ ] A wrong password shows DSM's error and does not retry.
- [ ] Killing the session on the NAS makes the next call re-prompt, not crash.
- [ ] A search of the source finds no path that writes a password anywhere.
- [ ] MockWebServer tests cover the error envelope, expiry and the allowlist refusal.
