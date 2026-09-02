# 001 - API observation

- **Status:** Partly done, blocked on U6
- **Source:** plan.md §11
- **Depends on:** nothing
- **Blocks:** 002, 003
- **Decisions:** [001](../decisions/001-web-api-is-the-only-source.md)
- **Progress:** 8 / 9

## Goal

Every unknown in plan.md §11 answered against the live NAS, written into
`documents/research/photos-web-api.md` as a specification later plans build from. Photos 1.9.1
on DSM 7.3.2 is the version of record; anything observed is stamped with it, because these
endpoints are undocumented and version-sensitive.

One session, then stop. The companion repo's observation pass is the precedent: it answered its
question and ended rather than exploring.

## Tasks

### Run

- [x] Run `scripts/observe-photos-api.sh` against the NAS and keep its output directory.
- [x] Confirm the script logged no password, no session id and no `sharing_link`.

### Write up

- [x] `SYNO.API.Info` dump reduced to the apis this app will use, with version ranges (U1).
- [x] Timeline endpoint: name, method, version, parameters, response shape (U2).
- [x] Item list: whether a time range is accepted, parameter names if so (U3).
- [x] Thumbnail endpoint: parameters, and whether a GET carrying the session id serves bytes,
      which is what Coil needs (U4).
- [x] Taken time unit, seconds or milliseconds, from a known photo (U5).
- [ ] `SYNO.FotoTeam.*` visibility against a restricted account (U6). The companion repo notes
      `test-user` exists and owns nothing, so this may need a folder shared to it first.
      > Blocked: the first run was as the owner. Needs a second run of the same script as
      > `test-user`, then a comparison of `item-count-FotoTeam.json` against 77,436.
- [x] Timeline day fields: NAS timezone or UTC, from a photo taken near midnight (U7).

## Acceptance criteria

- [x] `documents/research/photos-web-api.md` is committed and answers U1 to U7, or states
      plainly which are still open and why.
- [x] The allowlist of `(api, method, version)` triples plan.md §2 requires is written down,
      with a one-line purpose per entry.
- [x] No capture from the NAS is committed in any form.

## Follow-up run

Plan.md §11 is answered except U6, but the run also raised two questions the next plans would
otherwise guess at, so the script gained probes for them and one more run as the owner is
wanted alongside the `test-user` run: whether `start_time`/`end_time` ends are inclusive and cut
days the way the histogram does (decides decision 005's amendment), and whether the thumbnail
GET accepts the session as a `Cookie: id=` header so URLs carry no secret (decides plan 004's
image loader). Both are read calls already on the allowlist.

## On completion

1. Tick every box, set Status to Done, update Progress.
2. Update `index.md`.
3. Amend plan.md §11 to record each answer, or to narrow what remains unknown.
