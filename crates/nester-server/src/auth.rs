use axum::extract::State;
use axum::http::HeaderMap;
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};
use subtle::ConstantTimeEq;

use crate::state::AppState;

pub async fn require_bearer(
    State(state): State<AppState>,
    headers: HeaderMap,
    request: axum::extract::Request,
    next: Next,
) -> Response {
    let provided = headers
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "));

    let Some(provided) = provided else {
        return unauthorized();
    };

    let ok = provided.as_bytes().ct_eq(state.pairing_token.as_bytes());
    if bool::from(ok) {
        next.run(request).await
    } else {
        unauthorized()
    }
}

fn unauthorized() -> Response {
    (
        axum::http::StatusCode::UNAUTHORIZED,
        "missing or invalid pairing token",
    )
        .into_response()
}
