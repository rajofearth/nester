# 04: Phone uploads and pushes

Status: todo
Blocked-by: 03

Server gains the tus-style upload endpoints: POST /uploads, HEAD /uploads/<id> for offset, PATCH /uploads/<id> with body at offset. Phone pushes files added or changed on device.

What done looks like:

- upload endpoint streams to disk, hashes with BLAKE3, updates index, bumps sequence
- interrupted PATCH resumes from the offset reported by HEAD
- phone picks camera-roll folder, pushes new photos
- phone sees its own push confirmed via the next delta pull

Acceptance criteria: push a photo from the phone, it appears on the host disk and in the host index with correct hash.

Notes: upload protocol sketch in docs/architecture.md.
