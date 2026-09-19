# 07: GPUI window and tray

Status: in-progress
Blocked-by: none

Host window (GPUI) matching the opencode-monitor-utility design language: palette, rounded card, blurred background. Window done; tray-icon deferred.

What done looks like:

- window shows a scannable QR of the pairing string [done, qrcode matrix rendered as div grid]
- folder picker adds a synced folder [done, rfd dialog; applies on restart with a visible hint]
- tray icon reflects sync state, menu allows open/quit [todo, opencode-monitor-utility tray.rs is the proven pattern to port]
- one process: server runtime and GPUI loop coexist [done, server on background tokio thread, GPUI on main]

Acceptance criteria: on Windows ARM64, pair the phone by pointing the camera at the host window, no printed-string fallback needed.

Notes: ADR 0003. QR rendering and folder picker are hand-rolled.
