use std::path::PathBuf;

use axum::body::Body;
use axum::extract::{Path, Query, State};
use axum::http::{HeaderMap, StatusCode, header};
use axum::response::{IntoResponse, Response};
use axum::routing::{delete, get, post};
use axum::{Json, Router};
use futures_util::StreamExt;
use nester_core::{EntryKind, FileEntry, Folder};
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt};
use tokio_util::io::ReaderStream;

use crate::auth::require_bearer;
use crate::state::AppState;

#[utoipa::path(get, path = "/api/health", responses((status = 200, body = serde_json::Value)))]
pub async fn health() -> impl IntoResponse {
    Json(serde_json::json!({ "ok": true }))
}

#[derive(serde::Serialize, utoipa::ToSchema, Clone)]
pub struct FolderStats {
    pub files: u64,
    pub bytes: u64,
    pub last_change_at: Option<i64>,
    pub last_change_path: Option<String>,
    pub state: String,
}

#[derive(serde::Serialize, utoipa::ToSchema)]
pub struct FolderWithStats {
    #[serde(flatten)]
    pub folder: Folder,
    pub stats: Option<FolderStats>,
}

#[derive(serde::Serialize, utoipa::ToSchema)]
pub struct FoldersResponse {
    pub folders: Vec<FolderWithStats>,
}

/// Per-folder rollup straight from the index: totals for the GUI and the phone
/// header, plus a syncing flag while a transfer landed recently.
fn folder_stats(state: &AppState, folder_id: &str) -> Option<FolderStats> {
    let db = state.db(folder_id)?;
    let (files, bytes, last_path, last_at) = {
        let conn = db.lock().unwrap();
        let files: u64 = conn
            .query_row(
                "SELECT COUNT(*) FROM files WHERE deleted = 0 AND kind = 0",
                [],
                |r| r.get(0),
            )
            .unwrap_or(0);
        let bytes: u64 = conn
            .query_row(
                "SELECT COALESCE(SUM(size), 0) FROM files WHERE deleted = 0 AND kind = 0",
                [],
                |r| r.get(0),
            )
            .unwrap_or(0);
        let last = conn
            .query_row(
                "SELECT path, mtime_s FROM files WHERE deleted = 0 AND kind = 0
                 ORDER BY sequence DESC LIMIT 1",
                [],
                |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?)),
            )
            .ok();
        (
            files,
            bytes,
            last.as_ref().map(|t| t.0.clone()),
            last.as_ref().map(|t| t.1),
        )
    };
    let state_value = {
        let live = state.live.lock().unwrap();
        match live.get(folder_id) {
            Some(until) if *until > std::time::Instant::now() => "syncing".to_string(),
            _ => "idle".to_string(),
        }
    };
    Some(FolderStats {
        files,
        bytes,
        last_change_at: last_at,
        last_change_path: last_path,
        state: state_value,
    })
}

#[utoipa::path(
    get,
    path = "/api/folders",
    responses((status = 200, body = FoldersResponse)),
    security(("pairing_token" = []))
)]
pub async fn list_folders(State(state): State<AppState>) -> Json<FoldersResponse> {
    let folders = state
        .folders
        .iter()
        .map(|f| FolderWithStats {
            folder: f.clone(),
            stats: folder_stats(&state, &f.id),
        })
        .collect();
    Json(FoldersResponse { folders })
}

/// The phone pings this while foregrounded so the host shows it as online.
#[utoipa::path(
    post,
    path = "/api/heartbeat",
    responses((status = 204)),
    security(("pairing_token" = []))
)]
pub async fn heartbeat() -> impl IntoResponse {
    StatusCode::NO_CONTENT
}

#[derive(serde::Deserialize, utoipa::IntoParams)]
pub struct EntriesQuery {
    pub since: Option<i64>,
}

