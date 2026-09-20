# 13: Display polish from live testing

Status: done
Blocked-by: none
From the 2026-09-20 device test session:

- Folders screen shows `\\?\C:\Users\Yashraj\Pictures`: strip the Win32 extended prefix for display only (API root field + GUI) [done]. Keep the canonical path with prefix for IO and folder_id hashing [kept - config.toml still stores `\\?\...`, folder id unchanged (726e2c08cf77) so phone camera-roll bookkeeping survives]. `nester_core::display_root` applied in the `/api/folders` response and the host GUI folder rows. Verified live: phone shows `C:\Users\Yashraj\Pictures`.
- `.nester-tmp` appears as a folder in entries: exclude it in the entries query and scan walks [done]. Scan skips the dir (never indexed again); `entries_since` hides non-deleted staging rows but still propagates their deletions so devices that hold them purge on next pull (381 staging rows marked deleted on host restart, 0 non-deleted served). Uploads targeting `.nester-tmp` are rejected by the path guard. Rust tests cover both.
- Entries rows are ~160px tall because Download/Delete/Open are always-visible buttons: collapse to download tap + overflow actions once gallery views (ticket 12) land [done]. Files rows are now single-line (~48dp): tap = download/open, trailing delete icon, long-press = open/delete on grid tiles.
- Backup end-state readout after an all-failed run shows "1/1976, full bar" instead of "0 of 1976 · failed": count processed = uploaded+skipped+failed in the headline [done]. DONE card now reports "Finished - D of N backed up · S skipped · F failed" (error styling) instead of a green "Backup complete"; running/paused headlines use processed+1; the bar only accumulates bytes actually uploaded (failures no longer inflate sentBytes; no forced full bar at run end). Covered by BackupEndStateTest.
