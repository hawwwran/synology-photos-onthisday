# 001 - API observation

- **Status:** Not started
- **Source:** plan.md §11
- **Depends on:** nothing
- **Blocks:** 002, 003
- **Decisions:** [001](../decisions/001-web-api-is-the-only-source.md)
- **Progress:** 0 / 9

## Goal

Every unknown in plan.md §11 answered against the live NAS, written into
`documents/research/photos-web-api.md` as a specification later plans build from. Photos 1.9.1
on DSM 7.3.2 is the version of record; anything observed is stamped with it, because these
endpoints are undocumented and version-sensitive.

One session, then stop. The companion repo's observation pass is the precedent: it answered its
question and ended rather than exploring.

## Tasks

### Run

- [ ] Run `scripts/observe-photos-api.sh` against the NAS and keep its output directory.
- [ ] Confirm the script logged no password, no session id and no `sharing_link`.

### Write up

- [ ] `SYNO.API.Info` dump reduced to the apis this app will use, with version ranges (U1).
- [ ] Timeline endpoint: name, method, version, parameters, response shape (U2).
- [ ] Item list: whether a time range is accepted, parameter names if so (U3).
- [ ] Thumbnail endpoint: parameters, and whether a GET carrying the session id serves bytes,
      which is what Coil needs (U4).
- [ ] Taken time unit, seconds or milliseconds, from a known photo (U5).
- [ ] `SYNO.FotoTeam.*` visibility against a restricted account (U6). The companion repo notes
      `test-user` exists and owns nothing, so this may need a folder shared to it first.
- [ ] Timeline day fields: NAS timezone or UTC, from a photo taken near midnight (U7).

## Acceptance criteria

- [ ] `documents/research/photos-web-api.md` is committed and answers U1 to U7, or states
      plainly which are still open and why.
- [ ] The allowlist of `(api, method, version)` triples plan.md §2 requires is written down,
      with a one-line purpose per entry.
- [ ] No capture from the NAS is committed in any form.

## On completion

1. Tick every box, set Status to Done, update Progress.
2. Update `index.md`.
3. Amend plan.md §11 to record each answer, or to narrow what remains unknown.