#[utoipa::path(
    get,
    path = "/api/folders/{folder_id}/entries",
    params(("folder_id" = String, Path), EntriesQuery),
    responses(
        (status = 200, body = Vec<FileEntry>),
        (status = 409, description = "index-reset: the cursor is past the current max sequence, re-pull from 0"),
    ),
    security(("pairing_token" = []))
)]
pub async fn list_entries(
    State(state): State<AppState>,
    Path(folder_id): Path<String>,
    Query(q): Query<EntriesQuery>,
) -> Response {
    let Some(db) = state.db(&folder_id) else {
        return (StatusCode::NOT_FOUND, "unknown folder").into_response();
    };
    let since = q.since.unwrap_or(0);
    let result = tokio::task::spawn_blocking(move || {
        let conn = db.lock().unwrap();
        let max = nester_core::scan::max_sequence(&conn)?;
        if since > max {
            return Ok(None);
        }
        nester_core::scan::entries_since(&conn, since).map(Some)
    })
    .await;
    match result {
        Ok(Ok(Some(entries))) => Json(entries).into_response(),
        Ok(Ok(None)) => (StatusCode::CONFLICT, "index-reset").into_response(),
        Ok(Err(e)) => {
            tracing::error!("entries query failed: {e}");
            (StatusCode::INTERNAL_SERVER_ERROR, "index error").into_response()
        }
        Err(e) => {
            tracing::error!("entries task failed: {e}");
            (StatusCode::INTERNAL_SERVER_ERROR, "internal error").into_response()
        }
    }
}

#[utoipa::path(
    get,
    path = "/api/folders/{folder_id}/files/{*path}",
    params(("folder_id" = String, Path), ("path" = String, Path)),
    responses((status = 200, body = Vec<u8>)),
    security(("pairing_token" = []))
)]
pub async fn download(
    State(state): State<AppState>,
    Path((folder_id, rel_path)): Path<(String, String)>,
    headers: HeaderMap,
) -> Response {
    let Some(root) = state.root_of(&folder_id) else {
        return (StatusCode::NOT_FOUND, "unknown folder").into_response();
    };
    let rel_path = decode_rel(&rel_path);
    if !valid_rel_path(&rel_path) {
        return (StatusCode::BAD_REQUEST, "invalid path").into_response();
    }
    let Some(full) = safe_join(&root, &rel_path) else {
        return (StatusCode::BAD_REQUEST, "invalid path").into_response();
    };
    let Ok(meta) = tokio::fs::metadata(&full).await else {
        return (StatusCode::NOT_FOUND, "no such file").into_response();
    };
    if !meta.is_file() {
        return (StatusCode::NOT_FOUND, "not a file").into_response();
    }
    let indexed_mtime = indexed_mtime(&state, &folder_id, &rel_path).await;
    let len = meta.len();
    let mime = mime_for(&rel_path);

    let mut file = match tokio::fs::File::open(&full).await {
        Ok(f) => f,
        Err(e) => {
            tracing::error!("open failed for {}: {e}", full.display());
            return (StatusCode::INTERNAL_SERVER_ERROR, "open failed").into_response();
        }
    };

    let mut resp: Response = match parse_range(headers.get(header::RANGE), len) {
        Some(Err(())) => (
            StatusCode::RANGE_NOT_SATISFIABLE,
            [(header::CONTENT_RANGE, format!("bytes */{len}"))],
        )
            .into_response(),
        Some(Ok((start, end))) => {
            if start > 0 && file.seek(std::io::SeekFrom::Start(start)).await.is_err() {
                return (StatusCode::INTERNAL_SERVER_ERROR, "seek failed").into_response();
            }
            let stream = ReaderStream::with_capacity(file.take(end - start + 1), 64 * 1024);
            (
                StatusCode::PARTIAL_CONTENT,
                [
                    (header::CONTENT_TYPE, mime.to_string()),
                    (header::CONTENT_RANGE, format!("bytes {start}-{end}/{len}")),
                    (header::ACCEPT_RANGES, "bytes".to_string()),
                ],
                axum::body::Body::from_stream(stream),
            )
                .into_response()
        }
        None => {
            let stream = ReaderStream::with_capacity(file, 64 * 1024);
            (
                StatusCode::OK,
                [
                    (header::CONTENT_TYPE, mime.to_string()),
                    (header::ACCEPT_RANGES, "bytes".to_string()),
                    (header::CONTENT_LENGTH, len.to_string()),
                ],
                axum::body::Body::from_stream(stream),
            )
                .into_response()
        }
    };
    if let Some((s, ns)) = indexed_mtime {
        if let Ok(v) = axum::http::HeaderValue::from_str(&s.to_string()) {
            resp.headers_mut().insert("X-Nester-Mtime-S", v);
        }
        if let Ok(v) = axum::http::HeaderValue::from_str(&ns.to_string()) {
            resp.headers_mut().insert("X-Nester-Mtime-Ns", v);
        }
    }
    resp
}

