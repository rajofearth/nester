# v1 scope

## In scope

- Host on Windows and macOS, pure Rust, one process
- LAN WiFi sync between host and one or more Android devices
- QR pairing carrying host IP, port, and bearer token
- Multiple synced folders, Syncthing-style, Pictures suggested by default
- Two-way mirror sync, last-writer-wins conflicts on mtime
- Full index on the phone, on-demand content with LRU eviction
- Delta pulls by sequence, tus-style uploads, ranged downloads
- Host-side image thumbnails
- OpenAPI contract from utoipa as the API source of truth

## Held out

- Accounts: QR pairing covers identity in v1; accounts arrive with billing later
- RevenueCat: no billing in v1, plans will be device-count tiers when it exists
- Tailscale: remote access is out of the v1 threat model and blocks nothing on LAN
- iOS: v1 ships Android only; the portability seam is the OpenAPI contract, not shared code
- Linux host: owner hardware and CI budget are Windows + macOS; can be added later
- Share-out URLs: serving files to third parties is a different trust boundary than device sync
- Video streaming: ranged GETs already exist; transcode-and-stream quality work is deferred
- Version vectors: LWW on mtime is enough for two devices; revisit if real conflicts hurt
- CDC chunking: whole-file BLAKE3 hashing is fast enough at personal-library sizes
- Multiple users per host: one host, its paired devices, no multi-tenant routing in v1
- Web dashboard: the phone app is the only client; a browser UI is a separate surface to maintain
