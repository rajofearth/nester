# 05: Host write-back and LWW

Status: todo
Blocked-by: 04

Server applies phone changes to disk and index: accept an uploaded file, write it into the folder, compare mtimes for last-writer-wins, update the files table, bump sequence, rescan after apply.

What done looks like:

- applied upload lands on disk with sane mtime, index row matches
- LWW comparison per ADR 0004: newer mtime wins, loser overwritten
- rescan after apply converges index and disk
- conflict case covered by a test on the host side

Acceptance criteria: two versions of the same path pushed while apart converge to the newer one, with the sequence advancing so the other device pulls the result.

Notes: records the deliberate LWW deviation from the product context report.
