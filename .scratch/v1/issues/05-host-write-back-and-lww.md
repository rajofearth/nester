# 05: Host write-back and LWW

Status: done
Blocked-by: none

Server applies phone changes to disk and index: accept an uploaded file, write it into the folder, compare mtimes for last-writer-wins, update the files table, bump sequence, rescan after apply.

What done looks like:

- applied upload lands on disk with the phone's mtime, index row matches [done]
- LWW comparison per ADR 0004: newer mtime wins, stale upload rejected 409 [done, tested]
- rescan after apply converges index and disk [done, watcher/periodic rescan covers the written file]
- conflict case covered by a test on the host side [done, stale_upload_rejected_with_409 + newer_upload_overwrites]

Acceptance criteria: two versions of the same path pushed while apart converge to the newer one, with the sequence advancing so the other device pulls the result.

Notes: records the deliberate LWW deviation from the product context report.
