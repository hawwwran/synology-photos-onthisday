# 001 - The Photos web API is the only data source

- **Status:** Accepted
- **Date:** 2026-09-02
- **Origin:** Chosen

## Context

`synology-photos-companion` already reads Synology Photos, so reusing its access model was the
first thing to check. It connects to the `synofoto` PostgreSQL cluster over a Unix socket with
peer authentication, as a superuser role, from a container running on the NAS itself
(companion decision 020).

Two facts kill that model for a phone. `pg_hba.conf` has no `host` line and nothing listens on
TCP 5432, so the socket is the only way in and a phone cannot reach it. And the database has no
notion of a signed-in viewer: "which photos may this account see" is not derivable from it,
which companion's own research states while explaining why `category=normal_share_with_me`
exists only over HTTP.

The requirement here is the opposite of companion's. Companion needs filesystem paths, which
only the database has. This app needs per-account visibility, which only a session has.

## Decision

The app talks to the Synology Photos web API over HTTPS and to nothing else. No database, no
server component, no NAS-side deployment, no shared code with companion.

The session belongs to the person using the app, so Synology decides what they can see and the
app implements no permission logic at all.

## Consequences

- Per-account access is correct by construction rather than by reimplementation. This is the
  main reason for the decision.
- Nothing is deployed to the NAS. There is no service to run, no secret at rest on the NAS, and
  no upgrade path to coordinate with DSM updates.
- The app depends on undocumented, version-sensitive endpoints. Companion's own note applies:
  an HTTP API is *more* version-sensitive than the schema. The mitigations are plan 001's
  observation pass, `SYNO.API.Info` version discovery at runtime, and failing with a clear
  message rather than guessing.
- No filesystem path is available, so nothing path-shaped can ever be built here. Fine: this
  app displays photos, it does not copy files.
- Companion's research documents stay useful as background (the `id_user` versus DSM uid trap,
  `unit.takentime`, library scale), but no code moves between the repos.

## Alternatives considered

- **A small API server on the NAS reading the database.** One query would answer the whole
  daily cut, indexed and fast. Rejected: it would have to reimplement Synology's ACL to be
  per-user, it needs the superuser socket connection companion documents as its largest cost,
  and it puts a phone-facing service in front of that connection.
- **Reusing companion itself as the backend.** Rejected for the same reason plus scope: its
  safety rules, its plans and its whole shape are about archiving files, and a phone API would
  fight them.

## Related

[[002-personal-and-shared-space]], [[003-authentication-and-sessions]]
