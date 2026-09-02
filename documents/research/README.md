# Research

Observations of the Synology Photos web API, and anything else established by looking rather
than by deciding. Written-up findings are committed; raw captures never are.

## Why captures are not committed

A login response carries a live session id and token. An album or sharing response carries the
share passphrase and a `sharing_link` of the form
`https://<account>.quickconnect.to/mo/sharing/<passphrase>`, and those are **working
credentials**: anyone holding one can view that album from the internet without signing in.
One album-list call returns as many internet-reachable secrets as the account has shared
albums.

So `documents/research/observation-*/` is gitignored, and a write-up quotes field *names* and
shapes rather than values. `scripts/observe-photos-api.sh` redacts before it writes, but the
gitignore is the guarantee, not the redaction.

The same reasoning, and the original finding, are in the companion repo:
`~/git/synology-photos-companion/documents/research/api-observation.md`.

## Companion repo research worth reading

Not duplicated here. Relevant parts:

- `synology-schema/users-and-sharing.md` - `user_info.id` is **not** the DSM uid, and they
  diverge on this NAS. Any future work that touches user ids needs this.
- `synology-schema/index.md` - library scale: 228,695 units, 4,470 folders. The reason paging is
  not optional.
- `api-observation.md` - `entry.cgi` call shape, `SYNO.Foto` versus `SYNO.FotoTeam`, the
  `offset`/`limit` paging shape, and the sharing-link finding above.

## Files

| File | What it covers |
| --- | --- |
| `photos-web-api.md` | Written by plan 001. The endpoint specification this app builds against, stamped with the Photos version observed. |
