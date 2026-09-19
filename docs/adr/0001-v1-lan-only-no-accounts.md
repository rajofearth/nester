# 0001 v1 is LAN only with no accounts

Date: 2026-09-19
Status: accepted

## Context

Nester is personal cloud storage on your own hardware. The product context report sketched accounts, billing, and remote access as part of the picture. Every one of those adds a server-side component, and the core promise of nester is that nothing runs on nester's servers. Building auth infrastructure before there is a working sync loop is effort spent on the wrong layer.

## Decision

v1 speaks only over LAN WiFi. A device pairs by scanning a QR code that carries the host IP, port, and a bearer token. That token is the only identity. No accounts, no Tailscale, no RevenueCat in v1. Accounts and device-count plan tiers arrive later through RevenueCat.

## Consequences

Deferred: account registration, plan enforcement, remote access outside the home network, multi-user hosts. The QR token unblocks all of the actual sync work immediately; pairing is an afternoon of work instead of an auth system. The cost is that the phone only works at home in v1, and losing the token means re-pairing. Token handling must be careful: constant-time compare on the server, never committed or logged. When accounts arrive, the pairing token becomes a bootstrap credential rather than the whole identity, so the QR flow survives.
