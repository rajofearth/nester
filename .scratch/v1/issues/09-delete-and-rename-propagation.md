# 09: Delete and rename propagation

Status: in-progress
Blocked-by: none

Deletions propagate correctly in both directions. Renames do not exist as an explicit operation yet; on both sides a rename looks like a delete + add, which is correct but loses mtime. Explicit rename support is deferred.

What done looks like:

- delete on host: scan marks entry deleted, phone delta-pull drops it [done]
- delete on phone: DELETE endpoint, host removes file + marks index, tested [done]
- rename on host: appears as delete + add in the delta [accepted v1 shape, revisit if users rename often]
- rename on phone: not offered in the UI [todo if needed]
- re-create the same path after delete converges cleanly [covered by scan + upsert WHERE clause]

Acceptance criteria: a scripted sequence of deletes and renames on both sides ends with identical indexes on host and phone.

Notes: renames must not re-upload whole files if content is unchanged; hash comparison catches this.

Progress (2026-09-19): phone side of deletion started — per-file Delete action with confirm dialog in EntriesScreen (DELETE endpoint, purges downloaded file + drops from local entry cache); delta-pull apply already purged downloaded files and drops deleted=true entries from cache (verified, refactored into shared purgeDownloaded). Rename propagation not started.
