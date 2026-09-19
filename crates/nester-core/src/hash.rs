use std::io::Read;
use std::path::Path;

/// BLAKE3 hex digest of a whole file, streamed so multi-GB files never sit in RAM.
pub fn hash_file(path: &Path) -> anyhow::Result<String> {
    let mut file = std::fs::File::open(path)?;
    let mut hasher = blake3::Hasher::new();
    let mut buf = vec![0u8; 1024 * 1024];
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hasher.finalize().to_hex().to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn streamed_hash_matches_one_shot() {
        let dir = crate::test_root("hash-file");
        let p = dir.join("f.txt");
        std::fs::write(&p, b"nester").unwrap();
        let expected = blake3::hash(b"nester").to_hex().to_string();
        assert_eq!(hash_file(&p).unwrap(), expected);
    }

    #[test]
    fn empty_file_hashes() {
        let dir = crate::test_root("hash-empty");
        let p = dir.join("empty");
        std::fs::write(&p, b"").unwrap();
        let expected = blake3::hash(b"").to_hex().to_string();
        assert_eq!(hash_file(&p).unwrap(), expected);
    }
}
