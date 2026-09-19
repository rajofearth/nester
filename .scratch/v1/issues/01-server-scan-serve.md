# 01: Server scans and serves

Status: done
Blocked-by: none

nester-core scans a folder into a SQLite WAL index and hashes files with BLAKE3. This is largely done in the scaffold. nester-server exposes it: health, folders, entries (with ?since=N), and download endpoints behind bearer-token auth.

What done looks like:

- scan + index + hash verified by unit tests in nester-core
- nester-server routes registered with utoipa annotations
- auth middleware rejects bad tokens, compares constant-time
- exported OpenAPI JSON in the repo

Acceptance criteria: a running server answers GET /folders and GET /entries with a valid token, 401 without, and the OpenAPI JSON matches the routes.

Notes: the utoipa contract is the API source of truth. The Kotlin client is hand-written against it.
