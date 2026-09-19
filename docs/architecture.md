# Architecture

## Components

```
+--------------------------------------------------+
| host (one Rust process)                          |
|                                                  |
|  apps/host: GPUI window + tray icon              |
|      |                                           |
|  nester-server: axum on tokio                    |
|      |  bearer-token middleware                  |
|      |  /health /folders /entries /uploads       |
|      |                                           |
|  nester-core: sync engine                        |
|      scan + BLAKE3 hash                          |
|      index: SQLite WAL per folder                |
|        files(path, kind, size, mtime_s,          |
|              mtime_ns, deleted, hash, sequence)  |
|      watcher: notify 8 + debouncer-full          |
|      periodic full rescan (source of truth)      |
|      |                                           |
|  user's folder on disk                           |
+--------------------------------------------------+
        | LAN WiFi
        v
+------------------+
| Android app      |
| Kotlin, Compose  |
| full index copy  |
| on-demand bodies |
| LRU eviction     |
+------------------+
```

The watcher only triggers scans. The scan is the source of truth, which is Syncthing's model. A missed file event is corrected by the periodic rescan.

## Protocol sketch

Pairing. Host generates a token, prints a pairing string, the host window shows it as a QR. Phone scans it and stores host IP, port, token in settings. Every request after that carries `Authorization: Bearer <token>`, compared constant-time.

Delta pull. Phone sends `GET /entries?folder=<id>&since=N`. Server returns entries with sequence greater than N and the folder's current sequence. Phone applies them to its local index copy. `since=0` gives a full listing.

Upload. Phone pushes a changed or new file:

```
POST /uploads {folder, path, size}   -> upload id
HEAD /uploads/<id>                   -> current offset
PATCH /uploads/<id>  body at offset  -> server writes, acknowledges new offset
```

The server streams the body to disk, then hashes it with BLAKE3 and updates the index.

Download. `GET /entries/<id>/content` with Range support so the phone can resume and stream. Thumbnails come as a separate small endpoint or inline field.

## Data flows

First scan. Host walks the folder, hashes each file with BLAKE3 streamed, writes the files table, sets sequence per change. Phone pairs, delta-pulls with since=0, gets the whole index plus thumbnails. No file bodies downloaded yet.

File changed on host. Watcher fires, debouncer waits, scan runs, scan finds mtime and size differ, rehashes, bumps sequence. Phone's next delta pull sees the entry, downloads the body on demand or when pinned.

File changed on phone. Camera roll adds a photo. Phone POSTs the upload, PATCHes the body at offsets, then delta-pulls to confirm its index matches the host. Server applies to disk, scans, bumps sequence.

Conflict under LWW. Both sides changed the same path while apart. On reconnect, phone uploads its version and the host compares mtime between its index entry and the incoming change. Newer mtime wins, loser's copy is overwritten. The index records the outcome via hash and sequence. ADR 0004 records why this is acceptable in v1.
