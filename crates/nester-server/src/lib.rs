pub mod auth;
pub mod routes;
pub mod state;

use axum::Router;
use utoipa::OpenApi;

#[derive(OpenApi)]
#[openapi(
    info(title = "Nester Host API", version = "0.2.0"),
    paths(
        routes::health,
        routes::list_folders,
        routes::heartbeat,
        routes::list_entries,
        routes::download,
    ),
    components(schemas(
        nester_core::Folder,
        nester_core::FileEntry,
        nester_core::EntryKind,
        routes::FolderStats,
        routes::FolderWithStats,
        routes::FoldersResponse,
    ))
)]
pub struct ApiDoc;

pub fn openapi_json() -> serde_json::Value {
    serde_json::to_value(ApiDoc::openapi()).expect("openapi serializes")
}

pub fn router(state: state::AppState) -> Router {
    Router::new()
        .merge(routes::public_routes(state.clone()))
        .merge(routes::protected_routes(state))
        .fallback(|| async { axum::http::StatusCode::NOT_FOUND })
}

/// Bind and serve. The host binary stays free of axum details.
pub async fn serve(
    listener: tokio::net::TcpListener,
    state: state::AppState,
) -> std::io::Result<()> {
    axum::serve(
        listener,
        router(state).into_make_service_with_connect_info::<std::net::SocketAddr>(),
    )
    .await
}
