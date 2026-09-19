# Agents

Notes for AI agents working in this repo.

## Repo layout

- crates/nester-core: scan, index (SQLite WAL per folder), BLAKE3 hashing
- crates/nester-server: axum routes, bearer-token auth middleware, utoipa OpenAPI
- apps/host: the host binary, runs the server plus a GPUI window and tray icon
- apps/mobile: Kotlin + Jetpack Compose Android app
- docs/adr: numbered ADRs. docs/v1-scope.md and docs/architecture.md
- .scratch/v1/issues: local-file ticket tracker
- CONTEXT.md: glossary. If you change what a term means, update it there first

## Commands

Rust (run from workspace root):

```
cargo fmt --all
cargo clippy --workspace --all-targets
cargo test --workspace
cargo run -p nester-host -- --folder <path>
```

Mobile (from apps/mobile):

```
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Machine constraints on the owner's Windows ARM64 PC:

- Android SDK at P:\Applications\Android\Sdk, JDK 17 pinned
- No Android emulator is possible. Test on physical devices over adb
- Robolectric and Roborazzi are unstable locally. Do not add them locally. Plain JVM unit tests only. Robolectric is allowed in Linux CI later
- gradle.properties is tuned for low RAM (-Xmx4g, workers.max=4, kotlin in-process). Do not undo these
- The foojay resolver is set up so a JDK mismatch never blocks builds. Do not remove it

## Conventions

- No comments in code unless asked
- Changing sync behavior requires updating the matching ADR in docs/adr/. If no ADR covers it, write one or raise it with the team
- Changing terminology requires updating CONTEXT.md
- Tickets live in .scratch/v1/issues/, one markdown file per ticket with Status and Blocked-by fields. When work on a ticket is done, change its Status to done. When a ticket is completed, remove its number from the Blocked-by list of any ticket blocking on it
- The utoipa OpenAPI contract on the Rust server is the source of truth for the API. If routes change, regenerate the exported OpenAPI JSON and update the hand-written Kotlin client to match

## Warnings

- Never commit apps/mobile/local.properties
- Never commit pairing tokens, generated secrets, or anything from .scratch that contains tokens
- Do not add Robolectric or Roborazzi to local test setups
- No Linux host support. Do not add Linux-specific host code or CI jobs for the host
