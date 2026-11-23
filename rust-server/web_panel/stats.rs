use crate::lock_unpoison;
use crate::log_event;
use crate::model::now;
use crate::protocol;
use crate::read_unpoison;
use crate::web_panel::*;
use axum::Json;
use axum::extract::State;
use axum::http::HeaderMap;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::response::Response;
use std::io::Read;
use std::sync::LazyLock;
use std::sync::Mutex;
use std::sync::atomic::Ordering;
use std::time::Duration;
use std::time::Instant;

pub(crate) fn proc_kib(text: &str, field: &str) -> Option<u64> {
    text.lines()
        .find(|line| line.starts_with(field))?
        .split_whitespace()
        .nth(1)?
        .parse()
        .ok()
}

pub(crate) fn read_proc_text<'a>(path: &str, buffer: &'a mut [u8]) -> Option<&'a str> {
    let mut file = std::fs::File::open(path).ok()?;
    let len = file.read(buffer).ok()?;
    std::str::from_utf8(&buffer[..len]).ok()
}

#[derive(Clone, Copy, Default)]
pub(crate) struct ProcessMemory {
    pub(crate) rss: u64,
    pub(crate) peak: u64,
    pub(crate) anonymous: u64,
    pub(crate) file: u64,
    pub(crate) shared: u64,
    pub(crate) swap: u64,
}

pub(crate) fn process_memory(text: &str) -> ProcessMemory {
    ProcessMemory {
        rss: proc_kib(text, "VmRSS:").unwrap_or(0),
        peak: proc_kib(text, "VmHWM:").unwrap_or(0),
        anonymous: proc_kib(text, "RssAnon:").unwrap_or(0),
        file: proc_kib(text, "RssFile:").unwrap_or(0),
        shared: proc_kib(text, "RssShmem:").unwrap_or(0),
        swap: proc_kib(text, "VmSwap:").unwrap_or(0),
    }
}

#[derive(Clone, Copy, Default)]
pub(crate) struct ProcessMemoryRollup {
    pub(crate) pss: u64,
    pub(crate) pss_anon: u64,
    pub(crate) anonymous: u64,
    pub(crate) file: u64,
    pub(crate) shmem: u64,
    pub(crate) private: u64,
    pub(crate) shared: u64,
    pub(crate) swap_pss: u64,
}

pub(crate) fn process_memory_rollup(text: &str) -> ProcessMemoryRollup {
    let private_clean = proc_kib(text, "Private_Clean:").unwrap_or(0);
    let private_dirty = proc_kib(text, "Private_Dirty:").unwrap_or(0);
    let shared_clean = proc_kib(text, "Shared_Clean:").unwrap_or(0);
    let shared_dirty = proc_kib(text, "Shared_Dirty:").unwrap_or(0);
    ProcessMemoryRollup {
        pss: proc_kib(text, "Pss:").unwrap_or(0),
        pss_anon: proc_kib(text, "Pss_Anon:").unwrap_or(0),
        anonymous: proc_kib(text, "Anonymous:").unwrap_or(0),
        file: proc_kib(text, "Pss_File:").unwrap_or(0),
        shmem: proc_kib(text, "Pss_Shmem:").unwrap_or(0),
        private: private_clean.saturating_add(private_dirty),
        shared: shared_clean.saturating_add(shared_dirty),
        swap_pss: proc_kib(text, "SwapPss:").unwrap_or(0),
    }
}

#[derive(Default)]
pub(crate) struct SystemStatsCache {
    pub(crate) sampled_at: Option<Instant>,
    pub(crate) process_memory: ProcessMemory,
    pub(crate) process_rollup: ProcessMemoryRollup,
    pub(crate) total_ram: u64,
}

pub(crate) static SYSTEM_STATS_CACHE: LazyLock<Mutex<SystemStatsCache>> =
    LazyLock::new(|| Mutex::new(SystemStatsCache::default()));

pub(crate) const SYSTEM_STATS_CACHE_TTL: Duration = Duration::from_secs(60);

pub(crate) fn cached_system_stats() -> (ProcessMemory, ProcessMemoryRollup, u64) {
    let mut cache = lock_unpoison(&SYSTEM_STATS_CACHE);
    let refresh = cache
        .sampled_at
        .is_none_or(|sampled_at| sampled_at.elapsed() >= SYSTEM_STATS_CACHE_TTL);
    if refresh {
        let mut buffer = [0_u8; 4096];
        cache.process_memory = read_proc_text("/proc/self/status", &mut buffer)
            .map(process_memory)
            .unwrap_or_default();
        cache.process_rollup = read_proc_text("/proc/self/smaps_rollup", &mut buffer)
            .map(process_memory_rollup)
            .unwrap_or_default();
        cache.total_ram = read_proc_text("/proc/meminfo", &mut buffer)
            .and_then(|text| proc_kib(text, "MemTotal:"))
            .unwrap_or(0);
        cache.sampled_at = Some(Instant::now());
    }
    (cache.process_memory, cache.process_rollup, cache.total_ram)
}

