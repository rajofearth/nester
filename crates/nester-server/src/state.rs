use std::collections::{HashMap, VecDeque};
use std::net::IpAddr;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use nester_core::Folder;
use rusqlite::Connection;

/// Shared server state. One SQLite connection per folder, each behind its own
/// mutex; SQLite handles serialized access fine for a two-client LAN app.
#[derive(Clone)]
pub struct AppState {
    pub folders: Arc<Vec<Folder>>,
    pub dbs: Arc<HashMap<String, Arc<Mutex<Connection>>>>,
    pub pairing_token: Arc<String>,
    /// Folder id -> transfer-active-until instant. Feeds per-folder "syncing".
    pub live: Arc<Mutex<HashMap<String, Instant>>>,
    /// Device last-seen registry, shared with the host UI.
    pub devices: Arc<Mutex<HashMap<IpAddr, Instant>>>,
    /// Bounded activity log, shared with the host UI. Newest last.
    pub events: Arc<Mutex<VecDeque<String>>>,
}

impl AppState {
    pub fn new(
        folders: Vec<Folder>,
        dbs: HashMap<String, Arc<Mutex<Connection>>>,
        pairing_token: String,
        devices: Arc<Mutex<HashMap<IpAddr, Instant>>>,
        events: Arc<Mutex<VecDeque<String>>>,
    ) -> Self {
        Self {
            folders: Arc::new(folders),
            dbs: Arc::new(dbs),
            pairing_token: Arc::new(pairing_token),
            live: Arc::new(Mutex::new(HashMap::new())),
            devices,
            events,
        }
    }

    pub fn db(&self, folder_id: &str) -> Option<Arc<Mutex<Connection>>> {
        self.dbs.get(folder_id).cloned()
    }

    pub fn root_of(&self, folder_id: &str) -> Option<PathBuf> {
        self.folders
            .iter()
            .find(|f| f.id == folder_id)
            .map(|f| f.root.clone())
    }

    pub fn folder_label(&self, folder_id: &str) -> Option<String> {
        self.folders
            .iter()
            .find(|f| f.id == folder_id)
            .map(|f| f.label.clone())
    }

    pub fn touch_device(&self, ip: IpAddr) {
        self.devices.lock().unwrap().insert(ip, Instant::now());
    }

    pub fn note_event(&self, text: String) {
        let mut log = self.events.lock().unwrap();
        log.push_back(text);
        while log.len() > 40 {
            log.pop_front();
        }
    }

    pub fn mark_syncing(&self, folder_id: &str, seconds: u64) {
        self.live.lock().unwrap().insert(
            folder_id.to_string(),
            Instant::now() + Duration::from_secs(seconds),
        );
    }
}
