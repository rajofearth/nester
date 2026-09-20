# Context

This file is the single source of truth for nester's terminology. ADRs in docs/adr/ record decisions. If a term here changes meaning, the ADRs and docs must follow.

## Glossary

Host
The computer running the nester Rust app. It serves the sync API over LAN WiFi and owns the real copies of synced folders. Windows and macOS in v1.

Device
The Android phone running the nester app. v1 has exactly one host and one or more devices; device-count tiers come later with billing.

Folder (library)
A directory on the host that the user chose to sync, Syncthing-style. Each folder has its own SQLite index. The host suggests Pictures by default; phone camera-roll backup is an opt-in folder.

Pairing
The one-time act of connecting a device to a host by scanning a QR code. The QR carries the host IP, port, and bearer token. No accounts, no server-side registration.

Pairing token
The bearer token from the QR code. It is the only identity in v1. The server compares it constant-time in auth middleware.

Index
The SQLite database (WAL mode) describing a folder's contents. Stored on the host per folder, mirrored on the phone. Contains paths, sizes, hashes, thumbnails, and change sequences.

Sequence
A monotonic per-folder change counter in the index. Every applied change bumps it. The phone asks for everything after a sequence it has seen.

Entry
One row in the files table: path, kind, size, mtime_s, mtime_ns, deleted, hash, sequence. Files and directories are both entries.

Delta pull
The phone's incremental fetch of index changes, GET /entries?since=N. Returns entries with sequence greater than N. Cheap when nothing changed.

Mirror
The sync model. Device and host converge on the same set of entries; changes on either side propagate to the other. Conflicts resolve last-writer-wins in v1.

On-demand content
The phone holds the full index but downloads file bodies only when opened or pinned. Thumbnails come down with the index.

Backed up
For items the phone created (camera uploads): the body was uploaded to the host and the phone's bookkeeping recorded it. Shown as "Backed up" on camera screens; "Synced" in folder grids.

Synced / Not synced (device chip)
In a folder grid, "Synced" means the file body is on this phone (downloaded on demand); "Not synced" means the index knows the entry but the body is still on the host only. Downloads are always explicit (tap a tile to get a detail sheet with a Download action).

LRU eviction
When local content storage passes its budget, the phone deletes the least recently used file bodies. The index rows stay; contents can be re-downloaded.

Last-writer-wins (LWW)
Conflict policy: when both sides changed an entry, the copy with the newer mtime wins. A v1 tradeoff, recorded in ADR 0004. A keep-both rename policy is the v2 candidate.

Block
A fixed-size chunk of a file used for hashing. Whole-file BLAKE3 is used in v1; per-block hashes are deferred. See ADR 0005.

Thumbnail
A small preview generated host-side for image entries, shipped with the index so the phone gallery renders without full downloads. Video thumbnails need ffmpeg and are decided in a later ticket.

Tailscale (future)
A possible later path for remote access outside LAN WiFi. Deliberately deferred in v1; nester only speaks to devices on the same network.