pub(crate) fn format_memory_kib(kib: u64) -> String {
    let (value, unit) = if kib >= 1024 * 1024 {
        (kib as f64 / (1024.0 * 1024.0), "GB")
    } else {
        (kib as f64 / 1024.0, "MB")
    };
    let mut value = format!("{value:.2}");
    while value.ends_with('0') {
        value.pop();
    }
    if value.ends_with('.') {
        value.pop();
    }
    format!("{value} {unit}")
}

pub(crate) fn format_total_memory_kib(kib: u64) -> String {
    if kib >= 1024 * 1024 {
        let mut value = format!("{:.1}", kib as f64 / (1024.0 * 1024.0));
        if value.ends_with(".0") {
            value.truncate(value.len() - 2);
        }
        format!("{value} GB")
    } else {
        format!("{} MB", (kib + 512) / 1024)
    }
}

pub(crate) async fn stats(State(state): State<WebState>, _headers: HeaderMap) -> impl IntoResponse {
    let (process_memory, process_rollup, total_ram) = cached_system_stats();
    let io = *read_unpoison(&protocol::GLOBAL_IO_COUNTERS);

    let cpu_total = state.app.cpu_percent.load(Ordering::Relaxed);
    let cpu_cores = state.app.cpu_cores.load(Ordering::Relaxed).max(1);
    let cpu_capacity = cpu_cores.saturating_mul(100);
    let cpu_normalized = cpu_total
        .saturating_mul(100)
        .checked_div(cpu_capacity)
        .unwrap_or(0)
        .min(100);
    let log_ring_bytes = crate::lock_unpoison(&state.app.logs)
        .iter()
        .map(String::len)
        .sum::<usize>();
    let db = state.app.db.read().await;
    let (
        local_proxy_active,
        local_proxy_tcp_sessions,
        local_proxy_udp_flows,
        local_proxy_tcp_peak,
        local_proxy_udp_peak,
        local_proxy_tcp_total,
        local_proxy_udp_total,
    ) = {
        let route = state.app.proxy_route.read().await;
        let active = route.as_ref().is_some_and(|route| route.is_alive());
        let proxy_stats = route
            .as_ref()
            .map(|route| route.diagnostic_snapshot())
            .unwrap_or_default();
        (
            active,
            proxy_stats.tcp_active,
            proxy_stats.udp_active,
            proxy_stats.tcp_peak,
            proxy_stats.udp_peak,
            proxy_stats.tcp_total,
            proxy_stats.udp_total,
        )
    };
    Json(serde_json::json!({
        "ram": format!("{} / {}", format_memory_kib(process_memory.rss), format_total_memory_kib(total_ram)),
        "ram_used": format_memory_kib(process_memory.rss),
        "ram_total": format_total_memory_kib(total_ram),
        "ram_peak": format_memory_kib(process_memory.peak),
        "ram_anonymous": format_memory_kib(process_memory.anonymous),
        "ram_file": format_memory_kib(process_memory.file),
        "ram_shared": format_memory_kib(process_memory.shared),
        "ram_swap": format_memory_kib(process_memory.swap),
        "ram_pss": format_memory_kib(process_rollup.pss),
        "ram_private": format_memory_kib(process_rollup.private),
        "ram_shared_rollup": format_memory_kib(process_rollup.shared),
        "ram_anon_rollup": format_memory_kib(process_rollup.pss_anon),
        "ram_anonymous_rollup": format_memory_kib(process_rollup.anonymous),
        "ram_file_rollup": format_memory_kib(process_rollup.file),
        "ram_shmem_rollup": format_memory_kib(process_rollup.shmem),
        "ram_swap_pss": format_memory_kib(process_rollup.swap_pss),
        "ram_used_kib": process_memory.rss,
        "ram_peak_kib": process_memory.peak,
        "ram_anonymous_kib": process_memory.anonymous,
        "ram_file_kib": process_memory.file,
        "ram_shared_kib": process_memory.shared,
        "ram_swap_kib": process_memory.swap,
        "ram_pss_kib": process_rollup.pss,
        "ram_private_kib": process_rollup.private,
        "ram_shared_rollup_kib": process_rollup.shared,
        "ram_anon_rollup_kib": process_rollup.pss_anon,
        "ram_anonymous_rollup_kib": process_rollup.anonymous,
        "ram_file_rollup_kib": process_rollup.file,
        "ram_shmem_rollup_kib": process_rollup.shmem,
        "ram_swap_pss_kib": process_rollup.swap_pss,
        "cpu_total": cpu_total,
        "cpu_capacity": cpu_capacity,
        "cpu_normalized": cpu_normalized,
        "status": "Active",
        "peer_port": state.app.listen.port(),
        "public_ip": state.app.public_ip.read().await.clone(),
        "uptime": now().saturating_sub(state.app.started),
        "active": state.app.sessions.len(),
        "total": state.app.total_connections.load(Ordering::Relaxed),
        "up": state.app.bytes_from_client.load(Ordering::Relaxed),
        "down": state.app.bytes_to_client.load(Ordering::Relaxed),
        "udp_rx_packets": io.udp_rx_packets,
        "udp_rx_bytes": io.udp_rx_bytes,
        "udp_rx_errors": io.udp_rx_errors,
        "udp_tx_packets": io.udp_tx_packets,
        "udp_tx_bytes": io.udp_tx_bytes,
        "udp_tx_errors": io.udp_tx_errors,
        "passwords": db.passwords.len(),
        "transport": "RTP/SRTP",
        "tunnel": "userspace-tun",
        "allocator": crate::allocator_name(),
        "local_proxy_enabled": !db.local_proxy.active_profile_id.is_empty(),
        "local_proxy_port": db.local_proxy.active_profile().map(|p| p.port).unwrap_or(0),
        "local_proxy_active": local_proxy_active,
        "local_proxy_port_listening": state.app.proxy_port_listening.load(Ordering::Acquire),
        "local_proxy_health_error": crate::read_unpoison(&state.app.proxy_health_error).clone(),
        "local_proxy_tcp_sessions": local_proxy_tcp_sessions,
        "local_proxy_udp_flows": local_proxy_udp_flows,
        "local_proxy_tcp_peak": local_proxy_tcp_peak,
        "local_proxy_udp_peak": local_proxy_udp_peak,
        "local_proxy_tcp_total": local_proxy_tcp_total,
        "local_proxy_udp_total": local_proxy_udp_total,
        "hot_sessions": protocol::ACTIVE_SESSIONS_GAUGE.load(Ordering::Relaxed),
        "hot_session_capacity": protocol::HOT_SESSION_CAPACITY_GAUGE.load(Ordering::Relaxed),
        "public_session_capacity": state.app.sessions.capacity(),
        "mem_engine_epochs": protocol::epoch_snapshot_len(),
        "mem_password_epochs": state.app.password_epochs.len(),
        "mem_password_epoch_capacity": state.app.password_epochs.capacity(),
        "mem_derived_keys": state.app.derived_keys.len(),
        "mem_web_sessions": state.app.web_sessions.len(),
        "mem_web_session_capacity": MAX_WEB_SESSIONS,
        "mem_login_limits": state.app.login_limits.len(),
        "mem_login_limit_capacity": MAX_LOGIN_LIMITS,
        "mem_log_ring": crate::lock_unpoison(&state.app.logs).len(),
        "mem_log_bytes": log_ring_bytes,
        "mem_dpi_ring": protocol::dpi_ring_len(),
        "mem_stream_repairs": protocol::STREAM_REPAIRS_GAUGE.load(Ordering::Relaxed),
        "mem_stream_inventory": protocol::STREAM_INVENTORY_GAUGE.load(Ordering::Relaxed),
        "memory_trim_count": state.app.memory_trim_count.load(Ordering::Relaxed),
        "memory_trim_last_unix": state.app.memory_trim_last_unix.load(Ordering::Relaxed)
    }))
}

pub(crate) async fn memory_trim(State(state): State<WebState>) -> Response {
    match crate::trim_memory(&state.app).await {
        Ok(allocator_collected) => Json(serde_json::json!({
            "compacted": true,
            "allocator_collected": allocator_collected,
            "allocator_collection_scope": "not-supported-by-system-allocator",
            "trim_count": state.app.memory_trim_count.load(Ordering::Relaxed),
            "trimmed_at": state.app.memory_trim_last_unix.load(Ordering::Relaxed),
        }))
        .into_response(),
        Err(error) => {
            log_event(
                &state.app,
                "WARNING",
                "MEMORY",
                &format!("Memory trim skipped: {error:#}"),
            );
            (
                StatusCode::SERVICE_UNAVAILABLE,
                "memory trim is temporarily unavailable",
            )
                .into_response()
        }
    }
}
