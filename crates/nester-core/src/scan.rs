use std::path::Path;

use rusqlite::Connection;
use walkdir::WalkDir;

use crate::hash::hash_file;
use crate::{EntryKind, FileEntry};

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct ScanStats {
    pub added: u64,
    pub updated: u64,
    pub removed: u64,
    pub unchanged: u64,
}

/// Full scan of `root`: the source of truth. The file watcher only triggers
/// scans (Syncthing's model); it never decides what changed.
///
/// Change detection is mtime+size, the same heuristic Syncthing uses. Only
/// files whose stat changed get re-hashed.
pub fn scan_folder(conn: &mut Connection, root: &Path) -> anyhow::Result<ScanStats> {
    let root = root.canonicalize()?;
    let tx = conn.transaction()?;

    let mut stats = ScanStats::default();
    let mut seen: Vec<String> = Vec::new();

    for entry in WalkDir::new(&root).follow_links(false) {
        let entry = entry.map_err(|e| {
            let at = e
                .path()
                .map(|p| p.display().to_string())
                .unwrap_or_default();
            anyhow::anyhow!("walk error at {at}: {e}")
        })?;
        let path = entry.path();
        let rel = path
            .strip_prefix(&root)?
            .to_string_lossy()
            .replace('\\', "/");
        if rel.is_empty() {
            continue;
        }
        seen.push(rel.clone());

        let meta = entry.metadata()?;
        if meta.is_dir() {
            upsert_entry(
                &tx,
                &FileEntry {
                    path: rel.clone(),
                    kind: EntryKind::Dir,
                    size: 0,
                    mtime_s: 0,
                    mtime_ns: 0,
                    deleted: false,
                    hash: None,
                    sequence: 0,
                },
                &mut stats,
            )?;
            continue;
        }
        if meta.is_symlink() {
            tracing::debug!("skipping symlink {rel}");
            continue;
        }

        let (mtime_s, mtime_ns) = unix_mtime(meta.modified()?);

        if let Some(known) = get_entry_tx(&tx, &rel)?
            && !known.deleted
            && known.kind == EntryKind::File
            && known.size == meta.len()
            && known.hash.is_some()
            && known.mtime_s.abs_diff(mtime_s) <= 2
        {
            // FAT-style 2s tolerance: sub-2s mtime wobble (and any ns jitter
            // inside the window) counts as unchanged so FAT-touched files do
            // not rehash forever. Sizes and hashes still gate everything.
            stats.unchanged += 1;
            continue;
        }

        let hash = hash_file(path)?;
        upsert_entry(
            &tx,
            &FileEntry {
                path: rel,
                kind: EntryKind::File,
                size: meta.len(),
                mtime_s,
                mtime_ns,
                deleted: false,
                hash: Some(hash),
                sequence: 0,
            },
            &mut stats,
        )?;
    }

    mark_missing_as_deleted(&tx, &seen, &mut stats)?;
    tx.commit()?;
    Ok(stats)
}

fn mark_missing_as_deleted(
    tx: &rusqlite::Transaction<'_>,
    seen: &[String],
    stats: &mut ScanStats,
) -> anyhow::Result<()> {
    let stored: Vec<String> = {
        let mut stmt = tx.prepare("SELECT path FROM files WHERE deleted = 0")?;
        let rows = stmt.query_map([], |r| r.get(0))?;
        rows.collect::<Result<Vec<_>, _>>()?
    };
    for p in stored {
        if !seen.contains(&p) {
            tx.execute(
                "UPDATE files SET deleted = 1, sequence = (SELECT COALESCE(MAX(sequence), 0) + 1 FROM files) WHERE path = ?1",
                [&p],
            )?;
            stats.removed += 1;
        }
    }
    Ok(())
}

fn upsert_entry(
    tx: &rusqlite::Transaction<'_>,
    entry: &FileEntry,
    stats: &mut ScanStats,
) -> anyhow::Result<()> {
    let changed = tx.execute(
        "INSERT INTO files (path, kind, size, mtime_s, mtime_ns, deleted, hash, sequence)
         VALUES (?1, ?2, ?3, ?4, ?5, 0, ?6, (SELECT COALESCE(MAX(sequence), 0) + 1 FROM files))
         ON CONFLICT(path) DO UPDATE SET
            kind = excluded.kind, size = excluded.size, mtime_s = excluded.mtime_s,
            mtime_ns = excluded.mtime_ns, deleted = 0, hash = excluded.hash,
            sequence = (SELECT COALESCE(MAX(sequence), 0) + 1 FROM files)
         WHERE files.kind != excluded.kind OR files.size != excluded.size
            OR files.mtime_s != excluded.mtime_s OR files.mtime_ns != excluded.mtime_ns
            OR files.deleted != 0 OR (files.hash IS NOT excluded.hash)",
        rusqlite::params![
            entry.path,
            entry.kind as i64,
            entry.size,
            entry.mtime_s,
            entry.mtime_ns,
            entry.hash
        ],
    )?;
    if changed > 0 {
        if entry.hash.is_some() {
            stats.updated += 1;
        } else {
            stats.added += 1;
        }
    } else {
        stats.unchanged += 1;
    }
    Ok(())
}

