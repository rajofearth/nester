# 11: Background auto-backup worker + share target

Status: todo
Blocked-by: none

From the Android platform research (2026-09-20): WorkManager is the only sane backbone. (1) Consolidated upload worker with EXPONENTIAL backoff, existing-work-policy KEEP, triggered by app-open content changes; (2) 6-12h periodic watermark scan worker as catch-up (unmetered + battery-not-low constraints); (3) share-target manifest filters for SEND/SEND_MULTIPLE incl. */* mixed sends, copy streams to app storage immediately (URI grants are temporary), queue into the same store the backup drains; (4) battery-optimization deep-link explainer (detect isIgnoringBatteryOptimizations, link to settings, never request directly - sideloaded v1); (5) "backup stalled" banner instead of fighting OEM killers (ColorOS et al, see dontkillmyapp). Android 15 dataSync FGS has a 6h budget and cannot launch from BOOT_COMPLETED - WorkManager is Google's own replacement path, which is why no foreground service.

What done looks like:

- new camera photos upload without the app open (within WorkManager scheduling reality: minutes, not seconds)
- share sheet: "Nester" appears in Android share targets; shared files upload to the selected folder
- upload queue is idempotent (ExistingWorkPolicy.KEEP) and retries with backoff when the host is asleep
- periodic catch-up scan guarantees nothing is missed while the app was closed

Acceptance criteria: photo taken with app closed appears on the host within ~15 min of the phone next being online; a file shared from WhatsApp/Photos lands on the host.
