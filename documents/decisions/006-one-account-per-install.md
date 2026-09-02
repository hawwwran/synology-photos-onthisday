# 006 - One account per install, and what an account change destroys

- **Status:** Accepted
- **Date:** 2026-09-02
- **Origin:** Chosen (project owner, 2026-09-02)

## Context

A household NAS has several Photos accounts. An account switcher was on the table so a shared
tablet could show each person their own day.

## Decision

One account per install. Signing in as a different account is a full reset, not a switch.

On sign-out or a change of account, before any of the new account's data is shown:

- the day histogram and running totals are deleted,
- cached item rows are deleted,
- the thumbnail disk cache is cleared,
- the stored session id is cleared.

The trusted-device id may survive, since it belongs to the device rather than to an account.

## Consequences

- No cross-account leakage is possible, because there is no second account's data to leak. One
  household member cannot scroll into another's photos through a stale cache.
- The reset is the expensive path: a re-signed-in account re-downloads its thumbnails. Correct
  trade, and it only happens on an actual account change.
- Storage keys do not need an account dimension, which removes a whole class of "forgot to
  scope the query" bug. The account is instead recorded once so a change can be *detected*.
- A shared tablet with two users means re-entering a password each time. Accepted: the phone
  case is the product, and [[003-authentication-and-sessions]] already re-prompts on expiry.
- Reversible. Adding a switcher later means adding an account column and keying the caches,
  which is work but not a redesign.

## Amendments

- 2026-09-02: there are no running totals since [[005-day-index-on-device]]'s amendment of the
  same day. The wipe list is the day histogram, the item rows, the thumbnail cache and the
  session; nothing else is stored.
- 2026-09-02, plan 004: the thumbnail disk cache is cleared on a change of account, **not** on a
  same-account sign-out. Plan 004 requires that signing out and back in as the same person does
  not re-download thumbnails, so the Coil cache survives sign-out; it is keyed independently of
  the session ([[005-day-index-on-device]]). No leak follows: a different account's sign-in wipes
  the thumbnail cache before any of its data is shown, which is the moment the original context
  section is written for. The day histogram and item rows are still cleared on sign-out as
  before. `SessionManager` splits this into two wiper groups: one for the index (sign-out and
  account change) and one for the thumbnail cache (account change only).

## Related

[[003-authentication-and-sessions]], [[005-day-index-on-device]]
