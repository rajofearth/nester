#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod config;
mod theme;
mod tray;
mod ui;
mod win;

use std::collections::{HashMap, VecDeque};
use std::net::{IpAddr, SocketAddr, UdpSocket};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::Mutex;
use std::sync::atomic::AtomicBool;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

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
    pub crashed_last_run: bool,
    pub devices: Arc<Mutex<HashMap<IpAddr, Instant>>>,
    pub events: Arc<Mutex<VecDeque<String>>>,
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
    single_instance();

    let args = Args::parse();
    let data_dir = args.data_dir.unwrap_or_else(default_data_dir);
    std::fs::create_dir_all(data_dir.join("index"))?;
    std::fs::create_dir_all(data_dir.join("logs"))?;
    install_panic_hook(&data_dir);
    let _log_guard = init_logging(&data_dir);

    let crashed_last_run = take_crash_note(&data_dir);
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

    let devices: Arc<Mutex<HashMap<IpAddr, Instant>>> = Arc::new(Mutex::new(HashMap::new()));
    let events: Arc<Mutex<VecDeque<String>>> = Arc::new(Mutex::new(VecDeque::new()));
    let runtime = Arc::new(HostRuntime {
        ip: local_ip(),
        port,
        pairing_token: token.clone(),
        config_path: config_path.clone(),
        config: Mutex::new(config),
        folders: Mutex::new(folders.clone()),
        last_scan: Mutex::new(None),
        pending_restart: AtomicBool::new(false),
        crashed_last_run,
        devices: devices.clone(),
        events: events.clone(),
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
                let state =
                    AppState::new(server_folders, server_dbs, server_token, devices, events);
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

    let (tray_tx, tray_rx) = std::sync::mpsc::channel::<tray::UiMessage>();
    let _tray = tray::TrayController::spawn(tray_tx);

    gpui_platform::application().run(move |cx: &mut gpui::App| {
        let handle = ui::HostWindow::open(cx, runtime.clone(), tray_rx);
        handle
            .update(cx, |_, window, cx| {
                window.on_window_should_close(cx, move |_, _| {
                    if let Some(hwnd) = win::find_hwnd() {
                        win::hide(hwnd);
                        false
                    } else {
                        true
                    }
                });
            })
            .ok();
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
            let mut last_scan_at: Option<Instant> = None;
            while rx.recv().is_ok() {
                while rx.try_recv().is_ok() {}
                // Quiet window: phone uploads and editors both burst; one scan
                // after things settle beats a scan per write.
                std::thread::sleep(Duration::from_secs(3));
                while rx.try_recv().is_ok() {}
                if let Some(t) = last_scan_at {
                    let since = t.elapsed();
                    if since < Duration::from_secs(2) {
                        std::thread::sleep(Duration::from_secs(2) - since);
                    }
                }
                last_scan_at = Some(Instant::now());
                let mut conn = db.lock().unwrap();
                match nester_core::scan::scan_folder(&mut conn, &root) {
                    Ok(stats) => {
                        if stats.added + stats.updated + stats.removed > 0 {
                            tracing::info!(
                                "rescan {label}: {} added, {} updated, {} removed",
                                stats.added,
                                stats.updated,
                                stats.removed
                            );
                            let text = format!(
                                "{label}: +{} changed {} removed {}",
                                stats.added, stats.updated, stats.removed
                            );
                            {
                                let mut log = runtime.events.lock().unwrap();
                                log.push_back(text);
                                while log.len() > 40 {
                                    log.pop_front();
                                }
                            }
                        }
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

fn single_instance() {
    #[cfg(windows)]
    unsafe {
        use windows_sys::Win32::Foundation::{ERROR_ALREADY_EXISTS, GetLastError};
        use windows_sys::Win32::System::Threading::CreateMutexW;

        let name: Vec<u16> = "Local\\nester-singleton\0".encode_utf16().collect();
        let _mutex = CreateMutexW(std::ptr::null(), 0, name.as_ptr());
        if GetLastError() == ERROR_ALREADY_EXISTS {
            std::process::exit(0);
        }
    }
}

fn init_logging(data_dir: &Path) -> tracing_appender::non_blocking::WorkerGuard {
    use tracing_subscriber::Layer;
    use tracing_subscriber::layer::SubscriberExt;
    use tracing_subscriber::util::SubscriberInitExt;

    let filter = tracing_subscriber::EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| "nester=info,nester_server=info".into());
    let appender = tracing_appender::rolling::RollingFileAppender::builder()
        .rotation(tracing_appender::rolling::Rotation::DAILY)
        .filename_prefix("nester")
        .filename_suffix("log")
        .max_log_files(7)
        .build(data_dir.join("logs"))
        .expect("failed to create rolling log appender");
    let (writer, guard) = tracing_appender::non_blocking(appender);
    let file_layer = tracing_subscriber::fmt::layer()
        .with_writer(writer)
        .with_ansi(false)
        .with_filter(filter.clone());
    #[cfg(debug_assertions)]
    let subscriber = tracing_subscriber::registry()
        .with(file_layer)
        .with(tracing_subscriber::fmt::layer().with_filter(filter));
    #[cfg(not(debug_assertions))]
    let subscriber = tracing_subscriber::registry().with(file_layer);
    subscriber.init();
    guard
}

fn install_panic_hook(data_dir: &Path) {
    let path = data_dir.join("crash.log");
    let default_hook = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        let backtrace = std::backtrace::Backtrace::force_capture();
        let ts = unix_ts();
        let report = format!(
            "nester v{} crashed at {ts} (unix)\n{info}\n\n{backtrace}\n",
            env!("CARGO_PKG_VERSION")
        );
        let _ = std::fs::write(&path, report);
        default_hook(info);
    }));
}

/// A leftover crash.log means the previous run died uncleanly; file it away
/// with a timestamp and flag it so the UI can show the crash banner.
fn take_crash_note(data_dir: &Path) -> bool {
    let path = data_dir.join("crash.log");
    if !path.exists() {
        return false;
    }
    let _ = std::fs::rename(&path, data_dir.join(format!("crash-{}.log", unix_ts())));
    true
}

fn unix_ts() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
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
