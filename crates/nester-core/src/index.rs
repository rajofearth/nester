use rusqlite::Connection;

/// Schema v1. One table per concern, no version vectors (see docs/adr/0004).
/// `sequence` is the folder-local change counter; the phone pulls `?since=N`.
const MIGRATIONS: &[&str] = &[
    // 1
    "CREATE TABLE IF NOT EXISTS meta (
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS files (
        path TEXT PRIMARY KEY,
        kind INTEGER NOT NULL,
        size INTEGER NOT NULL,
        mtime_s INTEGER NOT NULL,
        mtime_ns INTEGER NOT NULL,
        deleted INTEGER NOT NULL DEFAULT 0,
        hash TEXT,
        sequence INTEGER NOT NULL
    );
    CREATE INDEX IF NOT EXISTS files_sequence ON files(sequence);",
];

pub fn migrate(conn: &Connection) -> anyhow::Result<()> {
    let current: i64 = conn.query_row("PRAGMA user_version", [], |r| r.get(0))?;
    for (i, sql) in MIGRATIONS.iter().enumerate().skip(current as usize) {
        conn.execute_batch(sql)?;
        conn.pragma_update(None, "user_version", (i + 1) as i64)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn migration_is_idempotent() {
        let conn = Connection::open_in_memory().unwrap();
        migrate(&conn).unwrap();
        migrate(&conn).unwrap();
        let v: i64 = conn
            .query_row("PRAGMA user_version", [], |r| r.get(0))
            .unwrap();
        assert_eq!(v, MIGRATIONS.len() as i64);
    }
}
