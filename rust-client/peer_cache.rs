//! Кэш последнего удачного IP пира.
//!
//! Bootstrap-плоскость резолвит `--peer` до поднятия туннеля. Когда DNS-ладдер
//! не дал ответа, единственной страховкой остаётся IP, на котором туннель уже
//! работал: файл `{"host", "ip", "unix_ts"}` с TTL 7 дней. Кэш используется
//! только после отказа DNS и удаляется, если туннель на этом IP не дошёл до
//! первого `READY` — иначе устаревший адрес стоил бы целой неудачной сессии.
//!
//! Путь передаёт Kotlin флагом `--peer-cache`; пустой путь выключает кэш.
//! Все ошибки файла логируются и не мешают старту.

use serde::{Deserialize, Serialize};
use std::{
    fs,
    io::Write,
    net::{IpAddr, Ipv4Addr},
    path::{Path, PathBuf},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, SystemTime, UNIX_EPOCH},
};

/// Срок жизни записи: 7 дней.
const PEER_CACHE_TTL: Duration = Duration::from_secs(7 * 24 * 60 * 60);

#[derive(Debug, Serialize, Deserialize)]
struct PeerCacheEntry {
    host: String,
    ip: String,
    unix_ts: u64,
}

/// Файл последнего IP пира; `path = None` — кэш выключен.
pub struct PeerCache {
    path: Option<PathBuf>,
}

impl PeerCache {
    pub fn new(raw: &str) -> Self {
        let raw = raw.trim();
        Self {
            path: (!raw.is_empty()).then(|| PathBuf::from(raw)),
        }
    }

    #[cfg(test)]
    fn enabled(&self) -> bool {
        self.path.is_some()
    }

    /// Свежий (не старше 7 дней) IPv4 для этого хоста. Отсутствующий, битый,
    /// просроченный файл и чужой host игнорируются.
    pub fn read(&self, host: &str) -> Option<IpAddr> {
        let path = self.path.as_ref()?;
        let raw = fs::read_to_string(path).ok()?;
        let entry: PeerCacheEntry = serde_json::from_str(&raw).ok()?;
        if !entry.host.trim().eq_ignore_ascii_case(host.trim()) {
            return None;
        }
        let age = unix_now().saturating_sub(entry.unix_ts);
        if age > PEER_CACHE_TTL.as_secs() {
            return None;
        }
        entry.ip.trim().parse::<Ipv4Addr>().ok().map(IpAddr::V4)
    }

    /// Атомарная перезапись: tmp-файл в том же каталоге, затем `rename`.
    pub fn write(&self, host: &str, ip: IpAddr) {
        let Some(path) = self.path.as_ref() else {
            return;
        };
        let entry = PeerCacheEntry {
            host: host.trim().to_ascii_lowercase(),
            ip: ip.to_string(),
            unix_ts: unix_now(),
        };
        let encoded = match serde_json::to_vec(&entry) {
            Ok(encoded) => encoded,
            Err(error) => {
                crate::log_error!("[DNS] Не удалось сериализовать кэш пира: {error}");
                return;
            }
        };
        if let Some(parent) = path.parent()
            && !parent.as_os_str().is_empty()
            && let Err(error) = fs::create_dir_all(parent)
        {
            crate::log_error!("[DNS] Не удалось создать каталог кэша пира: {error}");
            return;
        }
        let tmp = tmp_path(path);
        let write_result = fs::File::create(&tmp).and_then(|mut file| file.write_all(&encoded));
        if let Err(error) = write_result {
            crate::log_error!(
                "[DNS] Не удалось записать кэш пира {}: {error}",
                tmp.display()
            );
            let _ = fs::remove_file(&tmp);
            return;
        }
        if let Err(error) = fs::rename(&tmp, path) {
            crate::log_error!("[DNS] Не удалось переименовать кэш пира: {error}");
            let _ = fs::remove_file(&tmp);
            return;
        }
        crate::log_error!("[DNS] Последний IP пира сохранён: {host} -> {ip}");
    }

    /// Удаляет файл кэша; `true` — файл действительно был удалён.
    pub fn remove(&self) -> bool {
        let Some(path) = self.path.as_ref() else {
            return false;
        };
        match fs::remove_file(path) {
            Ok(()) => true,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => false,
            Err(error) => {
                crate::log_error!(
                    "[DNS] Не удалось удалить кэш пира {}: {error}",
                    path.display()
                );
                false
            }
        }
    }
}

fn tmp_path(path: &Path) -> PathBuf {
    let name = path
        .file_name()
        .map(|name| name.to_string_lossy().into_owned())
        .unwrap_or_else(|| "peer_cache.json".to_owned());
    path.with_file_name(format!("{name}.{}.tmp", std::process::id()))
}

fn unix_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|elapsed| elapsed.as_secs())
        .unwrap_or(0)
}

