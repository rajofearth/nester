# 12: Mobile gallery views (photos/videos/files)

Status: todo
Blocked-by: none

Replace the entries flat path list with the product's promised native-gallery model. The live test showed raw rows sorted by full path; that's a file manager, not a gallery.

What done looks like:

- Three views: Photos (grid of image entities), Videos (grid), Files (list of non-media)
- Media grid: Paging 3 over the local index (page ~120), stable keys, fixed aspect-ratio cells, Coil from local file if downloaded else placeholder tile, no SubcomposeAsyncImage
- A path-jump filter or folder drill-down remains accessible but secondary; default is the flat media grid
- Video rows: download-then-play (existing flow), tap opens player
- Empty states for each view distinct

Acceptance criteria: user lands on the folder and sees photos as a grid within one screen; scrolling 4,435 entries stays smooth at 60fps on a physical device.
