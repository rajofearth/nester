use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use nester_core::Folder;
use rusqlite::Connection;

/// Shared server state. One SQLite connection per folder, each behind its own
/// mutex; SQLite handles serialized access fine for a two-client LAN app.
#[derive(Clone)]
pub struct AppState {
    pub folders: Arc<Vec<Folder>>,
    pub dbs: Arc<HashMap<String, Arc<Mutex<Connection>>>>,
    pub pairing_token: Arc<String>,
}

impl AppState {
    pub fn new(
        folders: Vec<Folder>,
        dbs: HashMap<String, Arc<Mutex<Connection>>>,
        pairing_token: String,
    ) -> Self {
        Self {
            folders: Arc::new(folders),
            dbs: Arc::new(dbs),
            pairing_token: Arc::new(pairing_token),
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
}
