pub mod hash;
pub mod index;
pub mod scan;
pub mod watch;

use rusqlite::Connection;

/// One synced folder ("library"). The host can expose several; each has its
/// own index and syncs independently, Syncthing-style.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, utoipa::ToSchema)]
pub struct Folder {
    pub id: String,
    pub label: String,
    #[schema(value_type = String)]
    pub root: std::path::PathBuf,
}

/// Per-file metadata as stored in the index and exchanged with the app.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, utoipa::ToSchema)]
pub struct FileEntry {
    /// Path relative to the folder root, `/`-separated, no leading slash.
    pub path: String,
    pub kind: EntryKind,
    pub size: u64,
    /// Seconds since unix epoch.
    pub mtime_s: i64,
    /// Sub-second nanoseconds, for stable ordering of writes.
    pub mtime_ns: i64,
    pub deleted: bool,
    /// BLAKE3 of the whole file, hex. Null for directories and deleted entries.
    pub hash: Option<String>,
    /// Monotonic sequence, bumped on every local change. `?since=` queries use this.
    pub sequence: i64,
}

#[derive(
    Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize, utoipa::ToSchema,
)]
#[serde(rename_all = "snake_case")]
pub enum EntryKind {
    File,
    Dir,
}

impl FileEntry {
    pub fn is_fresher(&self, other: &FileEntry) -> bool {
        (self.mtime_s, self.mtime_ns) > (other.mtime_s, other.mtime_ns)
    }
}

pub fn open_db(path: &std::path::Path) -> anyhow::Result<Connection> {
    let conn = Connection::open(path)?;
    conn.pragma_update(None, "journal_mode", "WAL")?;
    conn.pragma_update(None, "synchronous", "NORMAL")?;
    conn.pragma_update(None, "foreign_keys", "ON")?;
    index::migrate(&conn)?;
    Ok(conn)
}

/// Upload staging directory the host creates inside a synced root. It is
/// infrastructure, not user content: never indexed, never listed, never synced.
pub const TMP_DIR: &str = ".nester-tmp";

/// Stable folder id from the canonical path, so the index survives restarts.
pub fn folder_id(path: &std::path::Path) -> String {
    blake3::hash(path.to_string_lossy().as_bytes()).to_hex()[..12].to_string()
}

/// Win32 extended-length prefix stripped for DISPLAY only. The canonical
/// `\\?\C:\...` path stays authoritative for IO and folder_id hashing;
/// stripping it before hashing would invalidate existing folder ids.
pub fn display_root(path: &std::path::Path) -> String {
    let raw = path.to_string_lossy();
    if let Some(rest) = raw.strip_prefix(r"\\?\UNC\") {
        format!(r"\\{rest}")
    } else if let Some(rest) = raw.strip_prefix(r"\\?\") {
        rest.to_string()
    } else {
        raw.into_owned()
    }
}

/// Test-only helper: an isolated scratch dir under `target/test-tmp`. Lives in
/// the public API because downstream crates' integration tests use it too.
pub fn test_root(tag: &str) -> std::path::PathBuf {
    let base = std::path::PathBuf::from("target/test-tmp");
    std::fs::create_dir_all(&base).unwrap();
    let unique = format!(
        "{}-{}-{:?}",
        tag,
        std::process::id(),
        std::thread::current().id()
    );
    let dir = base.join(unique);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fresher_compares_sub_second_precision() {
        let a = FileEntry {
            path: "a".into(),
            kind: EntryKind::File,
            size: 1,
            mtime_s: 100,
            mtime_ns: 0,
            deleted: false,
            hash: None,
            sequence: 0,
        };
        let mut b = a.clone();
        b.mtime_ns = 1;
        assert!(b.is_fresher(&a));
        assert!(!a.is_fresher(&b));
    }

    #[test]
    fn display_root_strips_win32_prefixes() {
        assert_eq!(
            display_root(std::path::Path::new(r"\\?\C:\Users\me\Pictures")),
            r"C:\Users\me\Pictures"
        );
        assert_eq!(
            display_root(std::path::Path::new(r"\\?\UNC\host\share")),
            r"\\host\share"
        );
        assert_eq!(
            display_root(std::path::Path::new("/home/me/Pictures")),
            "/home/me/Pictures"
        );
    }
}