fn get_entry_tx(tx: &rusqlite::Transaction<'_>, path: &str) -> anyhow::Result<Option<FileEntry>> {
    let mut stmt = tx.prepare(
        "SELECT path, kind, size, mtime_s, mtime_ns, deleted, hash, sequence FROM files WHERE path = ?1",
    )?;
    let mut rows = stmt.query_map([path], row_to_entry)?;
    match rows.next() {
        Some(row) => Ok(Some(row?)),
        None => Ok(None),
    }
}

pub(crate) fn row_to_entry(r: &rusqlite::Row<'_>) -> rusqlite::Result<FileEntry> {
    let kind_i: i64 = r.get(1)?;
    Ok(FileEntry {
        path: r.get(0)?,
        kind: if kind_i == 0 {
            EntryKind::File
        } else {
            EntryKind::Dir
        },
        size: r.get(2)?,
        mtime_s: r.get(3)?,
        mtime_ns: r.get(4)?,
        deleted: r.get(5)?,
        hash: r.get(6)?,
        sequence: r.get(7)?,
    })
}

/// Entries with `sequence > since`, oldest change first. The phone's delta pull.
pub fn entries_since(conn: &Connection, since: i64) -> anyhow::Result<Vec<FileEntry>> {
    let mut stmt = conn.prepare(
        "SELECT path, kind, size, mtime_s, mtime_ns, deleted, hash, sequence
         FROM files WHERE sequence > ?1 ORDER BY sequence ASC",
    )?;
    let rows = stmt.query_map([since], row_to_entry)?;
    Ok(rows.collect::<Result<Vec<_>, _>>()?)
}

/// Highest sequence number handed out so far, 0 for an empty index.
pub fn max_sequence(conn: &Connection) -> anyhow::Result<i64> {
    Ok(
        conn.query_row("SELECT COALESCE(MAX(sequence), 0) FROM files", [], |r| {
            r.get(0)
        })?,
    )
}

/// Index lookup by relative path.
pub fn get_entry(conn: &Connection, path: &str) -> anyhow::Result<Option<FileEntry>> {
    let mut stmt = conn.prepare(
        "SELECT path, kind, size, mtime_s, mtime_ns, deleted, hash, sequence FROM files WHERE path = ?1",
    )?;
    let mut rows = stmt.query_map([path], row_to_entry)?;
    match rows.next() {
        Some(row) => Ok(Some(row?)),
        None => Ok(None),
    }
}

/// Apply one change that originated on the phone: upsert or mark deleted, with
/// a fresh sequence number so the next delta pull picks it up.
pub fn apply_file_change(conn: &mut Connection, entry: &FileEntry) -> anyhow::Result<()> {
    let tx = conn.transaction()?;
    upsert_entry(&tx, entry, &mut ScanStats::default())?;
    tx.commit()?;
    Ok(())
}

/// Mark an entry deleted in the index (the file itself is handled by the caller).
pub fn mark_deleted(conn: &mut Connection, path: &str) -> anyhow::Result<bool> {
    let tx = conn.transaction()?;
    let changed = tx.execute(
        "UPDATE files SET deleted = 1, hash = NULL, sequence = (SELECT COALESCE(MAX(sequence), 0) + 1 FROM files) WHERE path = ?1 AND deleted = 0",
        [path],
    )?;
    tx.commit()?;
    Ok(changed > 0)
}

