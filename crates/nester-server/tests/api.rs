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

    fn test_router_with_dir() -> (Router, std::path::PathBuf) {
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
        (
            router(AppState::new(
                vec![folder],
                dbs,
                "tok123".into(),
                Default::default(),
                Default::default(),
            )),
            dir,
        )
    }

    fn test_router() -> Router {
        test_router_with_dir().0
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
            .clone()
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
            .clone()
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
            .clone()
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
            .clone()
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

    #[tokio::test]
    async fn download_serves_indexed_mtime_headers() {
        let r = test_router();
        let res = r
            .clone()
            .oneshot(
                Request::get("/api/folders/pics/entries?since=0")
                    .header(header::AUTHORIZATION, "Bearer tok123")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        let body = res.into_body().collect().await.unwrap().to_bytes();
        let entries: Vec<nester_core::FileEntry> = serde_json::from_slice(&body).unwrap();
        let a = entries.iter().find(|e| e.path == "a.txt").unwrap();

        let res = r
            .oneshot(
                Request::get("/api/folders/pics/files/a.txt")
                    .header(header::AUTHORIZATION, "Bearer tok123")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        let s = res
            .headers()
            .get("X-Nester-Mtime-S")
            .expect("mtime s header")
            .to_str()
            .unwrap()
            .parse::<i64>()
            .unwrap();
        let ns = res
            .headers()
            .get("X-Nester-Mtime-Ns")
            .expect("mtime ns header")
            .to_str()
            .unwrap()
            .parse::<i64>()
            .unwrap();
        assert_eq!(s, a.mtime_s);
        assert_eq!(ns, a.mtime_ns);
    }

    #[tokio::test]
    async fn download_omits_mtime_headers_without_index_row() {
        let (r, dir) = test_router_with_dir();
        std::fs::write(dir.join("unindexed.txt"), b"new on disk").unwrap();
        let res = r
            .oneshot(
                Request::get("/api/folders/pics/files/unindexed.txt")
                    .header(header::AUTHORIZATION, "Bearer tok123")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        assert!(res.headers().get("X-Nester-Mtime-S").is_none());
        assert!(res.headers().get("X-Nester-Mtime-Ns").is_none());
    }

    #[tokio::test]
    async fn upload_rejects_windows_unsafe_names() {
        let r = test_router();
        let cases = [
            "/api/folders/pics/files/bad%3Aname.txt",
            "/api/folders/pics/files/name%20",
        ];
        for case in cases {
            let res = r
                .clone()
                .oneshot(
                    Request::post(case)
                        .header(header::AUTHORIZATION, "Bearer tok123")
                        .body(Body::from(b"x".to_vec()))
                        .unwrap(),
                )
                .await
                .unwrap();
            assert_eq!(res.status(), StatusCode::BAD_REQUEST, "case: {case}");
        }
        let res = r
            .oneshot(
                Request::post("/api/folders/pics/files/ok%20name.txt")
                    .header(header::AUTHORIZATION, "Bearer tok123")
                    .header("X-Nester-Mtime-S", "2000")
                    .body(Body::from(b"fine".to_vec()))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::CREATED);
    }

    #[tokio::test]
    async fn download_rejects_windows_unsafe_names() {
        let r = test_router();
        let res = r
            .oneshot(
                Request::get("/api/folders/pics/files/bad%3Aname.txt")
                    .header(header::AUTHORIZATION, "Bearer tok123")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::BAD_REQUEST);
    }

    #[tokio::test]
    async fn entries_since_stale_cursor_conflicts() {
        let r = test_router();
        let res = r
            .clone()
            .oneshot(
                Request::get("/api/folders/pics/entries?since=999999")
                    .header(header::AUTHORIZATION, "Bearer tok123")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::CONFLICT);
        let body = res.into_body().collect().await.unwrap().to_bytes();
        assert_eq!(&body[..], b"index-reset");

        let res = r
            .oneshot(
                Request::get("/api/folders/pics/entries?since=99999999")
                    .header(header::AUTHORIZATION, "Bearer tok123")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::CONFLICT);
    }

    #[test]
    fn openapi_exports() {
        let doc = openapi_json();
        assert!(doc.get("paths").is_some());
    }

    #[tokio::test]
    async fn upload_applies_and_index_updates() {
        let r = test_router();
        let res = r
            .clone()
            .oneshot(
                Request::post("/api/folders/pics/files/new/dir/photo.jpg")
                    .header(header::AUTHORIZATION, "Bearer tok123")
                    .header("X-Nester-Mtime-S", "2000")
                    .header("X-Nester-Mtime-Ns", "0")
                    .body(Body::from(b"jpeg-bytes".to_vec()))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::CREATED);

        let res = r
            .clone()
            .oneshot(
                Request::get("/api/folders/pics/entries?since=0")
                    .header(header::AUTHORIZATION, "Bearer tok123")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        let body = res.into_body().collect().await.unwrap().to_bytes();
        let entries: Vec<nester_core::FileEntry> = serde_json::from_slice(&body).unwrap();
        let photo = entries
            .iter()
            .find(|e| e.path == "new/dir/photo.jpg")
            .unwrap();
        assert_eq!(
            photo.hash.as_deref(),
            Some(blake3::hash(b"jpeg-bytes").to_hex().as_str())
        );
        assert!(
            entries
                .iter()
                .any(|e| e.path == "new" && e.kind == nester_core::EntryKind::Dir)
        );
        assert!(
            entries
                .iter()
                .any(|e| e.path == "new/dir" && e.kind == nester_core::EntryKind::Dir)
        );

        let res = r
            .clone()
            .oneshot(
                Request::get("/api/folders/pics/files/new/dir/photo.jpg")
                    .header(header::AUTHORIZATION, "Bearer tok123")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        let body = res.into_body().collect().await.unwrap().to_bytes();
        assert_eq!(&body[..], b"jpeg-bytes");
    }

    #[tokio::test]
    async fn stale_upload_rejected_with_409() {
        let r = test_router();
        // a.txt is indexed with its real mtime (now-ish). Upload with an old
        // mtime so it looks stale.
        let res = r
            .clone()
            .oneshot(
                Request::post("/api/folders/pics/files/a.txt")
                    .header(header::AUTHORIZATION, "Bearer tok123")
                    .header("X-Nester-Mtime-S", "100")
                    .header("X-Nester-Mtime-Ns", "0")
                    .body(Body::from(b"stale".to_vec()))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::CONFLICT);
    }

    #[tokio::test]
    async fn newer_upload_overwrites() {
        let r = test_router();
        let future_s: i64 = 4102444800; // 2100, definitely fresher than now
        let res = r
            .clone()
            .oneshot(
                Request::post("/api/folders/pics/files/a.txt")
                    .header(header::AUTHORIZATION, "Bearer tok123")
                    .header("X-Nester-Mtime-S", future_s.to_string())
                    .body(Body::from(b"newer".to_vec()))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::CREATED);
        let res = r
            .clone()
            .oneshot(
                Request::get("/api/folders/pics/files/a.txt")
                    .header(header::AUTHORIZATION, "Bearer tok123")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        let body = res.into_body().collect().await.unwrap().to_bytes();
        assert_eq!(&body[..], b"newer");
    }

    #[tokio::test]
    async fn upload_with_spaces_round_trips_clean() {
        let r = test_router();
        let res = r
            .clone()
            .oneshot(
                Request::post("/api/folders/pics/files/my%20photos%2Fholiday%20pic.jpg")
                    .header(header::AUTHORIZATION, "Bearer tok123")
                    .header("X-Nester-Mtime-S", "2000")
                    .body(Body::from(b"pic".to_vec()))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::CREATED);

        let res = r
            .clone()
            .oneshot(
                Request::get("/api/folders/pics/entries?since=0")
                    .header(header::AUTHORIZATION, "Bearer tok123")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        let body = res.into_body().collect().await.unwrap().to_bytes();
        let entries: Vec<nester_core::FileEntry> = serde_json::from_slice(&body).unwrap();
        assert!(
            entries
                .iter()
                .any(|e| e.path == "my photos/holiday pic.jpg")
        );

        let res = r
            .oneshot(
                Request::get("/api/folders/pics/files/my%20photos%2Fholiday%20pic.jpg")
                    .header(header::AUTHORIZATION, "Bearer tok123")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn delete_marks_and_propagates() {
        let r = test_router();
        let res = r
            .clone()
            .oneshot(
                Request::delete("/api/folders/pics/files/a.txt")
                    .header(header::AUTHORIZATION, "Bearer tok123")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::OK);

        let res = r
            .clone()
            .oneshot(
                Request::get("/api/folders/pics/files/a.txt")
                    .header(header::AUTHORIZATION, "Bearer tok123")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::NOT_FOUND);

        let res = r
            .clone()
            .oneshot(
                Request::get("/api/folders/pics/entries?since=0")
                    .header(header::AUTHORIZATION, "Bearer tok123")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        let body = res.into_body().collect().await.unwrap().to_bytes();
        let entries: Vec<nester_core::FileEntry> = serde_json::from_slice(&body).unwrap();
        assert!(entries.iter().any(|e| e.path == "a.txt" && e.deleted));
    }
}