/// The indexed mtime of `rel_path`, for download mtime-fidelity headers.
/// Missing/deleted/dir rows yield None so the caller omits the headers.
async fn indexed_mtime(state: &AppState, folder_id: &str, rel_path: &str) -> Option<(i64, i64)> {
    let db = state.db(folder_id)?;
    let rel = rel_path.to_string();
    let entry = tokio::task::spawn_blocking(move || {
        let conn = db.lock().unwrap();
        nester_core::scan::get_entry(&conn, &rel).ok().flatten()
    })
    .await
    .ok()
    .flatten()?;
    if entry.deleted || entry.kind != EntryKind::File {
        return None;
    }
    Some((entry.mtime_s, entry.mtime_ns))
}

/// Single byte range only. `Ok((start, end))` inclusive; `Err(())` unsatisfiable;
/// `None` no/invalid header means full body.
fn parse_range(h: Option<&axum::http::HeaderValue>, len: u64) -> Option<Result<(u64, u64), ()>> {
    let raw = h?.to_str().ok()?;
    let spec = raw.strip_prefix("bytes=")?;
    let (start_s, end_s) = spec.split_once('-')?;
    if start_s.is_empty() {
        let suffix: u64 = end_s.parse().ok()?;
        if suffix == 0 || len == 0 {
            return Some(Err(()));
        }
        let start = len.saturating_sub(suffix);
        return Some(Ok((start, len - 1)));
    }
    let start: u64 = start_s.parse().ok()?;
    let end = if end_s.is_empty() {
        len.saturating_sub(1)
    } else {
        end_s.parse().ok()?
    };
    if len == 0 || start >= len || end < start {
        return Some(Err(()));
    }
    Some(Ok((start, end.min(len - 1))))
}

/// Reject traversal: every component must be plain, and the result must stay under root.
pub fn safe_join(root: &std::path::Path, rel: &str) -> Option<PathBuf> {
    if rel.is_empty() || rel.contains('\\') {
        return None;
    }
    let parts: Vec<&str> = rel.split('/').collect();
    if parts
        .iter()
        .any(|c| c.is_empty() || *c == "." || *c == "..")
    {
        return None;
    }
    let full = root.join(parts.join(std::path::MAIN_SEPARATOR_STR));
    if full.starts_with(root) {
        Some(full)
    } else {
        None
    }
}

/// Windows-host guard over the DECODED relative path: names must survive
/// landing on NTFS/Win32. Rejects reserved chars, control chars, dot/space
/// endings, empty segments, and oversized paths. Runs before `safe_join`, on
/// every handler that takes a file path.
fn valid_rel_path(rel: &str) -> bool {
    if rel.len() > 240 {
        return false;
    }
    rel.split('/').all(|seg| {
        !seg.is_empty()
            && !seg.ends_with('.')
            && !seg.ends_with(' ')
            && !seg.bytes().any(|b| b < 0x20 || b == 0x7f)
            && !seg.contains(['<', '>', ':', '"', '|', '?', '*'])
    })
}

/// Log-only: flag uploads whose name collides with an existing entry that
/// differs only by ASCII case in the same parent directory. Windows keeps the
/// host's casing on rename, so the disk file is authoritative; the phone's
/// mirror may briefly hold two entries until the next delta pull. No
/// user-facing behavior yet.
fn warn_case_collision(conn: &rusqlite::Connection, rel: &str) {
    let (parent, name) = rel.rsplit_once('/').unwrap_or(("", rel));
    let prefix = format!("{parent}/");
    let mut stmt = match conn.prepare("SELECT path FROM files WHERE deleted = 0") {
        Ok(s) => s,
        Err(e) => {
            tracing::debug!("case-collision probe skipped: {e}");
            return;
        }
    };
    let rows = match stmt.query_map([], |r| r.get::<_, String>(0)) {
        Ok(rows) => rows,
        Err(e) => {
            tracing::debug!("case-collision probe skipped: {e}");
            return;
        }
    };
    for row in rows.flatten() {
        if row == rel {
            continue;
        }
        let other_name = if parent.is_empty() {
            if row.contains('/') {
                continue;
            }
            row.as_str()
        } else {
            match row.strip_prefix(prefix.as_str()) {
                Some(rest) if !rest.contains('/') => rest,
                _ => continue,
            }
        };
        if other_name.eq_ignore_ascii_case(name) {
            tracing::warn!(
                "case-collision on upload: '{rel}' differs only by case from existing '{row}'"
            );
            return;
        }
    }
}

