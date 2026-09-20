# 14: M3 Expressive redesign + sync-state visibility

Status: done
Blocked-by: none
From the owner's feedback after session 2 (2026-09-20): the home list showed an unclear
"updated Xh ago", "Back up camera roll" was a clunky name, the gallery gave no way to see
what was backed up, tapping silently downloaded files, and there was no control over what
gets uploaded. The whole phone UI is now Material 3 Expressive.

What was delivered:

- Design system: dynamic color (Android 12+) with the Mono fallback scheme for the
  Android 11 device, both light and dark from the system setting; M3E shapes (pill
  buttons, 20dp cards/tiles, 28dp dialogs); expressive stacked lists (3dp gaps, 28dp
  outer / 8dp inner corners); StatusChip vocabulary (Synced / Not synced / Syncing /
  Failed / Not backed up / Backed up); press-scale on tiles; slide transitions between
  screens (forward from the right, reverse for back, fade for onboarding to home).
  Deviation: material3 1.4.0 in the 2025.10.00 BOM keeps MotionScheme internal, so the
  expressive MotionScheme API itself is not reachable yet; components still animate with
  M3 defaults. Revisit when the BOM exposes it.
- Navigation: bottom bar with Home / Devices / Settings (80dp on surfaceContainer,
  secondaryContainer pill indicator). Home lists real folders with honest supporting
  text: "Synced - 4.4k - 7.5 GB", "Syncing...", or "Offline - showing cached files";
  the camera-target folder shows "Not synced - X pending - Y synced - size" from the
  phone's own bookkeeping. "updated" is gone from Home; folder screens show
  "Last change Xh ago on the host" with the Refresh action beside it.
- Folder screen: Photos / Videos / Files tabs, 2-column grid of ~182dp 1:1 tiles with
  per-item status chips overlay. Tapping a downloaded item opens it; tapping a
  not-downloaded item opens a bottom sheet with an EXPLICIT Download button (no silent
  downloads), plus Open / Delete / Back up actions. Paging 3 (page 120) retained.
- Camera backup screen (renamed from "Back up camera roll"): per-item checkboxes with
  Select all / Deselect all (what to upload), "Back up N items", Pause/Resume,
  Retry failed, end-state readout counting uploaded+skipped+failed.
- Devices screen: the paired host with Connected/Checking/Disconnected state and a
  details dialog (files/bytes totals). Settings: pairing info, re-pair, unpair,
  "Include videos" toggle (DataStore), app version. Onboarding: QR scan preview
  (380x507, 20dp corners), "Connected !" pill, manual-entry fallback, and a
  "keep current pairing" exit when a pairing already exists.
- Shared run state (BackupRunState) makes in-flight uploads visible as "Syncing"/
  "Failed" chips on the folder grid while the camera screen runs.
- New deps: material-icons-extended (BOM), datastore-preferences 1.1.7. Release build
  now minifies (R8 + shrinkResources) and is signed with a local keystore
  (keystore/ + keystore.properties are gitignored).

Acceptance: owner's complaints addressed (clear home status, no silent downloads,
per-item backed-up visibility, upload selection); all 48 JVM tests green; release APK
signs clean (V2, CN=Nester) at .build-outputs/nester-v0.1.0-release.apk (24MB).