/// Удаляет кэшированный IP, если туннель на нём не дошёл до первого `READY`,
/// и пишет свежий IP только после `READY`.
///
/// `READY` приходит из воркера (`Events::ready`), а не из `TUNCONF`: одного
/// конфига TUN недостаточно, чтобы считать адрес рабочим. Запись только после
/// `READY` делает SIGKILL безопасным: до первого воркера файл не меняется,
/// поэтому непроверенный IP не переживёт убийство процесса.
pub struct PeerCacheGuard {
    cache: PeerCache,
    used_cached: bool,
    ready: Arc<AtomicBool>,
    key: Option<(String, IpAddr)>,
}

impl PeerCacheGuard {
    pub fn new(
        cache: PeerCache,
        used_cached: bool,
        ready: Arc<AtomicBool>,
        key: Option<(String, IpAddr)>,
    ) -> Self {
        Self {
            cache,
            used_cached,
            ready,
            key,
        }
    }

    /// Идемпотентен: вызывается явно в конце `run()` и из `Drop`.
    pub fn finish(&self) {
        let Some((host, ip)) = self.key.as_ref() else {
            return;
        };
        if self.ready.load(Ordering::Acquire) {
            // Проверенный READY адрес: пишем или обновляем TTL.
            self.cache.write(host, *ip);
            return;
        }
        if self.used_cached && self.cache.remove() {
            crate::log_error!("[DNS] Кэш последнего IP пира удалён: туннель не дошёл до READY");
        }
        // Свежий IP без READY не пишем и не трогаем прежний проверенный файл:
        // именно это делает SIGKILL безопасным.
    }
}