fn mime_for(path: &str) -> &'static str {
    let ext = path.rsplit('.').next().unwrap_or("").to_ascii_lowercase();
    match ext.as_str() {
        "jpg" | "jpeg" => "image/jpeg",
        "png" => "image/png",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "heic" => "image/heic",
        "mp4" => "video/mp4",
        "mov" => "video/quicktime",
        "webm" => "video/webm",
        "pdf" => "application/pdf",
        "txt" => "text/plain",
        _ => "application/octet-stream",
    }
}

pub fn public_routes(state: AppState) -> Router {
    Router::new()
        .route("/api/health", get(health))
        .with_state(state)
}

pub fn protected_routes(state: AppState) -> Router {
    Router::new()
        .route("/api/folders", get(list_folders))
        .route("/api/heartbeat", post(heartbeat))
        .route("/api/folders/{folder_id}/entries", get(list_entries))
        .route("/api/folders/{folder_id}/files/{*path}", get(download))
        .route("/api/folders/{folder_id}/files/{*path}", post(upload))
        .route(
            "/api/folders/{folder_id}/files/{*path}",
            delete(delete_file),
        )
        .layer(axum::middleware::from_fn_with_state(
            state.clone(),
            require_bearer,
        ))
        .with_state(state)
}

/// The phone pushed a file. Whole-body POST (tus-style resumable sessions are
/// deferred; see ticket 04 notes). Last-writer-wins: a stale upload gets 409.
#[utoipa::path(
    post,
    path = "/api/folders/{folder_id}/files/{*path}",
    params(("folder_id" = String, Path), ("path" = String, Path)),
    responses((status = 201), (status = 409)),
    security(("pairing_token" = []))
)]
pub async fn upload(
    State(state): State<AppState>,
    Path((folder_id, rel_path)): Path<(String, String)>,
    headers: HeaderMap,
    body: Body,
) -> Response {
    let Some(root) = state.root_of(&folder_id) else {
        return (StatusCode::NOT_FOUND, "unknown folder").into_response();
    };
    let Some(db) = state.db(&folder_id) else {
        return (StatusCode::NOT_FOUND, "unknown folder").into_response();
    };
    let rel_path = decode_rel(&rel_path);
    if !valid_rel_path(&rel_path) {
        return (StatusCode::BAD_REQUEST, "invalid path").into_response();
    }
    let Some(full) = safe_join(&root, &rel_path) else {
        return (StatusCode::BAD_REQUEST, "invalid path").into_response();
    };

    let (mtime_s, mtime_ns) = match parse_mtime(&headers) {
        Ok(v) => v,
        Err(e) => return (StatusCode::BAD_REQUEST, e).into_response(),
    };

    // Stream to temp while hashing, so a corrupt or aborted upload never
    // touches the real file.
    let tmp_dir = root.join(".nester-tmp");
    if let Err(e) = tokio::fs::create_dir_all(&tmp_dir).await {
        tracing::error!("tmp dir failed: {e}");
        return (StatusCode::INTERNAL_SERVER_ERROR, "write failed").into_response();
    }
    let tmp = tmp_dir.join(format!("upload-{}", rand::random::<u64>()));

    let mut hasher = blake3::Hasher::new();
    let mut size: u64 = 0;
    let mut file = match tokio::fs::File::create(&tmp).await {
        Ok(f) => f,
        Err(e) => {
            tracing::error!("temp create failed: {e}");
            return (StatusCode::INTERNAL_SERVER_ERROR, "write failed").into_response();
        }
    };
    let mut body = body.into_data_stream();
    loop {
        match body.next().await {
            Some(Ok(chunk)) => {
                hasher.update(&chunk);
                size += chunk.len() as u64;
                if let Err(e) = file.write_all(&chunk).await {
                    tracing::error!("temp write failed: {e}");
                    let _ = tokio::fs::remove_file(&tmp).await;
                    return (StatusCode::INTERNAL_SERVER_ERROR, "write failed").into_response();
                }
            }
            Some(Err(e)) => {
                tracing::error!("upload stream error: {e}");
                let _ = tokio::fs::remove_file(&tmp).await;
                return (StatusCode::BAD_REQUEST, "upload interrupted").into_response();
            }
            None => break,
        }
    }
    let _ = file.flush().await;
    drop(file);
    let hash = hasher.finalize().to_hex().to_string();

    let db = db.clone();
    let lookup_path = rel_path.clone();
    let existing = tokio::task::spawn_blocking(move || {
        let conn = db.lock().unwrap();
        nester_core::scan::get_entry(&conn, &lookup_path)
    })
    .await;
    let existing = match existing {
        Ok(Ok(e)) => e,
        Ok(Err(e)) => {
            tracing::error!("index read failed: {e}");
            let _ = tokio::fs::remove_file(&tmp).await;
            return (StatusCode::INTERNAL_SERVER_ERROR, "index failed").into_response();
        }
        Err(e) => {
            tracing::error!("index task failed: {e}");
            let _ = tokio::fs::remove_file(&tmp).await;
            return (StatusCode::INTERNAL_SERVER_ERROR, "internal error").into_response();
        }
    };

    if let Some(existing) = existing
        && !existing.deleted
        && existing.kind == EntryKind::File
        && existing.is_fresher(&FileEntry {
            path: rel_path.clone(),
            kind: EntryKind::File,
            size,
            mtime_s,
            mtime_ns,
            deleted: false,
            hash: Some(hash.clone()),
            sequence: 0,
        })
    {
        let _ = tokio::fs::remove_file(&tmp).await;
        return (StatusCode::CONFLICT, "stale version: pull first").into_response();
    }

    if let Some(parent) = full.parent()
        && let Err(e) = tokio::fs::create_dir_all(&parent).await
    {
        tracing::error!("mkdir failed: {e}");
        let _ = tokio::fs::remove_file(&tmp).await;
        return (StatusCode::INTERNAL_SERVER_ERROR, "write failed").into_response();
    }
    if let Err(e) = tokio::fs::rename(&tmp, &full).await {
        tracing::error!("commit move failed: {e}");
        return (StatusCode::INTERNAL_SERVER_ERROR, "write failed").into_response();
    }

    let db = state.db(&folder_id).unwrap();
    let dirs = collect_missing_dirs(&rel_path);
    let event_path = rel_path.clone();
    let rel = rel_path;
    let result = tokio::task::spawn_blocking(move || {
        let mut conn = db.lock().unwrap();
        warn_case_collision(&conn, &rel);
        for dir in dirs {
            let _ = nester_core::scan::apply_file_change(
                &mut conn,
                &FileEntry {
                    path: dir,
                    kind: EntryKind::Dir,
                    size: 0,
                    mtime_s: 0,
                    mtime_ns: 0,
                    deleted: false,
                    hash: None,
                    sequence: 0,
                },
            );
        }
        nester_core::scan::apply_file_change(
            &mut conn,
            &FileEntry {
                path: rel,
                kind: EntryKind::File,
                size,
                mtime_s,
                mtime_ns,
                deleted: false,
                hash: Some(hash),
                sequence: 0,
            },
        )
    })
    .await;

    match result {
        Ok(Ok(())) => {
            state.mark_syncing(&folder_id, 6);
            state.note_event(format!(
                "{}: received {} ({} bytes)",
                state
                    .folder_label(&folder_id)
                    .unwrap_or_else(|| folder_id.clone()),
                event_path,
                size
            ));
            (StatusCode::CREATED, "stored").into_response()
        }
        Ok(Err(e)) => {
            tracing::error!("index update failed: {e}");
            (StatusCode::INTERNAL_SERVER_ERROR, "index failed").into_response()
        }
        Err(e) => {
            tracing::error!("index task failed: {e}");
            (StatusCode::INTERNAL_SERVER_ERROR, "internal error").into_response()
        }
    }
}

