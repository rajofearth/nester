# 08: Thumbnails

Status: todo
Blocked-by: none

Host-side thumbnail generation for image entries, exposed so the index delta carries previews and the phone gallery renders without full downloads.

What done looks like:

- images get small thumbnails at scan or on demand
- thumbnail delivered with the entry or via its own endpoint
- phone gallery view uses them instead of full bodies
- video thumbnails are an investigation, not this ticket

Acceptance criteria: a folder of 200 photos lists on the phone with thumbnails and no full-file downloads.

Notes: video thumbnails need ffmpeg, decided in a separate ticket. Keep thumbnail generation off the hot scan path if it stalls rescans.
