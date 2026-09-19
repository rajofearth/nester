use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

/// Persisted host settings. Folders added or removed through the UI land here;
/// they take effect on the next start because the server binds them up front.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Config {
    pub folders: Vec<String>,
    pub port: u16,
}

impl Config {
    pub fn load(path: &Path) -> Option<Self> {
        let raw = std::fs::read_to_string(path).ok()?;
        toml::from_str(&raw).ok()
    }

    pub fn save(&self, path: &Path) -> anyhow::Result<()> {
        std::fs::create_dir_all(path.parent().unwrap_or(Path::new(".")))?;
        std::fs::write(path, toml::to_string_pretty(self)?)?;
        Ok(())
    }

    pub fn data_path(data_dir: &Path) -> PathBuf {
        data_dir.join("config.toml")
    }
}
