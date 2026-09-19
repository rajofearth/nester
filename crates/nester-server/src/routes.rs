use std::path::PathBuf;

use axum::extract::{Path, Query, State};
use axum::http::{HeaderMap, StatusCode, header};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use nester_core::{FileEntry, Folder};
use tokio::io::{AsyncReadExt, AsyncSeekExt};
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
        .route("/api/uploads/{folder_id}/{*path}", post(upload_placeholder))
        .layer(axum::middleware::from_fn_with_state(
            state.clone(),
            require_bearer,
        ))
        .with_state(state)
}

/// Ticket 04 replaces this with the tus-style offset protocol.
async fn upload_placeholder(Path(_): Path<(String, String)>) -> Response {
    (StatusCode::NOT_IMPLEMENTED, "uploads land in ticket 04").into_response()
}
