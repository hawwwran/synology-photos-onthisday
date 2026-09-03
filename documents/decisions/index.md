# Decisions

Architecture decision records. The summary here is a pointer, not a substitute: read the record
before acting on it, because the Consequences section usually holds the constraint the one-line
summary leaves out.

| Record | In force since | Summary |
| --- | --- | --- |
| [001](001-web-api-is-the-only-source.md) | 2026-09-02 | The Photos web API is the only data source. The companion repo's database access cannot work from a phone and carries no notion of a signed-in viewer. |
| [002](002-personal-and-shared-space.md) | 2026-09-02 | Personal and shared space are both read and merged. Running totals stay per namespace. |
| [003](003-authentication-and-sessions.md) | 2026-09-02 | The session id is stored, the password never is. Expiry re-prompts, and only for the session that saw it; DSM 105 is a permission error, not an expiry (amended 2026-09-03). The two-factor device id is kept. |
| [004](004-access-path-and-tls.md) | 2026-09-02 | HTTPS to a hostname on the owner's domain, TLS terminated by the router's existing reverse proxy in front of DSM (amended twice). No pinning, no cleartext, no TLS code. |
| [005](005-day-index-on-device.md) | 2026-09-02 | The day histogram lives in Room and answers day questions offline. A day's photos are fetched by `start_time`/`end_time` (amended 2026-09-02; the offset arithmetic was verified once and retired). Photos' own day boundaries are authoritative: the UTC date of `time`. |
| [006](006-one-account-per-install.md) | 2026-09-02 | One account per install, identified by base URL plus account name (amended 2026-09-03). An account change deletes the index and every cache before showing anything. |
| [007](007-stack-and-layout.md) | 2026-09-02 | The strumbook Android skeleton, `com.hawwwran.photosonthisday`, single module, Compose and Room and Coil 3. |
| [008](008-writing-likes-to-the-nas.md) | 2026-09-02 | Likes are written to the NAS as one app-owned File Station file (`Upload`/`Download` only). Synology Photos stays strictly read-only; the write allowlist is held apart from the read allowlist. Sync is serialized and transactional, and an unreadable file is never overwritten (amended 2026-09-03). |

## Open questions

- **Q1** Does `SYNO.FotoTeam.*` filter by per-user folder permission? **Closed unanswered,
  2026-09-02:** the owner dropped it, because no restricted account exists in this household's
  use of the app. [[002]] records the acceptance and how to test it if that changes.
- **Q2** Can a third-party client obtain a Photos-capable session through DSM SSO or OIDC? If
  yes, [[003]]'s password handling gets simpler and should be revisited.
- **Q3** Does the item list accept a time range on Photos 1.9.1? **Yes**, `start_time` and
  `end_time` (plan 001, 2026-09-02). **Resolved:** [[005]] amended the same day to fetch by
  range; the second run verified both ends inclusive, so the one-second edge the owner had
  accepted does not exist.
- **Q5** Google Play Protect rejects the app's APK at install time on the owner's Android 15 phone
  ("unknown developer", verdict REJECT after its cloud scan), so in-app updates fail until the user
  pauses Play Protect scanning; the update dialog now says so and links to the setting (plan 010
  addendum, 2026-09-03). Open: distribute through Google Play (internal or closed testing) or
  register for Google's developer verification of sideloaded apps, so the signing key is known and
  the pause is not needed on every household phone.
- **Q4** DSM auto-block sees only the reverse proxy's address ([[004]], second amendment), so a
  few failed logins from any household member would ban the proxy for everyone. Allowlist the
  proxy in DSM and rate-limit the login call in nginx, or accept it. **Resolved 2026-09-02:**
  accepted as is by the owner. The household is small and a ban lifts by itself.
