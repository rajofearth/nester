# 04: Phone uploads and pushes

Status: done
Blocked-by: none

DECISION CORRECTION: v1 ships whole-file POST instead of the tus-style session protocol. POST /api/folders/{id}/files/{path} with X-Nester-Mtime-S/Ns headers, streamed to temp then committed; 409 on stale (LWW). Reason: tus offset sessions added machinery for resumability we don't need until multi-GB uploads over flaky links are real. Recorded in ADR 0005. Revisit tus when camera-video uploads get big enough that resume matters.

What done looks like:

- upload endpoint streams to disk while hashing (BLAKE3), updates index, bumps sequence [done]
- LWW guard: stale upload rejected with 409, fresh overwrites [done]
- phone picks camera-roll folder, pushes new photos via streaming upload [done]
- interrupted-upload resume: deferred with tus
- phone sees its own push confirmed via the next delta pull

Acceptance criteria: push a photo from the phone, it appears on the host disk and in the host index with correct hash.

Notes: upload protocol sketch in docs/architecture.md.

Progress (2026-09-19): phone half landed for the shipped server contract (POST /api/folders/{folderId}/files/{path...} streaming body, X-Nester-Mtime-S/Ns headers, 409 conflict surfaced as skipped-conflict summary): NesterApi.upload/uploadStream/deleteFile, opt-in camera-roll backup screen with per-folder uploadedMedia tracking in FolderState. NOT done: tus-style POST/HEAD/PATCH resume protocol (server shipped plain POST instead), resumable/interrupted uploads.
