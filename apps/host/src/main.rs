mod config;
mod theme;
mod ui;
mod win;

use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr, UdpSocket};
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::Mutex;
use std::sync::atomic::AtomicBool;
use std::time::Duration;

use base64::Engine;
use clap::Parser;
use nester_core::Folder;
use nester_server::state::AppState;

use config::Config;

#[derive(Parser)]
#[command(
    name = "nester-host",
    about = "Nester host: syncs folders to your phone over WiFi"
)]
struct Args {
    /// Folders to sync. Repeat for multiple. Overrides config.toml.
    #[arg(long = "folder")]
    folders: Vec<std::path::PathBuf>,
    /// Overrides config.toml.
    #[arg(long)]
    port: Option<u16>,
    /// Where the pairing token, config, and per-folder indexes live.
    #[arg(long)]
    data_dir: Option<std::path::PathBuf>,
    /// Run the server without the GPUI window.
    #[arg(long)]
    headless: bool,
}

pub struct HostRuntime {
    pub ip: Option<IpAddr>,
    pub port: u16,
    pub pairing_token: String,
    pub config_path: PathBuf,
    pub config: Mutex<Config>,
    pub folders: Mutex<Vec<Folder>>,
    pub last_scan: Mutex<Option<ui::ScanLog>>,
    pub pending_restart: AtomicBool,
}

impl HostRuntime {
    pub fn pairing_payload(&self) -> String {
        match self.ip {
            Some(ip) => format!(
                "nester://pair?host={ip}&port={}&token={}",
                self.port, self.pairing_token
            ),
            None => format!(
                "nester://pair?host=MISSING&port={}&token={}",
                self.port, self.pairing_token
            ),
        }
    }
}

fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "nester=info,nester_server=info".into()),
        )
        .init();

    let args = Args::parse();
    let data_dir = args.data_dir.unwrap_or_else(default_data_dir);
    std::fs::create_dir_all(data_dir.join("index"))?;
    let config_path = Config::data_path(&data_dir);
    let mut config = Config::load(&config_path).unwrap_or_default();
    let port = args
        .port
        .or(if config.port > 0 {
            Some(config.port)
        } else {
            None
        })
        .unwrap_or(7300);
    config.port = port;

    let folder_paths: Vec<PathBuf> = if args.folders.is_empty() {
        config.folders.iter().map(PathBuf::from).collect()
    } else {
        args.folders.clone()
    };

    let token = load_or_create_token(&data_dir)?;

    let mut folders = Vec::new();
    let mut dbs = HashMap::new();
    for path in &folder_paths {
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
    config.folders = folders
        .iter()
        .map(|f| f.root.to_string_lossy().into_owned())
        .collect();
    config.save(&config_path)?;

    let runtime = Arc::new(HostRuntime {
        ip: local_ip(),
        port,
        pairing_token: token.clone(),
        config_path: config_path.clone(),
        config: Mutex::new(config),
        folders: Mutex::new(folders.clone()),
        last_scan: Mutex::new(None),
        pending_restart: AtomicBool::new(false),
    });

    let server_runtime = Arc::clone(&runtime);
    let server_folders = folders.clone();
    let server_dbs = dbs.clone();
    let server_token = token.clone();
    std::thread::Builder::new()
        .name("nester-server".into())
        .spawn(move || -> anyhow::Result<()> {
            let rt = tokio::runtime::Builder::new_multi_thread()
                .enable_all()
                .build()?;
            rt.block_on(async move {
                let watchers = start_rescans(&server_runtime, &server_folders, &server_dbs)?;
                let state = AppState::new(server_folders, server_dbs, server_token);
                let addr = SocketAddr::from(([0, 0, 0, 0], port));
                let listener = tokio::net::TcpListener::bind(addr).await?;
                tracing::info!("serving on {addr}");
                nester_server::serve(listener, state).await?;
                drop(watchers);
                anyhow::Ok(())
            })?;
            anyhow::Ok(())
        })?;

    if args.headless {
        let payload = runtime.pairing_payload();
        println!("{payload}");
        println!("Nester host running headless. Ctrl-C to stop.");
        loop {
            std::thread::sleep(Duration::from_secs(3600));
        }
    }

    gpui_platform::application().run(move |cx: &mut gpui::App| {
        ui::HostWindow::open(cx, runtime.clone());
    });

    Ok(())
}

fn start_rescans(
    runtime: &Arc<HostRuntime>,
    folders: &[Folder],
    dbs: &HashMap<String, Arc<Mutex<rusqlite::Connection>>>,
) -> anyhow::Result<Vec<nester_core::watch::FolderWatcher>> {
    let mut watchers = Vec::new();
    for folder in folders {
        let (tx, rx) = std::sync::mpsc::channel::<()>();
        watchers.push(nester_core::watch::FolderWatcher::start(
            &folder.root,
            Duration::from_secs(2),
            tx.clone(),
        )?);

        let tx_periodic = tx.clone();
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(Duration::from_secs(30));
            interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
            interval.tick().await;
            loop {
                interval.tick().await;
                let _ = tx_periodic.send(());
            }
        });

        let root = folder.root.clone();
        let label = folder.label.clone();
        let db = dbs[&folder.id].clone();
        let runtime = Arc::clone(runtime);
        tokio::task::spawn_blocking(move || {
            while rx.recv().is_ok() {
                while rx.try_recv().is_ok() {}
                let mut conn = db.lock().unwrap();
                match nester_core::scan::scan_folder(&mut conn, &root) {
                    Ok(stats) => {
                        tracing::info!(
                            "rescan {label}: {} added, {} updated, {} removed",
                            stats.added,
                            stats.updated,
                            stats.removed
                        );
                        *runtime.last_scan.lock().unwrap() = Some(ui::ScanLog {
                            label: label.clone(),
                            stats,
                        });
                    }
                    Err(e) => tracing::error!("rescan {label} failed: {e}"),
                }
            }
        });
    }
    Ok(watchers)
}

fn default_data_dir() -> std::path::PathBuf {
    dirs::data_dir()
        .unwrap_or_else(std::env::temp_dir)
        .join("nester")
}

/// The pairing token survives restarts so the phone only pairs once. Printed
/// to stdout because the headless path has no QR window.
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
