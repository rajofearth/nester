# nester

Nester is personal cloud storage that runs on your own computer. A Rust host app syncs folders from your PC or Mac to an Android phone app over the local WiFi. Nothing runs on nester's servers: no accounts, no relay, no billing. Pairing is a QR code that carries the host's IP, port, and a bearer token, and after that the two devices talk directly.

The sync model is a two-way mirror. The phone holds the full index of the host's folders (paths, sizes, hashes, thumbnails) but downloads file contents on demand, evicting old files from local storage with an LRU policy. Changes propagate both ways: edits on the host get pulled by the phone, camera roll photos get pushed up to the host. Conflicts resolve last-writer-wins on mtime in v1, a conscious tradeoff recorded in ADR 0004.

## v1 status

What works now (tested):

- Scan, index, and hash a folder into SQLite (nester-core), watcher + periodic rescan
- HTTP server with token auth, delta pulls, Range downloads, uploads with last-writer-wins guard, deletions (nester-server)
- Host app: GPUI window in the project's card style, QR pairing, folder add/remove, live scan status, server on a background runtime
- Android app: QR pairing, folder browsing, delta pulls, downloads with progress, camera-roll backup with streaming uploads, file deletion both ways

Still open: thumbnails (ticket 08), tray icon, explicit renames, upload resume. Held out until later: accounts, RevenueCat billing, Tailscale/remote access, iOS, Linux host, share-out URLs, video streaming, version vectors, CDC chunking. See docs/v1-scope.md.

## Repo layout

```
nester/
  Cargo.toml            workspace
  crates/
    nester-core/        scan, index (SQLite), hashing
    nester-server/      axum routes, auth middleware, OpenAPI (utoipa)
  apps/
    host/               Rust host binary: server + GPUI window + tray
    mobile/             Kotlin + Jetpack Compose Android app
  docs/
    v1-scope.md         in scope / held out
    architecture.md     components and data flow
    adr/                numbered decision records
  .scratch/v1/issues/   local-file ticket tracker
  CONTEXT.md            glossary, source of truth for terminology
  AGENTS.md             notes for AI agents working in this repo
```

## Quick start

Host:

```
cargo run -p nester-host -- --folder <path>
```

The host starts serving and prints a pairing string. Show it as a QR code (onboarding window in ticket 07) and scan it from the phone.

Mobile: open apps/mobile in Android Studio and run on a physical device. There is no emulator on the dev machine; test over adb on real hardware. The Android SDK lives at P:\Applications\Android\Sdk and the JDK is pinned to 17.

Docs: see docs/ and CONTEXT.md.