/// Phone deletion: delete the disk file, mark the index; the delta pull tells
/// other devices. Deletion wins in v1 (ticket 09).
#[utoipa::path(
    delete,
    path = "/api/folders/{folder_id}/files/{*path}",
    params(("folder_id" = String, Path), ("path" = String, Path)),
    responses((status = 200)),
    security(("pairing_token" = []))
)]
pub async fn delete_file(
    State(state): State<AppState>,
    Path((folder_id, rel_path)): Path<(String, String)>,
) -> Response {
    let Some(root) = state.root_of(&folder_id) else {
        return (StatusCode::NOT_FOUND, "unknown folder").into_response();
    };
    let Some(db) = state.db(&folder_id) else {
        return (StatusCode::NOT_FOUND, "unknown folder").into_response();
    };
    let rel_path = decode_rel(&rel_path);
    if !valid_rel_path(&rel_path) {
        return (StatusCode::BAD_REQUEST, "invalid path").into_response();
    }
    let Some(full) = safe_join(&root, &rel_path) else {
        return (StatusCode::BAD_REQUEST, "invalid path").into_response();
    };
    let _ = tokio::fs::remove_file(&full).await;
    let event_path = rel_path.clone();
    let rel = rel_path;
    let result = tokio::task::spawn_blocking(move || {
        let mut conn = db.lock().unwrap();
        nester_core::scan::mark_deleted(&mut conn, &rel)
    })
    .await;
    match result {
        Ok(Ok(_)) => {
            state.mark_syncing(&folder_id, 6);
            state.note_event(format!(
                "{}: deleted {}",
                state
                    .folder_label(&folder_id)
                    .unwrap_or_else(|| folder_id.clone()),
                event_path
            ));
            (StatusCode::OK, "deleted").into_response()
        }
        Ok(Err(e)) => {
            tracing::error!("delete index failed: {e}");
            (StatusCode::INTERNAL_SERVER_ERROR, "index failed").into_response()
        }
        Err(e) => {
            tracing::error!("delete task failed: {e}");
            (StatusCode::INTERNAL_SERVER_ERROR, "internal error").into_response()
        }
    }
}

