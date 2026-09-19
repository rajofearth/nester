# 0003 GPUI host shell

Date: 2026-09-19
Status: accepted

## Context

The host app needs a small UI: an onboarding window showing the pairing QR, a folder picker, and a tray icon with sync status. Options were a second UI process (tray plus browser page), Tauri, or GPUI in the same process as the server.

## Decision

Pure Rust, one process: axum server on tokio, plus GPUI for the window, plus tray-icon for the system tray. Tauri is rejected.

## Rationale

The owner already has three GPUI projects (ply, opencode-monitor-utility, raycast-dictation-clone), so GPUI carries no learning cost and the patterns are known to work on the target machines, including Windows ARM64. Tauri would introduce a second UI paradigm, a webview runtime, and a build chain that exists only for one small window. Tray plus browser would split the app into two processes and push users through a browser to pair, which is clunkier than a native window showing the QR. GPUI runs its own event loop alongside tokio; the server runs on its own runtime and the two communicate over channels.

## Consequences

One binary, one install, no webview. GPUI is a younger framework with a smaller ecosystem, so some controls (folder picker, QR rendering) are hand-rolled. That is acceptable for a window this small. If the host UI ever grows, the server and UI stay decoupled through the same channels, so a different shell can replace GPUI without touching sync.
