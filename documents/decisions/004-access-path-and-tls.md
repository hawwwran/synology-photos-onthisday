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

## Related

[[003-authentication-and-sessions]]
