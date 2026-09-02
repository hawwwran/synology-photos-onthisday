# 004 - Access path and TLS

- **Status:** Accepted
- **Date:** 2026-09-02
- **Origin:** Chosen (project owner, 2026-09-02)

## Context

The app carries a NAS account password on every login, so the transport is not negotiable. The
options were a DDNS hostname with a real certificate, LAN-only with a self-signed certificate,
or a VPN.

Android has an opinion here too: since API 28 cleartext HTTP is blocked by default, and a
self-signed certificate needs either a user-installed CA or pinning code in the app.

## Decision

HTTPS to a Synology DDNS hostname carrying a Let's Encrypt certificate issued through DSM.

- No `usesCleartextTraffic`, no `network-security-config`, no custom `TrustManager`, no pinning.
  Android's default trust store validates the certificate and the app writes no TLS code.
- The base URL is configuration, entered once, so a local address still works during
  development. It is validated as `https://` and an `http://` URL is refused with the reason.

### NAS prerequisites, done once by hand

1. DDNS hostname: Control Panel, External Access, DDNS.
2. Certificate for that hostname: Control Panel, Security, Certificate. DSM renews it.
3. Reverse proxy or a forwarded port for DSM HTTPS.

## Consequences

- Zero TLS code in the app, and no pinning to maintain when DSM rotates the certificate. This
  is the reason for the choice: pinning against an auto-renewing certificate is a bricked app
  waiting for a renewal.
- The NAS is exposed to the internet on one port. That is a NAS hardening question rather than
  an app one: auto-block, a non-default port, and DSM's firewall are where it is answered.
- Off-LAN testing is blocked until the three prerequisites are done. Development can proceed
  against a LAN address in the meantime, which is why the base URL is configuration.
- QuickConnect is not used. Its relay is undocumented for third parties, and the sharing links
  companion's research found are QuickConnect URLs carrying live passphrases, which is a good
  reminder that the relay path is not a place to improvise.

## Alternatives considered

- **LAN only, self-signed.** Fastest to start. Rejected: it defeats the product, which is
  looking at memories away from home, and it forces either a user-installed CA or trust-all
  code that would then be one refactor away from shipping.
- **Tailscale or WireGuard.** Strongest security, nothing exposed. Rejected: every household
  member would need a VPN client running for the app to work, and the app is useless when the
  tunnel is down. Still the right answer if the exposed port ever becomes a problem.

## Amendments

- 2026-09-02: The hostname is a domain the owner already holds, pointed at the home public IP,
  with a non-default router port forwarded to DSM's HTTPS port. Not a Synology DDNS name. The
  forward was verified reachable from outside the LAN on this date; DSM still served its default
  self-signed certificate, so nothing could connect yet. Consequence for prerequisite 2: DSM's
  Let's Encrypt integration validates an owned domain over HTTP-01, so port 80 has to reach the
  NAS at issuance and at every renewal. A Synology DDNS name would have avoided that, at the cost
  of a second hostname. The hostname and port themselves are not recorded here: the repo may
  become public, and they are configuration, not architecture.
- 2026-09-02, later the same day: the own domain's port 80 is not forwarded to the NAS and the
  owner does not want it to be, which rules out DSM's Let's Encrypt integration for that name.
  Resolved with a different topology: an nginx that already serves another of the owner's
  domains from an LXC container on the router, with automatic Let's Encrypt renewal, gained a
  vhost that reverse-proxies to DSM's HTTPS port on the LAN. Verified from outside the LAN the
  same day: trusted chain, HTTP redirected to HTTPS, `SYNO.API.Info` answering through the
  proxy. Consequences: prerequisites 1 to 3 above are replaced by "a vhost on the existing
  proxy", DSM's own port need not be exposed at all, and the app is unchanged. New open
  question: DSM auto-block keys on the client address and sees only the proxy's, so a few
  failed logins from anyone would ban the proxy for the whole household. Accepted as is by the
  owner the same day (Q4 in the index): the household is small and a ban lifts by itself.

## Related

[[003-authentication-and-sessions]]
