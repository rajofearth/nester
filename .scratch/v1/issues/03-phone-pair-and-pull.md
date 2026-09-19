# 03: Phone pairs and pulls

Status: todo
Blocked-by: none

Android app: QR scan screen (CameraX + MLKit), settings storage for host IP, port, token, entries list, file download via OkHttp.

What done looks like:

- scan screen parses the pairing QR and stores settings
- entries list renders from a full delta pull (?since=0)
- tapping an entry downloads the body and opens it
- delta pulls with since=N work across repeated runs

Acceptance criteria: on a physical device over adb, pair to a running host, see the folder listing, open a file.

Notes: no emulator on the dev machine, test on physical devices. Plain JVM unit tests only locally, no Robolectric.
