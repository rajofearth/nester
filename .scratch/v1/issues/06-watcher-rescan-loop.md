# 06: Watcher and rescan loop

Status: todo
Blocked-by: none

Host-side change detection: notify 8 + notify-debouncer-full triggering rescans, plus a periodic full rescan timer. The watcher never writes the index; the scan is the source of truth.

What done looks like:

- edit a file on disk, index row updates within the debounce window
- dropped-event case: change made while watcher misses it is caught by the periodic rescan
- rescan cost bounded: unchanged files skip rehash

Acceptance criteria: mtime/size comparison makes an untouched 1 GB file rescan cheap; a touched file rehashes and bumps sequence.

Notes: ADR 0005. Test the debounce and periodic timer with injected events.
