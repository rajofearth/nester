use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr, UdpSocket};
use std::sync::{Arc, Mutex};

use base64::Engine;
use clap::Parser;
use nester_core::Folder;
use nester_server::state::AppState;

#[derive(Parser)]
#[command(
    name = "nester-host",
    about = "Nester host: syncs folders to your phone over WiFi"
)]
struct Args {
    /// Folders to sync. Repeat for multiple.
    #[arg(long = "folder", required = true)]
    folders: Vec<std::path::PathBuf>,
    #[arg(long, default_value_t = 7300)]
    port: u16,
    /// Where the pairing token and per-folder indexes live.
    #[arg(long)]
    data_dir: Option<std::path::PathBuf>,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "nester=info,nester_server=info".into()),
        )
        .init();

    let args = Args::parse();
    let data_dir = args.data_dir.unwrap_or_else(default_data_dir);
    std::fs::create_dir_all(data_dir.join("index"))?;
    let token = load_or_create_token(&data_dir)?;

    let mut folders = Vec::new();
    let mut dbs = HashMap::new();
    for path in &args.folders {
        let canonical = path.canonicalize()?;
        let id = nester_core::folder_id(&canonical);
        let db_path = data_dir.join("index").join(format!("{id}.db"));
        let mut conn = nester_core::open_db(&db_path)?;
        let stats = nester_core::scan::scan_folder(&mut conn, &canonical)?;
        tracing::info!(
            "folder {} ({}): {} added, {} updated, {} unchanged, {} removed",
            canonical.display(),
            id,
            stats.added,
            stats.updated,
            stats.unchanged,
            stats.removed
        );
        dbs.insert(id.clone(), Arc::new(Mutex::new(conn)));
        folders.push(Folder {
            id,
            label: canonical
                .file_name()
                .map(|n| n.to_string_lossy().into_owned())
                .unwrap_or_default(),
            root: canonical,
        });
    }

    let state = AppState::new(folders.clone(), dbs, token.clone());
    let addr = SocketAddr::from(([0, 0, 0, 0], args.port));

    // Pairing payload the phone's QR scanner consumes.
    if let Some(ip) = local_ip() {
        println!("Scan this with the Nester app, or open the URL on your phone:");
        println!("  nester://pair?host={ip}&port={}&token={token}", args.port);
        println!("  http://{ip}:{}/api/health", args.port);
    } else {
        println!("Could not detect LAN IP; phone must target this machine manually.");
    }
    println!("Pairing token: {token}");

    let listener = tokio::net::TcpListener::bind(addr).await?;
    tracing::info!("serving {} folder(s) on {addr}", folders.len());
    tokio::select! {
        result = nester_server::serve(listener, state) => result?,
        _ = shutdown_signal() => tracing::info!("shutting down"),
    }
    Ok(())
}

fn default_data_dir() -> std::path::PathBuf {
    dirs::data_dir()
        .unwrap_or_else(std::env::temp_dir)
        .join("nester")
}

/// The pairing token survives restarts so the phone only pairs once. Printed
/// to stdout because the headless path has no QR window yet (ticket 07).
fn load_or_create_token(data_dir: &std::path::Path) -> anyhow::Result<String> {
    let path = data_dir.join("pairing-token");
    if let Ok(existing) = std::fs::read_to_string(&path) {
        let trimmed = existing.trim().to_string();
        if trimmed.len() >= 32 {
            return Ok(trimmed);
        }
    }
    let token = new_token();
    std::fs::write(&path, &token)?;
    Ok(token)
}

fn new_token() -> String {
    use rand::RngCore;
    let mut bytes = [0u8; 32];
    rand::rng().fill_bytes(&mut bytes);
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}

fn local_ip() -> Option<IpAddr> {
    let socket = UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("8.8.8.8:80").ok()?;
    socket.local_addr().ok().map(|a| a.ip())
}

async fn shutdown_signal() {
    let _ = tokio::signal::ctrl_c().await;
    tracing::info!("shutting down");
}
