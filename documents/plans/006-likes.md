# 006 - Liking photos, stored on the NAS

- **Status:** Done. File Station route (decision 008). Verified on device 2026-09-03: like/unlike
  round-trips through `likes.json` on the NAS (Download and Upload both ok), liked-first shows.
- **Source:** owner request, 2026-09-02.
- **Depends on:** 005, and [decision 008](../decisions/008-writing-likes-to-the-nas.md) (ratified).
- **Blocks:** nothing.
- **Decisions it touches:** [001](../decisions/001-web-api-is-the-only-source.md) and plan.md §2
  (both say Synology Photos is read-only), [002](../decisions/002-personal-and-shared-space.md)
  (two namespaces), [006](../decisions/006-one-account-per-install.md) (account-change wipe).
- **Progress:** code complete; acceptance criteria await the live test

## Goal

A person can like a photo or a video in the app. The like is kept **on the NAS**, so it survives
a reinstall and an account-change wipe, and can be seen from another device signed in as the same
account. Liked items are surfaced first in the day view, for a nicer presentation.

## The one decision everything hangs on

This app is built to never write to the NAS. Decision 001 and plan.md §2 say it plainly:
"Synology Photos is read-only. No write, rename, delete, upload, share or settings call." A like
stored on the NAS is a **write**. So this feature cannot be built without reversing, or narrowly
carving out, that rule.

That is a decision for the owner, not something this plan settles. Task 0 is to write **decision
008**, which either:

- **(a)** keeps Photos read-only and stores likes in an app-owned file elsewhere on the NAS (a
  write to the filesystem, not to Photos), or
- **(b)** allows exactly one narrow, audited write into Photos (a favorite or a tag on the item),
  reversing §2 for that single operation and nothing else.

Everything below is written so the storage choice can be made after task 0 and the observation in
task 1, without redoing the rest.

## The ways to store a like, with trade-offs

What the observed API offers (`documents/research/photos-web-api.md`, api list):

| Api | Namespaces | Notes |
| --- | --- | --- |
| `SYNO.Foto.Favorite` v1-2 | **personal only** | No `SYNO.FotoTeam.Favorite` exists, so native favorites cannot cover shared-space items |
| `SYNO.Foto.Browse.GeneralTag`, `SYNO.FotoTeam.Browse.GeneralTag` v1-2 | both | Tags exist in both spaces; the add/remove method and whether tags are per-user or library-wide are unobserved |
| `SYNO.Foto.Search.Filter` v1-4 | both | May let the item list filter by favorite or by tag server-side, which would put liked-first without client sorting; unobserved |
| `SYNO.FileStation.*` | filesystem | Present on DSM (outside the Foto namespace). An app-owned file on a share, written over FileStation, keeps Photos itself read-only |

### Option A - native Synology favorite (`SYNO.Foto.Favorite`)

- **For:** it is exactly "liked", shows up as a favorite in the Synology Photos app and web, and
  anything else the household uses (slideshows, the companion archiver) can present favorites
  first. This matches the owner's "tag on the photos, for better presentation" intent best,
  because the mark lives on the photo, not only in this app.
- **Against:** it is a write into Photos, so it reverses §2. It is **personal-space only**, so a
  shared-space photo (where most family photos live, [[002]]) cannot be favorited this way. It
  also hijacks the user's real Synology favorites, which they may already use for something else.

### Option B - a tag (`Browse.GeneralTag`)

