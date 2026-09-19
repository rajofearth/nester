# 0005 Sync internals

Date: 2026-09-19
Status: accepted

## Context

The host needs to notice changes fast without missing any, store an index the phone can cheaply diff against, and move file bodies both directions over LAN.

## Decision

Watching. notify 8 with notify-debouncer-full triggers scans; a periodic full rescan runs regardless. The watcher never updates the index directly. The scan is the source of truth, Syncthing's model, so dropped events self-heal on the next rescan.

Index. SQLite in WAL mode, one database per folder (rusqlite). The files table has path, kind, size, mtime_s, mtime_ns, deleted, hash, sequence. Sequence is a monotonic per-folder change counter; every applied change bumps it. The phone delta-pulls with GET /entries?since=N.

Hashing. BLAKE3, whole-file, streamed. Fast enough that no per-block scheme is needed at personal-library sizes. Fixed-size block hashes and CDC chunking are deferred; the hash column can hold them later.

Uploads. tus-style: POST /uploads creates an upload, HEAD returns the offset, PATCH writes the body at an offset. Resumable, no multipart plumbing.

Downloads. GET with Range support, so the phone streams and resumes.

Phone storage. The phone holds the full index (paths, sizes, hashes, thumbnails) but downloads bodies on demand, with LRU eviction when local content exceeds its budget. The index survives eviction; contents re-download when needed.

Thumbnails. Generated host-side for image entries, shipped with the index, so the gallery renders without full downloads. Video thumbnails need ffmpeg and are decided in a later ticket.

## Consequences

Scan-based truth is simpler and more robust than event-driven indexing, at the cost of periodic full walks. Per-folder databases keep locking contention at zero and make folder add/remove trivial. The sequence column is the whole delta protocol; nothing else on the phone needs to trust its own state. Deferred block hashing means a one-byte change re-uploads or re-downloads the whole file, acceptable on LAN.
