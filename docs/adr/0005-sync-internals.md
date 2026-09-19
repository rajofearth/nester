# 0005 Sync internals

Date: 2026-09-19
Status: accepted

## Context

The host needs to notice changes fast without missing any, store an index the phone can cheaply diff against, and move file bodies both directions over LAN.

## Decision

Watching. notify 8 with notify-debouncer-full triggers scans; a periodic full rescan runs regardless. The watcher never updates the index directly. The scan is the source of truth, Syncthing's model, so dropped events self-heal on the next rescan.

Index. SQLite in WAL mode, one database per folder (rusqlite). The files table has path, kind, size, mtime_s, mtime_ns, deleted, hash, sequence. Sequence is a monotonic per-folder change counter; every applied change bumps it. The phone delta-pulls with GET /entries?since=N.

Hashing. BLAKE3, whole-file, streamed. Fast enough that no per-block scheme is needed at personal-library sizes. Fixed-size block hashes and CDC chunking are deferred; the hash column can hold them later.

Uploads. CORRECTED 2026-09-19 while implementing ticket 04: v1 ships whole-file POST to /api/folders/{id}/files/{path} with the phone's mtime in headers, streamed to a temp file while hashing, then committed; stale uploads get 409 under LWW. The tus-style session protocol (POST /uploads, HEAD offset, PATCH at offset) was deferred because resumability only matters for multi-GB uploads over flaky links, which v1 LAN photo sync does not have. Revisit if camera-video backups grow past what one POST survives.

Downloads. GET with Range support, so the phone streams and resumes. EXTENDED 2026-09-20 (mtime fidelity, rsync -t semantics): download responses carry X-Nester-Mtime-S/Ns and the phone sets the file's mtime after the atomic rename. Without it, every download would look like a change on the next scan and LWW comparisons would be made against wrong timestamps. Same-window tolerance: mtime+size equal (within 2s for FAT volumes) counts as unchanged; any size or real mtime change rehashes.

Phone storage. The phone holds the full index (paths, sizes, hashes, thumbnails) but downloads bodies on demand, with LRU eviction when local content exceeds its budget. The index survives eviction; contents re-download when needed. Downloads write to .part, fsync, atomic rename, and check free space first; orphaned .part files older than a day are swept at app start.

Path guard. Uploads and downloads validate the decoded relative path server-side: Windows-reserved characters, control characters, trailing dot/space, empty segments, length over 240 are rejected with 400. Case-only collisions between host entries are logged (Windows keeps its casing; no silent merging yet). A stale phone cursor (since beyond MAX(sequence), e.g. after an index rebuild) gets 409 index-reset and the phone re-pulls from 0 while keeping its camera-roll bookkeeping.

Thumbnails. Generated host-side for image entries, shipped with the index, so the gallery renders without full downloads. Video thumbnails need ffmpeg and are decided in a later ticket.

## Consequences

Scan-based truth is simpler and more robust than event-driven indexing, at the cost of periodic full walks. Per-folder databases keep locking contention at zero and make folder add/remove trivial. The sequence column is the whole delta protocol; nothing else on the phone needs to trust its own state. Deferred block hashing means a one-byte change re-uploads or re-downloads the whole file, acceptable on LAN.
