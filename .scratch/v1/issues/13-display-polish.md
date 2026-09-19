# 13: Display polish from live testing

Status: todo
Blocked-by: none
From the 2026-09-20 device test session:

- Folders screen shows `\\?\C:\Users\Yashraj\Pictures`: strip the Win32 extended prefix for display only (API root field + GUI). Keep the canonical path with prefix for IO and folder_id hashing - changing folder ids would invalidate the phone's camera-roll bookkeeping.
- `.nester-tmp` appears as a folder in entries: exclude it in the entries query and scan walks (it is host upload-staging infrastructure inside the synced root).
- Entries rows are ~160px tall because Download/Delete/Open are always-visible buttons: collapse to download tap + overflow actions once gallery views (ticket 12) land.
- Backup end-state readout after an all-failed run shows "1/1976, full bar" instead of "0 of 1976 · failed": count processed = uploaded+skipped+failed in the headline.
