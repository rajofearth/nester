#[cfg(test)]
mod tests {
    use std::collections::HashMap;
    use std::sync::{Arc, Mutex};

    use axum::Router;
    use axum::body::Body;
    use axum::http::{Request, StatusCode, header};
    use http_body_util::BodyExt;
    use nester_core::Folder;
    use tower::ServiceExt;

    use nester_server::state::AppState;
    use nester_server::{openapi_json, router};

    fn test_router() -> Router {
        let dir = nester_core::test_root("server-api");
        let db = dir
            .parent()
            .unwrap()
            .join(format!("{}.db", dir.file_name().unwrap().to_string_lossy()));
        std::fs::write(dir.join("a.txt"), b"hello nester").unwrap();
        let mut conn = nester_core::open_db(&db).unwrap();
        nester_core::scan::scan_folder(&mut conn, &dir).unwrap();
        let folder = Folder {
            id: "pics".into(),
            label: "Pictures".into(),
            root: dir.clone(),
        };
        let mut dbs = HashMap::new();
        dbs.insert("pics".to_string(), Arc::new(Mutex::new(conn)));
        router(AppState::new(vec![folder], dbs, "tok123".into()))
    }

    #[tokio::test]
    async fn health_is_public() {
        let res = test_router()
            .oneshot(Request::get("/api/health").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn folders_need_token() {
        let r = test_router();
        let res = r
            .clone()
            .oneshot(Request::get("/api/folders").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::UNAUTHORIZED);
        let res = r
            .oneshot(
                Request::get("/api/folders")
                    .header(header::AUTHORIZATION, "Bearer tok123")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn entries_delta_pull_works() {
        let r = test_router();
        let res = r
            .oneshot(
                Request::get("/api/folders/pics/entries?since=0")
                    .header(header::AUTHORIZATION, "Bearer tok123")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        let body = res.into_body().collect().await.unwrap().to_bytes();
        let entries: Vec<nester_core::FileEntry> = serde_json::from_slice(&body).unwrap();
        assert!(entries.iter().any(|e| e.path == "a.txt"));
    }

    #[tokio::test]
    async fn download_rejects_traversal() {
        let r = test_router();
        let res = r
            .oneshot(
                Request::get("/api/folders/pics/files/../../etc/passwd")
                    .header(header::AUTHORIZATION, "Bearer tok123")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::BAD_REQUEST);
    }

    #[tokio::test]
    async fn download_supports_range() {
        let r = test_router();
        let res = r
            .oneshot(
                Request::get("/api/folders/pics/files/a.txt")
                    .header(header::AUTHORIZATION, "Bearer tok123")
                    .header(header::RANGE, "bytes=0-4")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::PARTIAL_CONTENT);
        let body = res.into_body().collect().await.unwrap().to_bytes();
        assert_eq!(&body[..], b"hello");
    }

    #[test]
    fn openapi_exports() {
        let doc = openapi_json();
        assert!(doc.get("paths").is_some());
    }
}
