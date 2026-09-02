# Decisions

Architecture decision records. The summary here is a pointer, not a substitute: read the record
before acting on it, because the Consequences section usually holds the constraint the one-line
summary leaves out.

| Record | In force since | Summary |
| --- | --- | --- |
| [001](001-web-api-is-the-only-source.md) | 2026-09-02 | The Photos web API is the only data source. The companion repo's database access cannot work from a phone and carries no notion of a signed-in viewer. |
| [002](002-personal-and-shared-space.md) | 2026-09-02 | Personal and shared space are both read and merged. Running totals stay per namespace. |
| [003](003-authentication-and-sessions.md) | 2026-09-02 | The session id is stored, the password never is. Expiry re-prompts. The two-factor device id is kept. |
| [004](004-access-path-and-tls.md) | 2026-09-02 | HTTPS to a DDNS hostname with a Let's Encrypt certificate. No pinning, no cleartext, no TLS code. |
| [005](005-day-index-on-device.md) | 2026-09-02 | The day histogram lives in Room and answers day questions offline. A day's photos are fetched by an offset computed from running totals. Photos' own day boundaries are authoritative. |
| [006](006-one-account-per-install.md) | 2026-09-02 | One account per install. An account change deletes the index and every cache before showing anything. |
| [007](007-stack-and-layout.md) | 2026-09-02 | The strumbook Android skeleton, `com.hawwwran.photosonthisday`, single module, Compose and Room and Coil 3. |

## Open questions

- **Q1** Does `SYNO.FotoTeam.*` filter by per-user folder permission? If not, [[002]]'s merge
  shows a household member more than Photos would. Answered by plan 001 (U6).
- **Q2** Can a third-party client obtain a Photos-capable session through DSM SSO or OIDC? If
  yes, [[003]]'s password handling gets simpler and should be revisited.
- **Q3** Does the item list accept a time range on Photos 1.9.1? If yes, [[005]]'s offset
  arithmetic becomes an optimisation rather than the mechanism. Answered by plan 001 (U3).
