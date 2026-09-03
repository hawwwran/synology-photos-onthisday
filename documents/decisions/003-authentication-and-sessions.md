# 003 - Authentication and session lifetime

- **Status:** Accepted
- **Date:** 2026-09-02
- **Origin:** Chosen (project owner, 2026-09-02)

## Context

`SYNO.API.Auth` `login` returns a session id, optionally a token, and a trusted-device id when
two-factor is satisfied. DSM issues no refresh token to a third-party client, so a client that
wants to survive session expiry without asking the user must keep the password.

That is the whole decision: store a password, or ask for one again.

## Decision

**The session id is stored. The password never is.** When DSM ends the session, the app asks
for the password again.

- Stored: session id, the token that accompanies it, and the two-factor trusted-device id.
- Not stored: the password. It lives in memory for one login call and is dropped.
- The trusted-device id is kept deliberately, so the second factor is asked for once per
  install rather than once per session. It is not a credential on its own.
- Storage is DataStore in the app's private directory, which file-based encryption already
  covers. `androidx.security:security-crypto` is not used: it is deprecated, and it would add a
  dependency to protect a short-lived token that never leaves the device.
- `allowBackup=false`, so no session id leaves the device in a cloud backup.
- A failed login is never retried automatically. DSM auto-block bans the address after a few
  failures and would lock the household out of its own NAS.

## Consequences

- No password exists at rest anywhere, so a stolen or rooted phone yields at most a session id
  that DSM can revoke. This is the point of the decision.
- The user retypes the password whenever DSM expires the session. How often that is depends on
  DSM's session settings, which are a NAS setting rather than an app change: if it is annoying
  in practice, Control Panel is where it gets fixed.
- The app must open to cached content, because a login prompt over a blank screen reads as a
  broken app. This is what makes the on-device cache a requirement rather than an optimisation
  ([[005-day-index-on-device]]).
- Biometric gating is not implemented and is not needed: there is nothing at rest worth gating
  beyond photos already cached, and the OS lock screen covers those.

## Alternatives considered

- **Store the password in `EncryptedSharedPreferences` for silent re-login.** The smoothest
  experience. Rejected by the owner: it puts a NAS account password on a phone, and the NAS
  account is the whole NAS, not just Photos.
- **Store the password behind a biometric prompt.** Middle ground, still a password at rest,
  and it adds a prompt to app open. Rejected for the same reason with extra machinery.
- **DSM OIDC or SSO.** No password would be handled at all. Rejected for now: DSM's SSO applies
  to the DSM web session, and it is not established that a third-party client can obtain a
  Photos-capable session that way. Worth revisiting if it ever can.

## Amendments

- 2026-09-03, plan 007: **an expiry is scoped to the session that saw it.** `onSessionExpired`
  takes the session id the failing call used, and the store ignores it unless it is the stored
  one. Before this, a view model that outlived its sign-out could meet DSM 119 with its dead sid
  and remove whatever sid was stored by then, which was the new login's. **DSM 105 is not an
  expiry.** It means "insufficient privilege": the session is alive and the account may not make
  that call. It maps to a plain DSM error with its own text; only 106, 107 and 119 re-prompt.
  Treating 105 as expiry would sign an account without shared-space access out on every refresh.

## Related

[[001-web-api-is-the-only-source]], [[005-day-index-on-device]], [[006-one-account-per-install]]
