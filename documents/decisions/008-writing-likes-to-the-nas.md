# 008 - Likes are written to the NAS as an app-owned file, Photos stays read-only

- **Status:** Accepted
- **Date:** 2026-09-02
- **Origin:** Chosen (project owner, 2026-09-02), plan [006](../plans/006-likes.md)

## Context

The owner wants to like photos and videos and have the likes kept on the NAS, so they survive a
reinstall and the account-change wipe ([[006-one-account-per-install]]) and appear on another
device signed in as the same account. That is the first time the app would write to the NAS,
which [[001-web-api-is-the-only-source]] and plan.md §2 forbid: "Synology Photos is read-only."

Plan 006 laid out three ways: the native Synology favorite, a tag on the item, or an app-owned
file on the filesystem. The owner chose the file.

## Decision

**Synology Photos stays strictly read-only. The only thing the app writes to the NAS is its own
likes file, through File Station, never through a Photos endpoint.**

- The likes live in one JSON file the app owns, by default `/home/OnThisDay/likes.json` (the
  signed-in account's home, so likes are per-account; the folder is a setting so it can be moved
  to a share the account can write). Nothing else on the NAS is touched.
- The write surface is exactly two File Station calls: `SYNO.FileStation.Upload` v2 to save the
  file (the only write), and `SYNO.FileStation.Download` v2 to read it back (a read). No Photos
  api gains a write. No `Delete`, `Rename`, `CopyMove`, `CreateFolder`-of-anything-else, or any
  other File Station method is allowlisted; `Upload` with `create_parents` makes the folder.
- These live in a **separate write allowlist**, held apart from the read allowlist, so the
  read-only guarantee for Photos is still checkable by looking at the read set alone.
- The like is idempotent, reversible and non-destructive: it sets or clears a like in the app's
  file and does nothing else. A photo that is not liked is never modified in any way.
- The local Room cache is the source of truth for instant, offline toggling; the file on the NAS
  is the durable copy. Sync is last-writer-wins by timestamp, which is safe because like and
  unlike are idempotent. The account-change wipe clears the local cache only; the file is
  re-read on the next sign-in.

## Consequences

- Photos' read-only invariant is intact: the read allowlist still contains only reads, and a
  reader can confirm that without reasoning about the write path. This is why the file was chosen
  over a favorite or a tag, which would have put a write onto a Photos endpoint.
- The likes are app-private. They do not show as favorites in the Synology Photos app or in other
  tools. If that is ever wanted, plan 006's tag option is the upgrade, and it would need its own
  decision because it writes into Photos.
- File Station must be enabled for the account and the folder must be writable. The default is the
  account's home; if the User Home service is off, the folder setting points it elsewhere.
- Two devices editing at once resolve last-writer-wins per item. A like is never lost silently: a
  failed sync keeps the local like and retries.
- File Station is Synology's documented, stable API, unlike the Photos web API, so its endpoints
  are used from the documentation rather than reverse-observed. A first live write is still
  verified against the NAS before the feature is trusted.

## Alternatives considered

- **Native favorite (`SYNO.Foto.Favorite`).** Rejected for now: it writes into Photos, covers the
  personal space only (there is no `SYNO.FotoTeam.Favorite`), and reuses the user's real
  favorites.
- **A tag (`Browse.GeneralTag`).** Rejected for now: also a write into Photos, and the tag would
  likely be library-wide and visible to every account.
- **Local only.** Rejected: the owner wants the likes on the NAS, and the account-change wipe
  would erase a local-only set.

## Amendments

- 2026-09-03, plan 009: **sync is serialized and transactional, and a file the app cannot read is
  never overwritten.** Syncs run one at a time; a request that arrives during a run marks it dirty
  and the run goes once more, so overlapping toggles collapse into at most one follow-up. The
  read-merge-write happens inside one Room transaction with no `DELETE` in the middle, because the
  previous clear-then-insert lost a toggle that committed between the read and the clear. A
  `likes.json` that exists but does not parse ends the sync as a failure, reported to the user once
  per session, and nothing is pushed: the earlier code read such a file as empty and uploaded over
  it. Keys read from the file are parsed, not destructured; an entry that is not `NAMESPACE:id` is
  skipped and counted, never persisted. The `pendingSync` column is gone (schema 5, plan 008):
  last-writer-wins by `updatedAt` already carries a failed push into the next sync. The likes-folder
  setting is reset on an account change, since it belongs to the previous account
  ([[006-one-account-per-install]]).

## Related

[[001-web-api-is-the-only-source]], [[002-personal-and-shared-space]],
[[006-one-account-per-install]]
