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

#[derive(serde::Serialize, utoipa::ToSchema)]
pub struct FoldersResponse {
    pub folders: Vec<Folder>,
}

#[utoipa::path(
    get,
    path = "/api/folders",
    responses((status = 200, body = FoldersResponse)),
    security(("pairing_token" = []))
)]
pub async fn list_folders(State(state): State<AppState>) -> Json<FoldersResponse> {
    Json(FoldersResponse {
        folders: state.folders.as_ref().clone(),
    })
}

#[derive(serde::Deserialize, utoipa::IntoParams)]
pub struct EntriesQuery {
    pub since: Option<i64>,
}

#[utoipa::path(
    get,
    path = "/api/folders/{folder_id}/entries",
    params(("folder_id" = String, Path), EntriesQuery),
    responses((status = 200, body = Vec<FileEntry>)),
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
        nester_core::scan::entries_since(&conn, since)
    })
    .await;
    match result {
        Ok(Ok(entries)) => Json(entries).into_response(),
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
    let Some(full) = safe_join(&root, &rel_path) else {
        return (StatusCode::BAD_REQUEST, "invalid path").into_response();
    };
    let Ok(meta) = tokio::fs::metadata(&full).await else {
        return (StatusCode::NOT_FOUND, "no such file").into_response();
    };
    if !meta.is_file() {
        return (StatusCode::NOT_FOUND, "not a file").into_response();
    }
    let len = meta.len();
    let mime = mime_for(&rel_path);

    let mut file = match tokio::fs::File::open(&full).await {
        Ok(f) => f,
        Err(e) => {
            tracing::error!("open failed for {}: {e}", full.display());
            return (StatusCode::INTERNAL_SERVER_ERROR, "open failed").into_response();
        }
    };

    match parse_range(headers.get(header::RANGE), len) {
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
    }
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
    let rel = rel_path;
    let result = tokio::task::spawn_blocking(move || {
        let mut conn = db.lock().unwrap();
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
        Ok(Ok(())) => (StatusCode::CREATED, "stored").into_response(),
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
    let Some(full) = safe_join(&root, &rel_path) else {
        return (StatusCode::BAD_REQUEST, "invalid path").into_response();
    };
    let _ = tokio::fs::remove_file(&full).await;
    let rel = rel_path;
    let result = tokio::task::spawn_blocking(move || {
        let mut conn = db.lock().unwrap();
        nester_core::scan::mark_deleted(&mut conn, &rel)
    })
    .await;
    match result {
        Ok(Ok(_)) => (StatusCode::OK, "deleted").into_response(),
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
