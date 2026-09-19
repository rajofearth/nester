use std::sync::mpsc::Sender;
use std::time::Duration;

use notify_debouncer_full::{DebounceEventResult, Debouncer, FileIdMap, new_debouncer};

/// Watches a folder recursively and signals after activity quiets down. The
/// watcher only triggers; the scan remains the source of truth (ADR 0005).
pub struct FolderWatcher {
    /// Held only to keep the watcher alive. Drop it and watching stops.
    #[allow(dead_code)]
    debouncer: Debouncer<notify::RecommendedWatcher, FileIdMap>,
}

impl FolderWatcher {
    pub fn start(
        root: &std::path::Path,
        debounce: Duration,
        signal: Sender<()>,
    ) -> anyhow::Result<Self> {
        let tx = signal.clone();
        let mut debouncer = new_debouncer(debounce, None, move |_events: DebounceEventResult| {
            let _ = tx.send(());
        })?;
        debouncer.watch(root, notify::RecursiveMode::Recursive)?;
        Ok(FolderWatcher { debouncer })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc;

    #[test]
    fn writes_trigger_signal() {
        let root = crate::test_root("watch-basic");
        let (tx, rx) = mpsc::channel();
        let watcher = FolderWatcher::start(&root, Duration::from_millis(150), tx).unwrap();
        let _guard = watcher; // keep alive

        std::fs::write(root.join("new.txt"), b"x").unwrap();

        let mut got = false;
        for _ in 0..100 {
            if rx.recv_timeout(Duration::from_millis(100)).is_ok() {
                got = true;
                break;
            }
        }
        assert!(got, "watcher did not signal within 10s");
    }
}
