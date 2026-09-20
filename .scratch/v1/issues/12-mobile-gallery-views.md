# 12: Mobile gallery views (photos/videos/files)

Status: done
Blocked-by: none

Replace the entries flat path list with the product's promised native-gallery model. The live test showed raw rows sorted by full path; that's a file manager, not a gallery.

What done looks like:

- Three views: Photos (grid of image entities), Videos (grid), Files (list of non-media) [done]
- Media grid: Paging 3 over the local index (page ~120), stable keys, fixed aspect-ratio cells, Coil from local file if downloaded else placeholder tile, no SubcomposeAsyncImage [done]
- A path-jump filter or folder drill-down remains accessible but secondary; default is the flat media grid [done - list icon opens "Show one folder" dialog, active filter chip with Clear]
- Video rows: download-then-play (existing flow), tap opens player [done - tap downloads then hands to system viewer with video mime]
- Empty states for each view distinct [done - photos/videos/files each have their own]

Acceptance criteria: user lands on the folder and sees photos as a grid within one screen [done - verified on device]; scrolling 4,435 entries stays smooth at 60fps on a physical device [done - six fast swipes deep into the grid on the OPPO A16e, no jank/crash, paging pages of 120].

Implementation (2026-09-20 session 2): `gallery/GalleryModels.kt` (classification, dir filter, view split - JVM tested), `gallery/ListPagingSource.kt` (Paging 3 over the in-memory index, page 120 - JVM tested), `ui/GalleryScreen.kt` (tabs, 4-col photo / 3-col video grids with 1:1 cells, stable path keys, contentType, compact file rows, shared download state map, tap = download-then-open, long-press = open/delete). Coil singleton with video decoder + 30% memory cache. Device evidence: screenshots in .scratch/session-notes/2026-09-20-session-2.md; crash buffer clean throughout.