fn unix_mtime(mtime: std::time::SystemTime) -> (i64, i64) {
    match mtime.duration_since(std::time::UNIX_EPOCH) {
        Ok(d) => (d.as_secs() as i64, d.subsec_nanos() as i64),
        Err(e) => {
            let d = e.duration();
            let mut s = -(d.as_secs() as i64);
            let mut ns = -(d.subsec_nanos() as i64);
            if ns < 0 {
                s -= 1;
                ns += 1_000_000_000;
            }
            (s, ns)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::open_db;

    #[test]
    fn scan_indexes_files_dirs_and_hashes() {
        let root = crate::test_root("scan-basic");
        let db = root.parent().unwrap().join(format!(
            "{}.db",
            root.file_name().unwrap().to_string_lossy()
        ));
        std::fs::write(root.join("a.txt"), b"hello").unwrap();
        std::fs::create_dir(root.join("sub")).unwrap();
        std::fs::write(root.join("sub/b.txt"), b"world").unwrap();

        let mut conn = open_db(&db).unwrap();
        let stats = scan_folder(&mut conn, &root).unwrap();
        assert_eq!(stats.updated, 2);
        assert_eq!(stats.added, 1);

        let entries = entries_since(&conn, 0).unwrap();
        let a = entries.iter().find(|e| e.path == "a.txt").unwrap();
        assert_eq!(
            a.hash.as_deref(),
            Some(blake3::hash(b"hello").to_hex().as_str())
        );
        assert!(
            entries
                .iter()
                .any(|e| e.path == "sub" && e.kind == EntryKind::Dir)
        );
        assert_eq!(stats.removed, 0);
    }

    #[test]
    fn rescan_skips_unchanged() {
        let root = crate::test_root("scan-rescan");
        let db = root.parent().unwrap().join(format!(
            "{}.db",
            root.file_name().unwrap().to_string_lossy()
        ));
        std::fs::write(root.join("a.txt"), b"hello").unwrap();
        let mut conn = open_db(&db).unwrap();
        scan_folder(&mut conn, &root).unwrap();
        let stats = scan_folder(&mut conn, &root).unwrap();
        assert_eq!(stats.updated, 0);
        assert_eq!(stats.unchanged, 1);
        assert_eq!(stats.added, 0);
    }

    #[test]
    fn deletion_marks_entry() {
        let root = crate::test_root("scan-delete");
        let db = root.parent().unwrap().join(format!(
            "{}.db",
            root.file_name().unwrap().to_string_lossy()
        ));
        let p = root.join("gone.txt");
        std::fs::write(&p, b"bye").unwrap();
        let mut conn = open_db(&db).unwrap();
        scan_folder(&mut conn, &root).unwrap();
        std::fs::remove_file(&p).unwrap();
        let stats = scan_folder(&mut conn, &root).unwrap();
        assert_eq!(stats.removed, 1);
        let entries = entries_since(&conn, 0).unwrap();
        let gone = entries.iter().find(|e| e.path == "gone.txt").unwrap();
        assert!(gone.deleted);
    }

    #[test]
    fn mtime_change_rehashes() {
        let root = crate::test_root("scan-mtime");
        let db = root.parent().unwrap().join(format!(
            "{}.db",
            root.file_name().unwrap().to_string_lossy()
        ));
        let p = root.join("a.txt");
        std::fs::write(&p, b"v1").unwrap();
        let mut conn = open_db(&db).unwrap();
        scan_folder(&mut conn, &root).unwrap();

        std::fs::write(&p, b"v2-longer").unwrap();
        // Force a fresh mtime; on fast filesystems the second write can land in
        // the same tick, which the size check catches anyway.
        let stats = scan_folder(&mut conn, &root).unwrap();
        assert_eq!(stats.updated + stats.added, 1);
        let entries = entries_since(&conn, 0).unwrap();
        let a = entries.iter().find(|e| e.path == "a.txt").unwrap();
        assert_eq!(
            a.hash.as_deref(),
            Some(blake3::hash(b"v2-longer").to_hex().as_str())
        );
    }

    #[test]
    fn mtime_within_two_seconds_unchanged() {
        use std::time::Duration;

        let root = crate::test_root("scan-fat-window");
        let db = root.parent().unwrap().join(format!(
            "{}.db",
            root.file_name().unwrap().to_string_lossy()
        ));
        let p = root.join("a.txt");
        std::fs::write(&p, b"same").unwrap();
        let mut conn = open_db(&db).unwrap();
        scan_folder(&mut conn, &root).unwrap();
        let base = get_entry(&conn, "a.txt").unwrap().unwrap().mtime_s;

        let set_mtime = |s: i64| {
            let f = std::fs::File::options().write(true).open(&p).unwrap();
            f.set_times(
                std::fs::FileTimes::new()
                    .set_modified(std::time::UNIX_EPOCH + Duration::from_secs(s.max(0) as u64)),
            )
            .unwrap();
        };

        // +1s, same content: inside the window, no rehash.
        set_mtime(base + 1);
        let stats = scan_folder(&mut conn, &root).unwrap();
        assert_eq!(stats.unchanged, 1);
        assert_eq!(stats.updated, 0);

        // +3s, same content: outside the window, rehash fires (same hash, new
        // mtime lands in the index).
        set_mtime(base + 3);
        let stats = scan_folder(&mut conn, &root).unwrap();
        assert_eq!(stats.updated, 1);
        let a = get_entry(&conn, "a.txt").unwrap().unwrap();
        assert_eq!(a.mtime_s, base + 3);
    }
}