impl Drop for PeerCacheGuard {
    fn drop(&mut self) {
        self.finish();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicUsize;

    const PEER_IP: Ipv4Addr = Ipv4Addr::new(203, 0, 113, 5);

    fn temp_cache(name: &str) -> (PeerCache, PathBuf) {
        static COUNTER: AtomicUsize = AtomicUsize::new(0);
        let unique = format!(
            "csqtt-peer-cache-{}-{}-{name}.json",
            std::process::id(),
            COUNTER.fetch_add(1, Ordering::SeqCst)
        );
        let path = std::env::temp_dir().join(unique);
        let _ = fs::remove_file(&path);
        (PeerCache::new(path.to_string_lossy().as_ref()), path)
    }

    #[test]
    fn writes_and_reads_the_recorded_ip() {
        let (cache, path) = temp_cache("round-trip");
        assert_eq!(cache.read("peer.example"), None);
        cache.write("peer.example", IpAddr::V4(PEER_IP));
        assert_eq!(cache.read("peer.example"), Some(IpAddr::V4(PEER_IP)));
        let raw = fs::read_to_string(&path).unwrap();
        let value: serde_json::Value = serde_json::from_str(&raw).unwrap();
        assert_eq!(value["host"], "peer.example");
        assert_eq!(value["ip"], PEER_IP.to_string());
        assert!(value["unix_ts"].as_u64().is_some());
        let _ = fs::remove_file(&path);
    }

    #[test]
    fn host_comparison_is_case_and_space_insensitive() {
        let (cache, path) = temp_cache("host-case");
        cache.write("  Peer.Example ", IpAddr::V4(PEER_IP));
        assert_eq!(cache.read("PEER.EXAMPLE"), Some(IpAddr::V4(PEER_IP)));
        let _ = fs::remove_file(&path);
    }

    #[test]
    fn foreign_host_is_ignored() {
        let (cache, path) = temp_cache("foreign-host");
        cache.write("peer.example", IpAddr::V4(PEER_IP));
        assert_eq!(cache.read("other.example"), None);
        let _ = fs::remove_file(&path);
    }

    #[test]
    fn stale_entry_older_than_seven_days_is_ignored() {
        let (cache, path) = temp_cache("stale");
        let expired = serde_json::json!({
            "host": "peer.example",
            "ip": PEER_IP.to_string(),
            "unix_ts": unix_now() - PEER_CACHE_TTL.as_secs() - 1,
        });
        fs::write(&path, expired.to_string()).unwrap();
        assert_eq!(cache.read("peer.example"), None);

        let fresh = serde_json::json!({
            "host": "peer.example",
            "ip": PEER_IP.to_string(),
            "unix_ts": unix_now() - PEER_CACHE_TTL.as_secs() + 60,
        });
        fs::write(&path, fresh.to_string()).unwrap();
        assert_eq!(cache.read("peer.example"), Some(IpAddr::V4(PEER_IP)));
        let _ = fs::remove_file(&path);
    }

    #[test]
    fn future_timestamp_is_treated_as_fresh() {
        let (cache, path) = temp_cache("future");
        let future = serde_json::json!({
            "host": "peer.example",
            "ip": PEER_IP.to_string(),
            "unix_ts": unix_now() + 86_400,
        });
        fs::write(&path, future.to_string()).unwrap();
        assert_eq!(cache.read("peer.example"), Some(IpAddr::V4(PEER_IP)));
        let _ = fs::remove_file(&path);
    }

    #[test]
    fn corrupt_or_incomplete_file_is_ignored() {
        let (cache, path) = temp_cache("corrupt");
        fs::write(&path, "not json at all").unwrap();
        assert_eq!(cache.read("peer.example"), None);
        fs::write(&path, r#"{"host":"peer.example"}"#).unwrap();
        assert_eq!(cache.read("peer.example"), None);
        let _ = fs::remove_file(&path);
    }

    #[test]
    fn ipv6_record_is_not_served_back() {
        let (cache, path) = temp_cache("ipv6");
        cache.write("peer.example", "2001:db8::1".parse().unwrap());
        assert_eq!(cache.read("peer.example"), None);
        let _ = fs::remove_file(&path);
    }

    #[test]
    fn empty_path_disables_the_cache() {
        let cache = PeerCache::new("   ");
        assert!(!cache.enabled());
        cache.write("peer.example", IpAddr::V4(PEER_IP));
        assert_eq!(cache.read("peer.example"), None);
        cache.remove();
    }

    #[test]
    fn write_overwrites_the_previous_address_atomically() {
        let (cache, path) = temp_cache("overwrite");
        cache.write("peer.example", IpAddr::V4(PEER_IP));
        let replacement: Ipv4Addr = Ipv4Addr::new(198, 51, 100, 9);
        cache.write("peer.example", IpAddr::V4(replacement));
        assert_eq!(cache.read("peer.example"), Some(IpAddr::V4(replacement)));
        assert!(!tmp_path(&path).exists(), "tmp-файл не должен оставаться");
        let _ = fs::remove_file(&path);
    }

    #[test]
    fn remove_ignores_a_missing_file() {
        let (cache, path) = temp_cache("missing");
        cache.remove();
        assert!(!path.exists());
        cache.write("peer.example", IpAddr::V4(PEER_IP));
        cache.remove();
        assert!(!path.exists());
    }

    #[test]
    fn guard_writes_only_after_ready_and_removes_failed_cached_ip() {
        let key = || Some(("peer.example".to_owned(), IpAddr::V4(PEER_IP)));

        let (cache, path) = temp_cache("guard-cached");
        cache.write("peer.example", IpAddr::V4(PEER_IP));
        PeerCacheGuard::new(cache, true, Arc::new(AtomicBool::new(false)), key()).finish();
        assert!(!path.exists(), "кэш без READY удаляется");

        let (cache, path) = temp_cache("guard-ready");
        cache.write("peer.example", IpAddr::V4(PEER_IP));
        PeerCacheGuard::new(cache, true, Arc::new(AtomicBool::new(true)), key()).finish();
        assert!(path.exists(), "READY сохраняет кэш");

        let old = IpAddr::V4(Ipv4Addr::new(203, 0, 113, 9));
        let (cache, path) = temp_cache("guard-fresh-no-ready");
        cache.write("peer.example", old);
        PeerCacheGuard::new(cache, false, Arc::new(AtomicBool::new(false)), key()).finish();
        let cache = PeerCache::new(path.to_string_lossy().as_ref());
        assert_eq!(
            cache.read("peer.example"),
            Some(old),
            "свежий IP без READY не перезаписывает проверенный файл"
        );
        let _ = fs::remove_file(&path);

        let (cache, path) = temp_cache("guard-fresh-ready");
        PeerCacheGuard::new(cache, false, Arc::new(AtomicBool::new(true)), key()).finish();
        let cache = PeerCache::new(path.to_string_lossy().as_ref());
        assert_eq!(
            cache.read("peer.example"),
            Some(IpAddr::V4(PEER_IP)),
            "свежий IP пишется после READY"
        );
        let _ = fs::remove_file(&path);
    }

    #[test]
    fn guard_drop_matches_explicit_finish() {
        let (cache, path) = temp_cache("guard-drop");
        cache.write("peer.example", IpAddr::V4(PEER_IP));
        {
            let _guard = PeerCacheGuard::new(
                cache,
                true,
                Arc::new(AtomicBool::new(false)),
                Some(("peer.example".to_owned(), IpAddr::V4(PEER_IP))),
            );
        }
        assert!(!path.exists());
    }

    #[test]
    fn sigkill_before_ready_keeps_previous_verified_entry() {
        let old = IpAddr::V4(Ipv4Addr::new(203, 0, 113, 9));
        let (cache, path) = temp_cache("sigkill");
        cache.write("peer.example", old);
        let guard = PeerCacheGuard::new(
            cache,
            false,
            Arc::new(AtomicBool::new(false)),
            Some(("peer.example".to_owned(), IpAddr::V4(PEER_IP))),
        );
        // SIGKILL не вызывает Drop: имитируем отсутствие finish().
        std::mem::forget(guard);
        let cache = PeerCache::new(path.to_string_lossy().as_ref());
        assert_eq!(
            cache.read("peer.example"),
            Some(old),
            "непроверенный IP не пережил SIGKILL"
        );
        let _ = fs::remove_file(&path);
    }
}