/// Parent dirs of `rel` that must exist in the index after an upload.
fn collect_missing_dirs(rel: &str) -> Vec<String> {
    let parts: Vec<&str> = rel.split('/').collect();
    let mut dirs = Vec::new();
    let mut acc = String::new();
    for part in parts.iter().take(parts.len().saturating_sub(1)) {
        if !acc.is_empty() {
            acc.push('/');
        }
        acc.push_str(part);
        dirs.push(acc.clone());
    }
    dirs
}

/// `X-Nester-Mtime-S` seconds + `X-Nester-Mtime-Ns` nanos, defaults to now.
fn parse_mtime(headers: &HeaderMap) -> Result<(i64, i64), &'static str> {
    let s = headers
        .get("X-Nester-Mtime-S")
        .and_then(|v| v.to_str().ok())
        .map(|v| v.parse::<i64>().map_err(|_| "bad X-Nester-Mtime-S"));
    let ns = headers
        .get("X-Nester-Mtime-Ns")
        .and_then(|v| v.to_str().ok())
        .map(|v| v.parse::<i64>().map_err(|_| "bad X-Nester-Mtime-Ns"));
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default();
    let s = match s {
        Some(Ok(v)) => v,
        Some(Err(e)) => return Err(e),
        None => now.as_secs() as i64,
    };
    let ns = match ns {
        Some(Ok(v)) => v,
        Some(Err(e)) => return Err(e),
        None => now.subsec_nanos() as i64,
    };
    Ok((s, ns))
}

/// Axum wildcard captures keep percent-encoding; paths in the index are clean.
fn decode_rel(raw: &str) -> String {
    percent_encoding::percent_decode_str(raw)
        .decode_utf8_lossy()
        .into_owned()
}