- **For:** works in both namespaces, lives on the photo, and is queryable ("list items with tag
  X") so liked-first can be server-side. A dedicated tag like `otd-liked` avoids touching the
  user's favorites.
- **Against:** still a write into Photos (reverses §2). The add/remove method is unobserved. Tags
  in Photos are likely library-wide, so the tag and the liked set may be visible to, and editable
  by, every account, which may or may not be wanted. Pollutes the shared tag namespace.

### Option C - an app-owned file on the NAS (FileStation sidecar)

- **For:** **Photos stays strictly read-only** (decision 001 and §2 untouched). One small file the
  app owns (JSON or SQLite) under a chosen folder holds `{namespace, unit_id} -> liked`. Uniform
  across personal and shared items. Survives reinstall and the account-change wipe. Reversible and
  self-contained: deleting the file removes the feature with no trace on the photos.
- **Against:** it is still a write to the NAS, just to the filesystem rather than to Photos, so it
  needs its own small carve-out and the FileStation upload/download endpoints observed. The likes
  are **not** visible inside Synology Photos or other tools, so "better presentation" only applies
  within this app. Concurrency: two devices editing the file need a read-modify-write with a
  version check, or they accept last-writer-wins.

### Option D - local only (Room), rejected

Simplest and no writes, but the owner explicitly wants the like on the NAS, and the
account-change wipe ([[006]]) would erase local-only likes. Kept here only to say why it is not
the answer.

## Recommendation, to react to

Two coherent shapes, depending on what matters more:

1. **If the like should be visible beyond this app** (in Synology Photos, slideshows, the
   archiver), go with **Option B, a dedicated tag** in both namespaces, and reconcile with
   `Search.Filter` for liked-first. Accept the §2 carve-out for a single tag add/remove. Use the
   **native favorite (A)** only if the owner is happy for likes to be personal-space-only and to
   share the user's real favorites.
2. **If keeping Photos untouched matters more than cross-tool visibility**, go with **Option C, a
   FileStation sidecar**, which preserves the read-only invariant for Photos and works uniformly,
   at the cost of the likes being app-private.

My lean: **Option C** for a clean first version (it does not weaken the app's central safety
promise and works for shared photos), with **Option B** as the upgrade if you later want the
likes to show up in Synology's own apps. The observation in task 1 may change this, for example
if native favorite turns out to cover shared items after all, or if tag-add is trivial and
per-user.

## Storage and sync model (independent of the option)

Whichever store is chosen, the app keeps a **local like cache in Room** as the source of truth for
instant, offline toggling, and treats the NAS as the durable copy:

- Room table `like(namespace, unit_id, liked, updated_at, pending_sync)`.
- A tap toggles the row at once (optimistic), and enqueues a sync.
- Sync **pushes** pending changes to the NAS store and **pulls** the NAS like set on refresh, so a
  like made on another device appears here. Conflict policy: last-writer-wins by `updated_at`,
  which is safe because like/unlike is idempotent and non-destructive.
- Account-change wipe ([[006]]) clears the **local** cache only; the NAS copy is re-pulled on the
  next sign-in. This is the whole reason the like lives on the NAS.

## Safety, because this is the first write

Writing must be as disciplined as reading is:

- A **separate write allowlist** of `(api, method, version)` triples, holding only the like
  operations. Anything else still throws before a request is built.
- Every write is **idempotent, reversible, and non-destructive**: it only ever sets or clears a
  like (a favorite, a tag, or a line in the app's file). It never deletes a photo, never renames,
  never edits pixels, never touches album or sharing state.
- **No response body logged**, same as reads.
- The write-endpoint observation (task 1) is done on **one disposable test item**, toggled and
  then reverted, never on a destructive method.

## Tasks

### 0. Decide

- [x] Wrote **decision 008**: likes are written to the NAS as one app-owned File Station file
      (Option C). Synology Photos stays strictly read-only. Owner ratified 2026-09-02.

### 1. Observe the write path (a plan-001-style pass, write-safe)

- [ ] `SYNO.Foto.Favorite`: the set and unset method, its parameters, what it returns, and whether
      a favorite made here shows in `Browse.Item` (a favorite flag in `additional`) or needs a
      separate query.
- [ ] Whether any favorite mechanism covers `SYNO.FotoTeam` items at all.
- [ ] `Browse.GeneralTag` / the tag-add path: how a tag is added to and removed from an item, in
      both namespaces, and whether tags are per-user or library-wide.
- [ ] `Search.Filter`: whether the item list can be filtered by favorite or by tag, so liked-first
      can be server-side.
- [ ] For the sidecar option: `SYNO.FileStation.*` upload, download and create-folder shapes, and
      where the app's file may live.
- [ ] Write the findings into `documents/research/photos-web-api.md`; confirm the test item was
      reverted and no capture is committed.

> The File Station route needs no Photos-endpoint observation. Its versions were read from
> `SYNO.API.Info` (Upload v2-3, Download v1-2); the Upload/Download parameter shapes are from
> Synology's documented File Station API. The one live check is tomorrow's test: that the account
> can write `likes.json` to the configured folder.

### 2. Write layer

- [x] A write allowlist and a small write client, with the same envelope decoding, typed failures
      and body-free logging as the read client.
- [x] Idempotent like and unlike against the chosen store.

### 3. Local model

- [x] Room `like` table and a repository exposing the like state per item as a flow.
- [x] Optimistic toggle and a pending-sync marker.
- [x] Clear-on-account-change (local only), exercised by a test.

### 4. Sync

- [x] Push pending likes to the NAS; pull the NAS like set on refresh and reconcile.
- [x] Conflict policy (last-writer-wins by `updated_at`) with a test.
- [x] A failed sync keeps the local like and retries; it never silently drops a like.

### 5. UI

- [x] A like toggle (heart) in the viewer, beside share and download.
- [x] A like indicator on grid cells, and a tap target if it earns one.
- [x] Liked-first ordering within each year of the day view; decide whether to add a "liked only"
      filter.

### 6. Hardening

- [x] A test asserting the write allowlist holds only like operations and nothing destructive.
- [x] A test asserting account change clears the local like cache but not the NAS copy.
- [x] The write path is shown, by name, to touch only the like and nothing else.

## Acceptance criteria

- [x] Liking an item writes `likes.json` to the NAS; sync reads it back (verified on device: File
      Station Download and Upload both ok, "sync ok").
- [ ] Visible on a second device after a refresh (not tested; the mechanism is the shared NAS file).
- [ ] A reinstall, or an account-change wipe and sign back in, restores the likes from the NAS
      (mechanism in place; not exercised on device yet).
- [x] Liked items appear first in the day view.
- [x] Every write the app can make maps, by name, to a like operation; no destructive triple
      exists in the write allowlist (`HardeningTest`).
- [x] Photos that are not liked are never modified on the NAS in any way (only `likes.json` is
      written; Photos endpoints stay read-only).

## Batch actions (added 2026-09-03, owner request)

Long-press a grid tile to enter selection mode; tapping tiles then toggles them. A contextual top
bar shows the count, an X to cancel, and heart, share and download. Heart likes all selected (and
syncs); download saves each original to the gallery; share hands the originals to the Android
share sheet as a multi-file `ACTION_SEND_MULTIPLE`. Verified on device: selection, batch like
(synced to `likes.json`), and the contextual bar. Batch download and share reuse the single-item
save and share paths.

## What is built (2026-09-02)

- Write allowlist split from the read allowlist; only `SYNO.FileStation.Upload` v2 is a write,
  `SYNO.FileStation.Download` v2 is a read. `FileStationClient` does both, allowlist-checked, no
  body logged.
- `likes.json` on the NAS via `LikesNasStore`; the local cache is Room `item_like`; `LikeRepository`
  toggles optimistically and reconciles last-writer-wins (`LikesMerge`), and is an account-data
  wiper (local only).
- Heart toggle in the viewer, a heart badge on liked grid cells, liked-first ordering per year, and
  an editable likes-folder setting (default `/home/OnThisDay`).
- Tests: like key, file round-trip, merge last-writer-wins and order-independence, and the write
  allowlist hardening. 64 JVM tests pass.

## Open questions

- **Per-user or shared likes?** Native favorite is personal; a tag or sidecar can be either. Does
  the household want one shared "family favorites" set, or each person their own?
- **Which store?** A, B or C, settled by task 0 and task 1.
- **Do likes made outside the app count?** With native favorite (A) they would show here for free,
  which is a point in its favour.
- **Concurrency** for the sidecar (C): read-modify-write with a version check, or accept
  last-writer-wins?

## On completion

1. Fill the task count into `Progress:` once the approach is chosen, tick as verified.
2. Update `index.md`.
3. Record the chosen store and its write allowlist in `documents/research/photos-web-api.md`.
