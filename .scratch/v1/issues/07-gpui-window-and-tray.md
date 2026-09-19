# 07: GPUI window and tray

Status: todo
Blocked-by: none

Host onboarding window (GPUI): display the pairing QR, folder picker, and sync status. Plus a tray-icon entry with status menu.

What done looks like:

- window shows a scannable QR of the pairing string
- folder picker adds a synced folder, server picks it up
- tray icon reflects sync state, menu allows open/quit
- one process: server runtime and GPUI loop coexist

Acceptance criteria: on Windows ARM64, pair the phone by pointing the camera at the host window, no printed-string fallback needed.

Notes: ADR 0003. QR rendering and folder picker are hand-rolled.
