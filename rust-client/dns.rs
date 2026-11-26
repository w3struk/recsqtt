//! Bootstrap-DNS клиента: ладдер `link` -> `static` -> `system`.
//!
//! Плоскость обслуживает установление соединения (`--peer`, хосты VK API, домены
//! списка маршрутизации) до того, как туннель поднят, и от сервера не зависит.
//!
//! Таблица «требование дизайна -> тест» (задача 1.5 плана):
//!
//! | Требование                                                   | Тест                                              |
//! | ------------------------------------------------------------ | ------------------------------------------------- |
//! | link-тир: только IPv4-литералы, максимум два, остальное — счётчик | `link_servers_parse_ipv4_only_and_cap_two`   |
//! | пустой link-тир пропускается мгновенно, `UPDATE_DNS|` очищает  | `link_servers_empty_is_instant`                  |
//! | положительный ответ link отменяет static                      | `waterfall_link_answer_stops_static`             |
//! | тишина link -> static стартует после бюджета тира             | `waterfall_static_starts_after_link_timeout`     |
//! | пустой link не тратит бюджет                                  | `waterfall_empty_link_skips_budget`              |
//! | полная тишина даёт ровно два прохода                          | `retry_after_silence_makes_exactly_two_passes`   |
//! | повтор игнорирует кулдауны                                    | `retry_ignores_cooldowns`                        |
//! | одиночный `NXDOMAIN` не окончательный                         | `nxdomain_soft_alone_is_not_final`               |
//! | `NXDOMAIN` подтверждается link + Яндекс                       | `nxdomain_confirmed_by_link_and_yandex`          |
//! | НСДИ не подтверждает `NXDOMAIN` никогда                       | `nxdomain_nsdi_never_confirms`                   |
//! | `NODATA` для A — окончателен, затем AAAA                      | `nodata_a_then_aaaa`                             |
//! | таймаут system-бюджета                                        | `system_budget_times_out`                        |
//! | bootstrap только IPv4, литеральный IPv6 проходит              | `ipv4_only_peer_resolution`                      |
//! | положительный кэш: `min(ttl, 900 c)` и попадание               | `cache_positive_ttl_and_hit`                     |
//! | подтверждённый `NXDOMAIN` кэшируется                          | `cache_negative_confirmed_nxdomain`              |
//! | мягкий `NXDOMAIN` не кэшируется                               | `cache_soft_nxdomain_not_cached`                 |
//! | `UPDATE_DNS` сохраняет положительный кэш                       | `cache_update_dns_preserves_positive`            |
//! | `UPDATE_DNS` отменяет in-flight проход и перезапускает ладдер  | `update_dns_replaces_link_and_cancels_inflight`  |
//! | параллельные одинаковые lookup делят один проход ладдера       | `singleflight_shares_one_ladder_pass`            |
//! | отмена ведущего не отменяет ведомого и не залипает запись      | `singleflight_leader_abort_does_not_cancel_follower` |
//! | отказ кэшируется только после исчерпания повторов              | `failure_cache_written_only_after_retry_exhaustion` |
//! | TTL отказа растёт вдвое, ответ стирает запись                  | `failure_cache_backoff_and_clear`                |
//! | кулдаун — со второго подряд отказа, счётчик свой у каждого     | `cooldown_needs_two_consecutive_failures`         |
//! | `SERVFAIL` не уводит в кулдаун с первого раза                  | `servfail_needs_two_consecutive_failures_for_cooldown` |
//! | локальная ошибка не считается отказом сервера                  | `local_failure_does_not_strike_server`           |
//! | таймаут сервера уводит его в кулдаун                          | `health_skips_timed_out_server`                  |
//! | успешный источник пробуется первым в следующем lookup         | `health_preferred_source_first`                  |
//!
//! Bootstrap — только IPv4: DNS-серверы, адрес пира и статический список. AAAA
//! запрашивается лишь после `NODATA` для A, а `lookup_a` (пир) AAAA не делает вовсе.
//!
//! Отказы живут в трёх независимых плоскостях: singleflight (`flights`) делят
//! один проход между одинаковыми запросами, `failures` помнят исчерпанный отказ
//! пары `(host, qtype)` на 2–30 с, а `HealthState.strikes` считает подряд идущие
//! отказы каждого сервера до кулдауна 15–30 с.

use anyhow::{Context, Result, anyhow, bail};
use futures::{StreamExt, stream::FuturesUnordered};
use primp::dns::{Addrs, Name, Resolve, Resolving};
use std::{
    collections::HashMap,
    future::Future,
    net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr},
    sync::{
        Arc, OnceLock, RwLock,
        atomic::{AtomicU64, Ordering},
    },
    time::{Duration, Instant},
};
use tokio::net::UdpSocket;
use tokio_util::sync::CancellationToken;

/// Порт DNS-серверов из `LinkProperties` и статического списка.
const DNS_PORT: u16 = 53;
/// Максимум адресов, принятых из `-dns-servers`/`UPDATE_DNS`.
const MAX_LINK_SERVERS: usize = 2;
/// Таймаут одного UDP-запроса к серверу (меньше бюджета тира, чтобы сервер
/// успевал уйти в кулдаун до конца тира).
const DNS_TIMEOUT: Duration = Duration::from_millis(900);
const LINK_BUDGET: Duration = Duration::from_millis(1200);
const STATIC_BUDGET: Duration = Duration::from_millis(1500);
const SYSTEM_BUDGET: Duration = Duration::from_millis(2500);
/// Общий бюджет на один QNAME: `LINK_BUDGET + STATIC_BUDGET + SYSTEM_BUDGET`.
const TOTAL_BUDGET: Duration = Duration::from_millis(5200);
/// Пауза перед единственным повтором после полной тишины.
const RETRY_DELAY: Duration = Duration::from_millis(1000);
/// Повторов после полной тишины: ровно один, двойного прохода нет.
const RETRY_ATTEMPTS: usize = 1;
/// Сколько раз `lookup` перезапускает проход после смены DNS-списка.
const MAX_RESTARTS: usize = 4;
/// Порог подряд идущих отказов сервера, после которого начинается кулдаун.
/// Одиночный таймаут сервер не карантинит.
const FAILURE_THRESHOLD: u32 = 2;
/// Кулдаун сервера/источника: база 15 с, удвоение на каждый следующий отказ,
/// потолок 30 с.
const COOLDOWN_BASE: Duration = Duration::from_secs(15);
const COOLDOWN_MAX: Duration = Duration::from_secs(30);
const POSITIVE_CACHE_MAX_TTL: Duration = Duration::from_secs(900);
const NEGATIVE_CACHE_TTL: Duration = Duration::from_secs(45);
/// Негативный кэш отказов (после исчерпания повторов): база 2 с, удвоение на
/// каждый следующий подряд отказ, потолок 30 с, разброс ±20 %.
const FAILURE_CACHE_BASE_TTL: Duration = Duration::from_secs(2);
const FAILURE_CACHE_MAX_TTL: Duration = Duration::from_secs(30);
/// Сколько ведомый участник общего резолва ждёт результата ведущего: полный
/// бюджет `lookup_qtype` (до `MAX_RESTARTS + 1` проходов с повтором) плюс запас.
const FLIGHT_WAIT: Duration = Duration::from_secs(35);
const CACHE_MAX_ENTRIES: usize = 1024;
const DNS_A_RECORD: u16 = 1;
const DNS_AAAA_RECORD: u16 = 28;

const YANDEX_DNS_SERVERS: [SocketAddr; 2] = [
    SocketAddr::new(IpAddr::V4(Ipv4Addr::new(77, 88, 8, 8)), DNS_PORT),
    SocketAddr::new(IpAddr::V4(Ipv4Addr::new(77, 88, 8, 1)), DNS_PORT),
];
const NSDI_DNS_SERVERS: [SocketAddr; 2] = [
    SocketAddr::new(IpAddr::V4(Ipv4Addr::new(195, 208, 4, 1)), DNS_PORT),
    SocketAddr::new(IpAddr::V4(Ipv4Addr::new(195, 208, 5, 1)), DNS_PORT),
];

/// Статический провайдер ладдера.
///
/// `confirms_nxdomain` — участвует ли провайдер в подтверждении `NXDOMAIN`:
/// НСДИ фильтрует по реестру и подтверждать не может, Яндекс — может.
#[derive(Clone, Copy)]
struct StaticProvider {
    name: &'static str,
    servers: &'static [SocketAddr],
    confirms_nxdomain: bool,
}

const STATIC_PROVIDERS: [StaticProvider; 2] = [
    StaticProvider {
        name: "Яндекс",
        servers: &YANDEX_DNS_SERVERS,
        confirms_nxdomain: true,
    },
    StaticProvider {
        name: "НСДИ",
        servers: &NSDI_DNS_SERVERS,
        confirms_nxdomain: false,
    },
];

/// Источник ответа в ладдере. Используется как ключ health и как метка для лога.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum SourceId {
    Link,
    Static(usize),
    System,
}

impl SourceId {
    /// Подтверждает ли источник окончательный `NXDOMAIN`.
    fn confirms_nxdomain(self, config: &ResolverConfig) -> bool {
        match self {
            Self::Link => true,
            Self::Static(index) => config
                .static_providers
                .get(index)
                .is_some_and(|provider| provider.confirms_nxdomain),
            Self::System => false,
        }
    }
}

/// Тайминги, кулдауны, кэш и статический список одного резолвера.
#[derive(Clone)]
struct ResolverConfig {
    link_budget: Duration,
    static_budget: Duration,
    system_budget: Duration,
    total_budget: Duration,
    retry_delay: Duration,
    retry_attempts: usize,
    query_timeout: Duration,
    /// Отказов подряд до кулдауна сервера/системного резолвера.
    failure_threshold: u32,
    cooldown_base: Duration,
    cooldown_max: Duration,
    positive_cache_max_ttl: Duration,
    negative_cache_ttl: Duration,
    /// Негативный кэш отказов: база и потолок TTL.
    failure_cache_base_ttl: Duration,
    failure_cache_max_ttl: Duration,
    /// Таймаут ведомого участника общего резолва.
    flight_wait: Duration,
    cache_max_entries: usize,
    /// Порт, который получают адреса из `-dns-servers`/`UPDATE_DNS` (в тестах — эфемерный).
    link_port: u16,
    static_providers: Vec<StaticProvider>,
}

impl ResolverConfig {
    fn production() -> Self {
        Self {
            link_budget: LINK_BUDGET,
            static_budget: STATIC_BUDGET,
            system_budget: SYSTEM_BUDGET,
            total_budget: TOTAL_BUDGET,
            retry_delay: RETRY_DELAY,
            retry_attempts: RETRY_ATTEMPTS,
            query_timeout: DNS_TIMEOUT,
            failure_threshold: FAILURE_THRESHOLD,
            cooldown_base: COOLDOWN_BASE,
            cooldown_max: COOLDOWN_MAX,
            positive_cache_max_ttl: POSITIVE_CACHE_MAX_TTL,
            negative_cache_ttl: NEGATIVE_CACHE_TTL,
            failure_cache_base_ttl: FAILURE_CACHE_BASE_TTL,
            failure_cache_max_ttl: FAILURE_CACHE_MAX_TTL,
            flight_wait: FLIGHT_WAIT,
            cache_max_entries: CACHE_MAX_ENTRIES,
            link_port: DNS_PORT,
            static_providers: STATIC_PROVIDERS.to_vec(),
        }
    }
}

/// Код ответа DNS (`rcode`), вырезанный из флагов.
enum Rcode {
    NoError,
    ServFail,
    NxDomain,
    Refused,
    Other(u8),
}

impl Rcode {
    fn from_flags(flags: u16) -> Self {
        match flags & 0x000f {
            0 => Self::NoError,
            2 => Self::ServFail,
            3 => Self::NxDomain,
            5 => Self::Refused,
            other => Self::Other(other as u8),
        }
    }
}

/// Разобранный ответ одного сервера.
enum QueryOutcome {
    Answer(DnsAnswer),
    NoData,
    NxDomain,
    /// Сервер ответил `SERVFAIL`/`REFUSED` — это отказ сервера, он идёт в счётчик.
    ServerFailure,
    /// Сервер не ответил за `query_timeout` — тоже отказ сервера.
    Timeout,
    /// Локальная ошибка (не собрать запрос, не открыть сокет, битый ответ).
    /// Отказом сервера не считается и счётчик не трогает.
    Failed,
}

/// Положительный ответ сервера: только записи запрошенного типа.
struct DnsAnswer {
    addresses: Vec<IpAddr>,
    ttl: Duration,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum NegativeKind {
    NxDomain,
    NoData,
}

enum CacheEntry {
    Positive {
        addresses: Vec<IpAddr>,
        expires_at: Instant,
    },
    Negative {
        kind: NegativeKind,
        expires_at: Instant,
    },
}

impl CacheEntry {
    fn expires_at(&self) -> Instant {
        match self {
            Self::Positive { expires_at, .. } | Self::Negative { expires_at, .. } => *expires_at,
        }
    }

    fn is_fresh(&self, now: Instant) -> bool {
        now < self.expires_at()
    }
}

enum CacheLookup {
    Positive(Vec<IpAddr>),
    Negative(NegativeKind),
    Miss,
}

#[derive(Default)]
struct HealthState {
    cooldown: HashMap<SocketAddr, Instant>,
    /// Подряд идущие отказы каждого сервера. Ключ — сам сервер, поэтому отказ
    /// одного сервера не влияет на счётчики остальных.
    strikes: HashMap<SocketAddr, u32>,
    system_cooldown: Option<Instant>,
    system_strikes: u32,
    preferred: Option<SourceId>,
    last_good: Option<(SourceId, SocketAddr)>,
}

/// Запись негативного кэша отказов: ладдер исчерпал повторы для `(host, qtype)`.
struct FailureEntry {
    /// Сколько раз подряд по этой паре не ответил ни один источник.
    strikes: u32,
    expires_at: Instant,
    /// Текст ошибки для восстановления `anyhow` у каждого вызывающего.
    message: String,
}

impl FailureEntry {
    fn is_fresh(&self, now: Instant) -> bool {
        now < self.expires_at
    }
}

/// Общий результат прохода ладдера: клонируемый, чтобы его получили все
/// участники singleflight, а `anyhow` каждый восстанавливает сам.
type SharedResult = Result<QtypeResult, String>;

/// Один общий проход ладдера по паре `(host, qtype)`.
struct Flight {
    sender: tokio::sync::watch::Sender<Option<SharedResult>>,
}

impl Default for Flight {
    fn default() -> Self {
        let (sender, _receiver) = tokio::sync::watch::channel(None);
        Self { sender }
    }
}

impl Flight {
    /// Ждёт результат ведущего; `None` — ведущий пропал (задача снята).
    async fn wait(&self) -> Option<SharedResult> {
        let mut receiver = self.sender.subscribe();
        loop {
            if let Some(result) = (*receiver.borrow()).clone() {
                return Some(result);
            }
            if receiver.changed().await.is_err() {
                return (*receiver.borrow()).clone();
            }
        }
    }
}

/// Убирает запись singleflight по завершении ведущего — в том числе если
/// ведущий паникует: `Drop` выполняется при раскрутке стека.
struct FlightGuard {
    inner: Arc<Inner>,
    key: (String, u16),
    flight: Arc<Flight>,
}

impl Drop for FlightGuard {
    fn drop(&mut self) {
        write_lock(&self.inner.flights, |flights| {
            let same = flights
                .get(&self.key)
                .is_some_and(|current| Arc::ptr_eq(current, &self.flight));
            if same {
                flights.remove(&self.key);
            }
        });
    }
}

/// Результат одного прохода ладдера по одному QNAME.
enum PassOutcome {
    Answer(DnsAnswer, SourceId),
    NoData,
    NxDomainFinal,
    NxDomainSoft,
    Silence,
    Cancelled,
}

/// Результат одного тира.
enum TierOutcome {
    Answer(DnsAnswer, SourceId),
    NoData,
    NxDomainFinal,
    Exhausted,
}

/// Тир ладдера. Оба статических провайдера опрашиваются параллельно внутри
/// одного `STATIC_BUDGET`; провайдерская принадлежность остаётся в `SourceId`.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Stage {
    Link,
    Static,
    System,
}

impl Stage {
    fn from_source(source: SourceId) -> Self {
        match source {
            SourceId::Link => Self::Link,
            SourceId::Static(_) => Self::Static,
            SourceId::System => Self::System,
        }
    }
}

/// Результат одного QNAME внутри `lookup`.
#[derive(Clone)]
enum QtypeResult {
    Addresses(Vec<IpAddr>),
    NoData,
}

/// Что отдать вызывающему, если по паре уже записан недавний отказ.
enum FailureHit {
    /// Просроченный положительный ответ важнее отказа.
    Addresses(Vec<IpAddr>),
    Failure(anyhow::Error),
}

/// Результат системного резолвера.
enum SystemOutcome {
    Answer(DnsAnswer),
    NoData,
    Failed,
    TimedOut,
}

fn read_lock<T, R>(lock: &RwLock<T>, f: impl FnOnce(&T) -> R) -> R {
    match lock.read() {
        Ok(guard) => f(&guard),
        Err(poisoned) => f(&poisoned.into_inner()),
    }
}

fn write_lock<T, R>(lock: &RwLock<T>, f: impl FnOnce(&mut T) -> R) -> R {
    match lock.write() {
        Ok(mut guard) => f(&mut guard),
        Err(poisoned) => f(&mut poisoned.into_inner()),
    }
}

fn qtype_label(qtype: u16) -> &'static str {
    match qtype {
        DNS_A_RECORD => "A",
        DNS_AAAA_RECORD => "AAAA",
        _ => "?",
    }
}

/// TTL записи негативного кэша отказов: база × 2 за каждый подряд идущий отказ,
/// не больше потолка, плюс разброс ±20 %.
fn failure_ttl(base: Duration, max: Duration, strikes: u32) -> Duration {
    let factor = 2_u32.saturating_pow(strikes.saturating_sub(1));
    let ttl = base.saturating_mul(factor).min(max);
    let percent = 80 + rand::random::<u32>() % 41;
    (ttl * percent) / 100
}

fn nxdomain_error(host: &str) -> anyhow::Error {
    anyhow!("DNS: {host} не существует — NXDOMAIN подтверждён вторым источником")
}

/// Резолвер: публичная обёртка над общим состоянием.
///
/// Состояние лежит в `Arc`, чтобы ведущий проход singleflight жил отдельной
/// задачей: таймаут или отмена вызывающего не должны отменять общий проход.
pub struct DnsResolver {
    inner: Arc<Inner>,
}

impl std::ops::Deref for DnsResolver {
    type Target = Inner;

    fn deref(&self) -> &Self::Target {
        &self.inner
    }
}

/// Общее состояние резолвера. `pub` только потому, что `Deref::Target` не может
/// быть приватнее самого `DnsResolver`; снаружи модуля тип не используется.
pub struct Inner {
    config: ResolverConfig,
    link: RwLock<Vec<SocketAddr>>,
    generation: AtomicU64,
    cancel: RwLock<CancellationToken>,
    cache: RwLock<HashMap<(String, u16), CacheEntry>>,
    /// Негативный кэш отказов: пишется только после исчерпания повторов.
    failures: RwLock<HashMap<(String, u16), FailureEntry>>,
    /// Общие проходы ладдера: `(host, qtype)` -> текущий ведущий.
    flights: RwLock<HashMap<(String, u16), Arc<Flight>>>,
    health: RwLock<HealthState>,
}

impl Default for DnsResolver {
    fn default() -> Self {
        Self::new()
    }
}

impl DnsResolver {
    pub fn new() -> Self {
        Self::from_config(ResolverConfig::production(), Vec::new())
    }

    fn from_config(config: ResolverConfig, link: Vec<SocketAddr>) -> Self {
        Self {
            inner: Arc::new(Inner {
                config,
                link: RwLock::new(link),
                generation: AtomicU64::new(0),
                cancel: RwLock::new(CancellationToken::new()),
                cache: RwLock::new(HashMap::new()),
                failures: RwLock::new(HashMap::new()),
                flights: RwLock::new(HashMap::new()),
                health: RwLock::new(HealthState::default()),
            }),
        }
    }

    #[cfg(test)]
    fn with_config(config: ResolverConfig, link: Vec<SocketAddr>) -> Self {
        Self::from_config(config, link)
    }

    /// Полный резолв хоста: A, при `NODATA` — AAAA (хосты VK API).
    pub async fn lookup(&self, host: &str) -> Result<Vec<IpAddr>> {
        self.resolve(host, false).await
    }

    /// Резолв только A: пир и `resolve_socket` AAAA не запрашивают.
    pub async fn lookup_a(&self, host: &str) -> Result<Vec<IpAddr>> {
        self.resolve(host, true).await
    }

    async fn resolve(&self, host: &str, a_only: bool) -> Result<Vec<IpAddr>> {
        let host = host.trim();
        if let Ok(address) = host.parse::<IpAddr>() {
            return Ok(vec![address]);
        }
        let normalized = host.to_ascii_lowercase();
        if normalized.is_empty() {
            bail!("пустое DNS-имя");
        }

        let mut skip_a_pass = false;
        match self.cache_lookup(&normalized, DNS_A_RECORD) {
            CacheLookup::Positive(addresses) => {
                crate::log_info!(
                    "[DNS] {normalized} A: кэш положительный, {} адрес(ов)",
                    addresses.len()
                );
                return Ok(addresses);
            }
            CacheLookup::Negative(NegativeKind::NxDomain) => {
                crate::log_info!("[DNS] {normalized} A: кэш отрицательный (NXDOMAIN)");
                return Err(nxdomain_error(&normalized));
            }
            CacheLookup::Negative(NegativeKind::NoData) => {
                crate::log_info!("[DNS] {normalized} A: кэш отрицательный (NODATA)");
                if a_only {
                    bail!("DNS: для {normalized} нет A-записи (NODATA)");
                }
                // NODATA для A окончателен: сразу AAAA, без нового A-прохода.
                skip_a_pass = true;
            }
            CacheLookup::Miss => {}
        }
        if !skip_a_pass {
            match self.failure_hit(&normalized, DNS_A_RECORD) {
                Some(FailureHit::Addresses(addresses)) => return Ok(addresses),
                Some(FailureHit::Failure(error)) => return Err(error),
                None => {}
            }
            if let QtypeResult::Addresses(addresses) =
                self.lookup_qtype(&normalized, DNS_A_RECORD).await?
            {
                return Ok(addresses);
            }
            if a_only {
                bail!("DNS: для {normalized} нет A-записи (NODATA)");
            }
        }

        match self.cache_lookup(&normalized, DNS_AAAA_RECORD) {
            CacheLookup::Positive(addresses) => {
                crate::log_info!(
                    "[DNS] {normalized} AAAA: кэш положительный, {} адрес(ов)",
                    addresses.len()
                );
                return Ok(addresses);
            }
            CacheLookup::Negative(NegativeKind::NxDomain) => {
                crate::log_info!("[DNS] {normalized} AAAA: кэш отрицательный (NXDOMAIN)");
                return Err(nxdomain_error(&normalized));
            }
            CacheLookup::Negative(NegativeKind::NoData) => {
                bail!("DNS: для {normalized} нет ни A, ни AAAA")
            }
            CacheLookup::Miss => {}
        }
        match self.failure_hit(&normalized, DNS_AAAA_RECORD) {
            Some(FailureHit::Addresses(addresses)) => return Ok(addresses),
            Some(FailureHit::Failure(error)) => return Err(error),
            None => {}
        }
        match self.lookup_qtype(&normalized, DNS_AAAA_RECORD).await? {
            QtypeResult::Addresses(addresses) => Ok(addresses),
            QtypeResult::NoData => bail!("DNS: для {normalized} нет ни A, ни AAAA"),
        }
    }

    /// Один общий проход ладдера на пару `(host, qtype)`.
    ///
    /// Первый вызывающий становится ведущим: проход уезжает в отдельную задачу,
    /// поэтому его не отменяют ни таймаут, ни отмена ожидающих. Остальные ждут
    /// тот же результат и применяют к ожиданию собственный таймаут.
    async fn lookup_qtype(&self, host: &str, qtype: u16) -> Result<QtypeResult> {
        let key = (host.to_owned(), qtype);
        let (flight, leader) = write_lock(&self.flights, |flights| match flights.get(&key) {
            Some(flight) => (Arc::clone(flight), false),
            None => {
                let flight = Arc::new(Flight::default());
                flights.insert(key.clone(), Arc::clone(&flight));
                (flight, true)
            }
        });
        if leader {
            let inner = Arc::clone(&self.inner);
            let guard_key = key.clone();
            let task_flight = Arc::clone(&flight);
            let task_host = key.0;
            tokio::spawn(async move {
                let _guard = FlightGuard {
                    inner: Arc::clone(&inner),
                    key: guard_key,
                    flight: Arc::clone(&task_flight),
                };
                let result = inner.run_lookup_qtype(&task_host, qtype).await;
                task_flight.sender.send_replace(Some(result));
            });
        }

        let timeout = self.config.flight_wait;
        match tokio::time::timeout(timeout, flight.wait()).await {
            Ok(Some(Ok(result))) => Ok(result),
            Ok(Some(Err(message))) => Err(anyhow::Error::msg(message)),
            Ok(None) => Err(anyhow!("DNS: общий резолв {host} прерван")),
            Err(_) => Err(anyhow!(
                "DNS: ожидание общего резолва {host} {label} превысило {} мс",
                timeout.as_millis(),
                label = qtype_label(qtype)
            )),
        }
    }
}

impl Inner {
    /// Полный проход по одному QNAME: перезапуски после смены DNS-списка,
    /// повтор после тишины и запись кэшей. Возвращает клонируемый результат.
    async fn run_lookup_qtype(&self, host: &str, qtype: u16) -> SharedResult {
        let started = Instant::now();
        for _ in 0..=MAX_RESTARTS {
            let generation = self.generation.load(Ordering::Acquire);
            let outcome = self.run_with_retry(host, qtype).await;
            if matches!(outcome, PassOutcome::Cancelled)
                || self.generation.load(Ordering::Acquire) != generation
            {
                continue;
            }
            return self
                .finish_lookup(host, qtype, outcome, started.elapsed())
                .map_err(|error| error.to_string());
        }
        Err(format!(
            "DNS: резолв {host} прерван сменой DNS-списка слишком много раз"
        ))
    }

    async fn run_with_retry(&self, host: &str, qtype: u16) -> PassOutcome {
        let mut attempt = 0usize;
        loop {
            let outcome = self.run_pass(host, qtype, attempt > 0).await;
            match outcome {
                PassOutcome::Silence if attempt < self.config.retry_attempts => {
                    attempt += 1;
                    if self.sleep_before_retry().await {
                        return PassOutcome::Cancelled;
                    }
                }
                other => return other,
            }
        }
    }

    /// Пауза перед повтором; `true` — проход отменён сменой DNS-списка.
    async fn sleep_before_retry(&self) -> bool {
        let cancel = self.current_cancel();
        let generation = self.generation.load(Ordering::Acquire);
        tokio::select! {
            biased;
            _ = cancel.cancelled() => true,
            _ = tokio::time::sleep(self.config.retry_delay) => {
                self.generation.load(Ordering::Acquire) != generation
            }
        }
    }

    fn current_cancel(&self) -> CancellationToken {
        read_lock(&self.cancel, CancellationToken::clone)
    }

    /// Один проход ладдера; отменяется сменой DNS-списка.
    async fn run_pass(&self, host: &str, qtype: u16, ignore_cooldown: bool) -> PassOutcome {
        let cancel = self.current_cancel();
        let pass = self.run_pass_inner(host, qtype, ignore_cooldown);
        tokio::pin!(pass);
        tokio::select! {
            biased;
            _ = cancel.cancelled() => PassOutcome::Cancelled,
            outcome = &mut pass => outcome,
        }
    }

    async fn run_pass_inner(&self, host: &str, qtype: u16, ignore_cooldown: bool) -> PassOutcome {
        let deadline = Instant::now() + self.config.total_budget;
        let mut nxdomain_sources: Vec<SourceId> = Vec::new();

        for stage in self.pass_order() {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                break;
            }
            let budget = self.stage_budget(stage).min(remaining);
            let outcome = match stage {
                Stage::System => {
                    self.run_system_tier(host, qtype, budget, ignore_cooldown)
                        .await
                }
                Stage::Link | Stage::Static => {
                    self.run_udp_stage(
                        stage,
                        host,
                        qtype,
                        budget,
                        ignore_cooldown,
                        &mut nxdomain_sources,
                    )
                    .await
                }
            };
            match outcome {
                TierOutcome::Answer(answer, source) => {
                    return PassOutcome::Answer(answer, source);
                }
                TierOutcome::NoData => return PassOutcome::NoData,
                TierOutcome::NxDomainFinal => return PassOutcome::NxDomainFinal,
                TierOutcome::Exhausted => {}
            }
            if self.nxdomain_confirmed(&nxdomain_sources) {
                return PassOutcome::NxDomainFinal;
            }
        }

        if self.nxdomain_confirmed(&nxdomain_sources) {
            PassOutcome::NxDomainFinal
        } else if nxdomain_sources.is_empty() {
            PassOutcome::Silence
        } else {
            PassOutcome::NxDomainSoft
        }
    }

    fn stage_budget(&self, stage: Stage) -> Duration {
        match stage {
            Stage::Link => self.config.link_budget,
            Stage::Static => self.config.static_budget,
            Stage::System => self.config.system_budget,
        }
    }

    /// Порядок тиров: предпочитаемый источник первым, затем обычный ладдер.
    ///
    /// `Static` — один тир: оба провайдера опрашиваются параллельно в его бюджете.
    fn pass_order(&self) -> Vec<Stage> {
        let mut order = Vec::with_capacity(3 + self.config.static_providers.len());
        if let Some(preferred) = read_lock(&self.health, |health| health.preferred) {
            order.push(Stage::from_source(preferred));
        }
        order.push(Stage::Link);
        order.push(Stage::Static);
        order.push(Stage::System);
        let mut unique = Vec::with_capacity(order.len());
        for stage in order {
            if !unique.contains(&stage) {
                unique.push(stage);
            }
        }
        unique
    }

    /// Серверы тира без кулдауна, успешный сервер — первым.
    fn ordered_servers(&self, source: SourceId, ignore_cooldown: bool) -> Vec<SocketAddr> {
        let mut servers = match source {
            SourceId::Link => read_lock(&self.link, |link| link.clone()),
            SourceId::Static(index) => self
                .config
                .static_providers
                .get(index)
                .map(|provider| provider.servers.to_vec())
                .unwrap_or_default(),
            SourceId::System => return Vec::new(),
        };
        if !ignore_cooldown {
            let now = Instant::now();
            let cooldown = read_lock(&self.health, |health| health.cooldown.clone());
            servers.retain(|server| cooldown.get(server).is_none_or(|until| *until <= now));
        }
        if let Some((good_source, good_server)) = read_lock(&self.health, |health| health.last_good)
            && good_source == source
            && let Some(position) = servers.iter().position(|server| *server == good_server)
        {
            let server = servers.remove(position);
            servers.insert(0, server);
        }
        servers
    }

    fn source_name(&self, source: SourceId) -> &str {
        match source {
            SourceId::Link => "link",
            SourceId::Static(index) => self
                .config
                .static_providers
                .get(index)
                .map_or("static", |provider| provider.name),
            SourceId::System => "system",
        }
    }

    /// Окончательный `NXDOMAIN` — только при двух независимых подтверждающих источниках.
    fn nxdomain_confirmed(&self, sources: &[SourceId]) -> bool {
        sources
            .iter()
            .filter(|source| source.confirms_nxdomain(&self.config))
            .count()
            >= 2
    }

    fn mark_success(&self, source: SourceId, server: SocketAddr) {
        write_lock(&self.health, |health| {
            health.cooldown.remove(&server);
            // Любой ответ сервера (Answer/NoData/NXDOMAIN) обнуляет только его
            // собственный счётчик: успех одного сервера не прощает остальные.
            health.strikes.remove(&server);
            health.preferred = Some(source);
            health.last_good = Some((source, server));
        });
    }

    /// Длительность кулдауна: база × 2 на каждый отказ сверх порога, не больше
    /// потолка.
    fn cooldown_for(&self, strikes: u32) -> Duration {
        let exponent = strikes.saturating_sub(self.config.failure_threshold);
        let factor = 2_u32.saturating_pow(exponent);
        self.config
            .cooldown_base
            .saturating_mul(factor)
            .min(self.config.cooldown_max)
    }

    fn mark_failure(&self, server: SocketAddr) {
        let now = Instant::now();
        let threshold = self.config.failure_threshold;
        let (strikes, started, cooldown) = write_lock(&self.health, |health| {
            let counter = health.strikes.entry(server).or_insert(0);
            *counter = counter.saturating_add(1);
            let strikes = *counter;
            if strikes < threshold {
                return (strikes, false, Duration::ZERO);
            }
            let cooldown = self.cooldown_for(strikes);
            let started = health
                .cooldown
                .get(&server)
                .is_none_or(|until| *until <= now);
            health.cooldown.insert(server, now + cooldown);
            (strikes, started, cooldown)
        });
        if started {
            crate::log_error!(
                "[DNS] Сервер {server} в кулдауне {} с после {strikes} подряд отказов: таймаут или SERVFAIL",
                cooldown.as_secs()
            );
        }
    }

    fn mark_system_success(&self) {
        write_lock(&self.health, |health| {
            health.system_cooldown = None;
            health.system_strikes = 0;
            health.preferred = Some(SourceId::System);
        });
    }

    fn mark_system_failure(&self) {
        let now = Instant::now();
        let threshold = self.config.failure_threshold;
        let (strikes, started, cooldown) = write_lock(&self.health, |health| {
            health.system_strikes = health.system_strikes.saturating_add(1);
            let strikes = health.system_strikes;
            if strikes < threshold {
                return (strikes, false, Duration::ZERO);
            }
            let cooldown = self.cooldown_for(strikes);
            let started = health.system_cooldown.is_none_or(|until| until <= now);
            health.system_cooldown = Some(now + cooldown);
            (strikes, started, cooldown)
        });
        if started {
            crate::log_error!(
                "[DNS] Системный резолвер в кулдауне {} с после {strikes} подряд отказов: таймаут",
                cooldown.as_secs()
            );
        }
    }

    /// Серверы тира с их источниками: link, либо все провайдеры static сразу.
    fn stage_servers(&self, stage: Stage, ignore_cooldown: bool) -> Vec<(SourceId, SocketAddr)> {
        match stage {
            Stage::Link => self
                .ordered_servers(SourceId::Link, ignore_cooldown)
                .into_iter()
                .map(|server| (SourceId::Link, server))
                .collect(),
            Stage::Static => {
                let mut servers = Vec::new();
                for index in 0..self.config.static_providers.len() {
                    let source = SourceId::Static(index);
                    servers.extend(
                        self.ordered_servers(source, ignore_cooldown)
                            .into_iter()
                            .map(|server| (source, server)),
                    );
                }
                servers
            }
            Stage::System => Vec::new(),
        }
    }

    /// Опрос всех серверов тира параллельно, до бюджета тира.
    ///
    /// `NXDOMAIN` пишется в `PassRecord` с идентификатором провайдера, поэтому
    /// подтвердить его может только Яндекс (`Static(0)`), а НСДИ — нет.
    async fn run_udp_stage(
        &self,
        stage: Stage,
        host: &str,
        qtype: u16,
        budget: Duration,
        ignore_cooldown: bool,
        nxdomain_sources: &mut Vec<SourceId>,
    ) -> TierOutcome {
        let servers = self.stage_servers(stage, ignore_cooldown);
        if servers.is_empty() {
            return TierOutcome::Exhausted;
        }
        let mut remaining = servers.len();
        let mut pending = FuturesUnordered::new();
        for (source, server) in servers {
            pending.push(async move {
                (
                    (source, server),
                    self.query_server(server, host, qtype).await,
                )
            });
        }
        let deadline = Instant::now() + budget;
        while remaining > 0 {
            let left = deadline.saturating_duration_since(Instant::now());
            if left.is_zero() {
                return TierOutcome::Exhausted;
            }
            let next = match tokio::time::timeout(left, pending.next()).await {
                Ok(Some(next)) => next,
                Ok(None) | Err(_) => return TierOutcome::Exhausted,
            };
            remaining -= 1;
            let ((source, server), outcome) = next;
            match outcome {
                QueryOutcome::Answer(answer) => {
                    self.mark_success(source, server);
                    return TierOutcome::Answer(answer, source);
                }
                QueryOutcome::NoData => {
                    self.mark_success(source, server);
                    return TierOutcome::NoData;
                }
                QueryOutcome::NxDomain => {
                    self.mark_success(source, server);
                    if !nxdomain_sources.contains(&source) {
                        nxdomain_sources.push(source);
                    }
                    if self.nxdomain_confirmed(nxdomain_sources) {
                        return TierOutcome::NxDomainFinal;
                    }
                }
                QueryOutcome::ServerFailure | QueryOutcome::Timeout => self.mark_failure(server),
                // Локальная ошибка (битый запрос/ответ, сокет) — не отказ сервера.
                QueryOutcome::Failed => {}
            }
        }
        TierOutcome::Exhausted
    }

    async fn run_system_tier(
        &self,
        host: &str,
        qtype: u16,
        budget: Duration,
        ignore_cooldown: bool,
    ) -> TierOutcome {
        let now = Instant::now();
        let cooling_down = read_lock(&self.health, |health| {
            health.system_cooldown.is_some_and(|until| until > now)
        });
        if cooling_down && !ignore_cooldown {
            return TierOutcome::Exhausted;
        }
        match system_lookup(host, qtype, budget).await {
            SystemOutcome::Answer(answer) => {
                self.mark_system_success();
                TierOutcome::Answer(answer, SourceId::System)
            }
            SystemOutcome::NoData => {
                self.mark_system_success();
                TierOutcome::NoData
            }
            SystemOutcome::TimedOut => {
                self.mark_system_failure();
                TierOutcome::Exhausted
            }
            SystemOutcome::Failed => TierOutcome::Exhausted,
        }
    }

    async fn query_server(&self, server: SocketAddr, host: &str, qtype: u16) -> QueryOutcome {
        let query_id = rand::random::<u16>();
        let query = match build_query(host, query_id, qtype) {
            Ok(query) => query,
            Err(error) => {
                crate::log_error!("[DNS] Некорректный запрос {host}: {error}");
                return QueryOutcome::Failed;
            }
        };
        match tokio::time::timeout(
            self.config.query_timeout,
            query_dns_server(server, query, query_id, qtype),
        )
        .await
        {
            Ok(outcome) => outcome,
            Err(_) => QueryOutcome::Timeout,
        }
    }

    fn finish_lookup(
        &self,
        host: &str,
        qtype: u16,
        outcome: PassOutcome,
        elapsed: Duration,
    ) -> Result<QtypeResult> {
        let label = qtype_label(qtype);
        let millis = elapsed.as_millis();
        match outcome {
            PassOutcome::Answer(answer, source) => {
                let ttl = answer.ttl.min(self.config.positive_cache_max_ttl);
                self.cache_insert(
                    (host.to_owned(), qtype),
                    CacheEntry::Positive {
                        addresses: answer.addresses.clone(),
                        expires_at: Instant::now() + ttl,
                    },
                );
                self.clear_failure(host, qtype);
                crate::log_info!(
                    "[DNS] {host} {label}: источник {}, {} адрес(ов), кэш=мимо, {millis} мс",
                    self.source_name(source),
                    answer.addresses.len()
                );
                Ok(QtypeResult::Addresses(answer.addresses))
            }
            PassOutcome::NoData => {
                self.cache_insert(
                    (host.to_owned(), qtype),
                    CacheEntry::Negative {
                        kind: NegativeKind::NoData,
                        expires_at: Instant::now() + self.config.negative_cache_ttl,
                    },
                );
                self.clear_failure(host, qtype);
                // NODATA домена списка маршрутизации — рутина (53–63 за прогон),
                // на каждый QNAME строку не пишем; итог виден вызывающему.
                Ok(QtypeResult::NoData)
            }
            PassOutcome::NxDomainFinal => {
                self.cache_insert(
                    (host.to_owned(), qtype),
                    CacheEntry::Negative {
                        kind: NegativeKind::NxDomain,
                        expires_at: Instant::now() + self.config.negative_cache_ttl,
                    },
                );
                self.clear_failure(host, qtype);
                crate::log_error!(
                    "[DNS] {host} {label}: NXDOMAIN подтверждён вторым источником, {millis} мс"
                );
                Err(nxdomain_error(host))
            }
            PassOutcome::NxDomainSoft => {
                crate::log_error!(
                    "[DNS] {host} {label}: мягкий NXDOMAIN не подтверждён, {millis} мс"
                );
                Err(anyhow!(
                    "DNS: источники вернули отрицательный ответ на {host}, но он не подтверждён вторым источником"
                ))
            }
            PassOutcome::Silence => {
                // Повторы исчерпаны — только здесь отказ попадает в негативный кэш.
                let message = format!("DNS: ни один источник не ответил для {host}");
                self.record_failure(host, qtype, &message);
                if let Some(addresses) = self.stale_positive(host, qtype) {
                    crate::log_error!(
                        "[DNS] Использован просроченный кэш для {host} {label} (все источники недоступны), {millis} мс"
                    );
                    return Ok(QtypeResult::Addresses(addresses));
                }
                crate::log_error!("[DNS] {host} {label}: ни один источник не ответил, {millis} мс");
                Err(anyhow!(message))
            }
            PassOutcome::Cancelled => bail!("DNS: резолв {host} отменён сменой DNS-списка"),
        }
    }

    /// Свежая запись негативного кэша отказов, если она есть.
    ///
    /// Просроченный положительный ответ важнее недавнего отказа: тогда
    /// возвращается он, а не ошибка.
    fn failure_hit(&self, host: &str, qtype: u16) -> Option<FailureHit> {
        let key = (host.to_owned(), qtype);
        let now = Instant::now();
        let message = read_lock(&self.failures, |failures| {
            failures
                .get(&key)
                .filter(|entry| entry.is_fresh(now))
                .map(|entry| entry.message.clone())
        })?;
        if let Some(addresses) = self.stale_positive(host, qtype) {
            crate::log_error!(
                "[DNS] Использован просроченный кэш для {host} {} (недавний отказ источников)",
                qtype_label(qtype)
            );
            return Some(FailureHit::Addresses(addresses));
        }
        crate::log_info!(
            "[DNS] {host} {}: недавний отказ источников, ладдер пропущен",
            qtype_label(qtype)
        );
        Some(FailureHit::Failure(anyhow::Error::msg(message)))
    }

    /// Запоминает отказ после исчерпания повторов: TTL растёт вдвое за каждый
    /// следующий подряд отказ, потолок `failure_cache_max_ttl`, разброс ±20 %.
    fn record_failure(&self, host: &str, qtype: u16, message: &str) {
        let key = (host.to_owned(), qtype);
        let now = Instant::now();
        let max_entries = self.config.cache_max_entries;
        let base = self.config.failure_cache_base_ttl;
        let max = self.config.failure_cache_max_ttl;
        write_lock(&self.failures, |failures| {
            if !failures.contains_key(&key) && failures.len() >= max_entries {
                failures.retain(|_, entry| entry.is_fresh(now));
                while failures.len() >= max_entries {
                    let oldest = failures
                        .iter()
                        .min_by_key(|(_, entry)| entry.expires_at)
                        .map(|(key, _)| key.clone());
                    match oldest {
                        Some(oldest) => {
                            failures.remove(&oldest);
                        }
                        None => break,
                    }
                }
            }
            let entry = failures.entry(key).or_insert_with(|| FailureEntry {
                strikes: 0,
                expires_at: now,
                message: message.to_owned(),
            });
            entry.strikes = entry.strikes.saturating_add(1);
            entry.message = message.to_owned();
            entry.expires_at = now + failure_ttl(base, max, entry.strikes);
        });
    }

    fn clear_failure(&self, host: &str, qtype: u16) {
        let key = (host.to_owned(), qtype);
        write_lock(&self.failures, |failures| {
            failures.remove(&key);
        });
    }

    fn cache_lookup(&self, host: &str, qtype: u16) -> CacheLookup {
        let key = (host.to_owned(), qtype);
        let now = Instant::now();
        read_lock(&self.cache, |cache| match cache.get(&key) {
            Some(CacheEntry::Positive {
                addresses,
                expires_at,
            }) if now < *expires_at => CacheLookup::Positive(addresses.clone()),
            Some(CacheEntry::Negative { kind, expires_at }) if now < *expires_at => {
                CacheLookup::Negative(*kind)
            }
            _ => CacheLookup::Miss,
        })
    }

    /// Просроченный положительный ответ — только как страховка при полном отказе сети.
    fn stale_positive(&self, host: &str, qtype: u16) -> Option<Vec<IpAddr>> {
        let key = (host.to_owned(), qtype);
        read_lock(&self.cache, |cache| match cache.get(&key) {
            Some(CacheEntry::Positive { addresses, .. }) => Some(addresses.clone()),
            _ => None,
        })
    }

    fn cache_insert(&self, key: (String, u16), entry: CacheEntry) {
        let max = self.config.cache_max_entries;
        write_lock(&self.cache, |cache| {
            if !cache.contains_key(&key) && cache.len() >= max {
                let now = Instant::now();
                cache.retain(|_, value| value.is_fresh(now));
                while cache.len() >= max {
                    let oldest = cache
                        .iter()
                        .min_by_key(|(_, value)| value.expires_at())
                        .map(|(key, _)| key.clone());
                    match oldest {
                        Some(oldest) => {
                            cache.remove(&oldest);
                        }
                        None => break,
                    }
                }
            }
            cache.insert(key, entry);
        });
    }

    /// Заменяет link-список, отменяет in-flight проход и сбрасывает health,
    /// отрицательный кэш и кэш отказов; положительный кэш сохраняется.
    pub fn update_link_servers(&self, csv: &str) -> (usize, usize) {
        let (servers, dropped) = parse_link_servers_with_port(csv, self.config.link_port);
        let accepted = servers.len();
        self.generation.fetch_add(1, Ordering::AcqRel);
        write_lock(&self.link, |link| {
            *link = servers;
        });
        let previous = write_lock(&self.cancel, |token| {
            std::mem::replace(token, CancellationToken::new())
        });
        previous.cancel();
        write_lock(&self.health, |health| {
            *health = HealthState::default();
        });
        write_lock(&self.cache, |cache| {
            cache.retain(|_, entry| matches!(entry, CacheEntry::Positive { .. }));
        });
        write_lock(&self.failures, |failures| failures.clear());
        (accepted, dropped)
    }
}

/// Разбор `-dns-servers`/`UPDATE_DNS`: trim, только IPv4-литералы, дедупликация,
/// максимум два адреса. Возвращает принятые адреса и число отброшенных.
pub fn parse_link_servers(csv: &str) -> (Vec<SocketAddr>, usize) {
    parse_link_servers_with_port(csv, DNS_PORT)
}

fn parse_link_servers_with_port(csv: &str, port: u16) -> (Vec<SocketAddr>, usize) {
    let mut servers: Vec<SocketAddr> = Vec::new();
    let mut dropped = 0usize;
    for entry in csv.split(',') {
        let entry = entry.trim();
        if entry.is_empty() {
            continue;
        }
        match entry.parse::<Ipv4Addr>() {
            Ok(address) => {
                let server = SocketAddr::new(IpAddr::V4(address), port);
                if servers.contains(&server) {
                    dropped += 1;
                    continue;
                }
                if servers.len() >= MAX_LINK_SERVERS {
                    dropped += 1;
                    continue;
                }
                servers.push(server);
            }
            Err(_) => dropped += 1,
        }
    }
    (servers, dropped)
}

/// Системный резолвер с бюджетом: результат фильтруется по family.
async fn system_lookup(host: &str, qtype: u16, budget: Duration) -> SystemOutcome {
    let host = host.to_owned();
    let lookup = async move {
        let resolved = tokio::net::lookup_host((host.as_str(), 0)).await?;
        Ok::<Vec<IpAddr>, std::io::Error>(family_addresses(
            resolved.map(|address| address.ip()),
            qtype,
        ))
    };
    system_answer(budget, lookup).await
}

/// Оставляет только адреса запрошенного семейства и убирает дубликаты.
fn family_addresses(addresses: impl IntoIterator<Item = IpAddr>, qtype: u16) -> Vec<IpAddr> {
    let mut kept: Vec<IpAddr> = Vec::new();
    for address in addresses {
        let family_matches = match qtype {
            DNS_A_RECORD => address.is_ipv4(),
            DNS_AAAA_RECORD => address.is_ipv6(),
            _ => false,
        };
        if family_matches && !kept.contains(&address) {
            kept.push(address);
        }
    }
    kept
}

/// Обёртка бюджета system: пустой результат — `NoData`, ошибка — `Failed`,
/// таймаут — `TimedOut` (он же ставит source в кулдаун).
async fn system_answer<F>(budget: Duration, lookup: F) -> SystemOutcome
where
    F: Future<Output = std::io::Result<Vec<IpAddr>>>,
{
    match tokio::time::timeout(budget, lookup).await {
        Ok(Ok(addresses)) if addresses.is_empty() => SystemOutcome::NoData,
        Ok(Ok(addresses)) => SystemOutcome::Answer(DnsAnswer {
            addresses,
            ttl: POSITIVE_CACHE_MAX_TTL,
        }),
        Ok(Err(_)) => SystemOutcome::Failed,
        Err(_) => SystemOutcome::TimedOut,
    }
}

async fn query_dns_server(
    server: SocketAddr,
    query: Vec<u8>,
    query_id: u16,
    qtype: u16,
) -> QueryOutcome {
    let socket = match UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).await {
        Ok(socket) => socket,
        Err(_) => return QueryOutcome::Failed,
    };
    if socket.send_to(&query, server).await.is_err() {
        return QueryOutcome::Failed;
    }
    let mut response = [0_u8; 1500];
    loop {
        let (length, source) = match socket.recv_from(&mut response).await {
            Ok(received) => received,
            Err(_) => return QueryOutcome::Failed,
        };
        if source != server || length < 2 || response[0..2] != query_id.to_be_bytes() {
            continue;
        }
        return match parse_response(&response[..length], qtype) {
            Ok(outcome) => outcome,
            Err(error) => {
                crate::log_error!("[DNS] Битый ответ от {server}: {error}");
                QueryOutcome::Failed
            }
        };
    }
}

fn build_query(host: &str, query_id: u16, record_type: u16) -> Result<Vec<u8>> {
    let host = host.trim_end_matches('.');
    if host.is_empty() || host.len() > 253 {
        bail!("некорректное DNS-имя {host:?}");
    }

    let mut query = Vec::with_capacity(host.len() + 18);
    query.extend_from_slice(&query_id.to_be_bytes());
    query.extend_from_slice(&0x0100_u16.to_be_bytes());
    query.extend_from_slice(&1_u16.to_be_bytes());
    query.extend_from_slice(&0_u16.to_be_bytes());
    query.extend_from_slice(&0_u16.to_be_bytes());
    query.extend_from_slice(&0_u16.to_be_bytes());
    for label in host.split('.') {
        if label.is_empty() || label.len() > 63 || !label.is_ascii() {
            bail!("некорректная DNS-метка {label:?}");
        }
        query.push(label.len() as u8);
        query.extend_from_slice(label.as_bytes());
    }
    query.push(0);
    query.extend_from_slice(&record_type.to_be_bytes());
    query.extend_from_slice(&1_u16.to_be_bytes());
    Ok(query)
}

fn parse_response(response: &[u8], qtype: u16) -> Result<QueryOutcome> {
    if response.len() < 12 {
        bail!("слишком короткий DNS-ответ");
    }
    let flags = read_u16(response, 2)?;
    if flags & 0x8000 == 0 {
        bail!("DNS-ответ без флага response");
    }
    if flags & 0x0200 != 0 {
        bail!("DNS-ответ требует TCP-повторения");
    }
    match Rcode::from_flags(flags) {
        Rcode::NoError => {}
        Rcode::NxDomain => return Ok(QueryOutcome::NxDomain),
        Rcode::ServFail | Rcode::Refused => return Ok(QueryOutcome::ServerFailure),
        Rcode::Other(code) => {
            crate::log_error!("[DNS] Неизвестный rcode {code} в ответе DNS-сервера");
            return Ok(QueryOutcome::ServerFailure);
        }
    }

    let questions = read_u16(response, 4)? as usize;
    let answers = read_u16(response, 6)? as usize;
    let mut offset = 12;
    for _ in 0..questions {
        offset = skip_name(response, offset)?;
        offset = offset
            .checked_add(4)
            .filter(|value| *value <= response.len())
            .context("повреждён DNS-вопрос")?;
    }

    let mut addresses: Vec<IpAddr> = Vec::new();
    let mut ttl: Option<u32> = None;
    for _ in 0..answers {
        offset = skip_name(response, offset)?;
        let record_type = read_u16(response, offset)?;
        let record_class = read_u16(response, offset + 2)?;
        let record_ttl = read_u32(response, offset + 4)?;
        let data_length = read_u16(response, offset + 8)? as usize;
        let data_offset = offset
            .checked_add(10)
            .filter(|value| *value <= response.len())
            .context("повреждён DNS-ответ")?;
        let next_offset = data_offset
            .checked_add(data_length)
            .filter(|value| *value <= response.len())
            .context("повреждён DNS resource record")?;
        if record_class == 1 && record_type == qtype {
            let address = match record_type {
                DNS_A_RECORD if data_length == 4 => Some(IpAddr::V4(Ipv4Addr::new(
                    response[data_offset],
                    response[data_offset + 1],
                    response[data_offset + 2],
                    response[data_offset + 3],
                ))),
                DNS_AAAA_RECORD if data_length == 16 => {
                    let mut octets = [0_u8; 16];
                    octets.copy_from_slice(&response[data_offset..next_offset]);
                    Some(IpAddr::V6(Ipv6Addr::from(octets)))
                }
                _ => None,
            };
            if let Some(address) = address {
                if !addresses.contains(&address) {
                    addresses.push(address);
                }
                ttl = Some(ttl.map_or(record_ttl, |current| current.min(record_ttl)));
            }
        }
        offset = next_offset;
    }
    if addresses.is_empty() {
        return Ok(QueryOutcome::NoData);
    }
    Ok(QueryOutcome::Answer(DnsAnswer {
        addresses,
        ttl: Duration::from_secs(u64::from(ttl.unwrap_or(0))),
    }))
}

fn skip_name(message: &[u8], mut offset: usize) -> Result<usize> {
    loop {
        let length = *message.get(offset).context("повреждён DNS name")?;
        if length & 0xc0 == 0xc0 {
            return offset
                .checked_add(2)
                .filter(|value| *value <= message.len())
                .context("повреждён DNS compression pointer");
        }
        if length & 0xc0 != 0 {
            bail!("некорректный DNS name");
        }
        offset = offset.checked_add(1).context("переполнение DNS name")?;
        if length == 0 {
            return Ok(offset);
        }
        offset = offset
            .checked_add(length as usize)
            .filter(|value| *value <= message.len())
            .context("обрезанный DNS name")?;
    }
}

fn read_u16(message: &[u8], offset: usize) -> Result<u16> {
    let bytes = message
        .get(offset..offset + 2)
        .context("обрезанный DNS-заголовок")?;
    Ok(u16::from_be_bytes([bytes[0], bytes[1]]))
}

fn read_u32(message: &[u8], offset: usize) -> Result<u32> {
    let bytes = message
        .get(offset..offset + 4)
        .context("обрезанный DNS resource record")?;
    Ok(u32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
}

impl Resolve for DnsResolver {
    fn resolve(&self, name: Name) -> Resolving {
        let host = name.as_str().to_owned();
        let resolver = global();
        Box::pin(async move {
            let addresses = resolver
                .lookup(&host)
                .await
                .map_err(|error| -> Box<dyn std::error::Error + Send + Sync> { error.into() })?;
            Ok(Box::new(
                addresses
                    .into_iter()
                    .map(|address| SocketAddr::new(address, 0)),
            ) as Addrs)
        })
    }
}

pub fn global() -> Arc<DnsResolver> {
    static RESOLVER: OnceLock<Arc<DnsResolver>> = OnceLock::new();
    RESOLVER
        .get_or_init(|| Arc::new(DnsResolver::new()))
        .clone()
}

pub async fn resolve_socket(address: &str) -> Result<SocketAddr> {
    resolve_socket_with(&global(), address).await
}

/// Разбирает `host:port` (включая `[v6]:port`) на имя хоста и порт без DNS.
pub fn split_host_port(address: &str) -> Result<(String, u16)> {
    let parsed = url::Url::parse(&format!("udp://{address}"))
        .with_context(|| format!("разбор адреса {address:?}"))?;
    let host = parsed
        .host_str()
        .context("адрес не содержит имя хоста")?
        .to_owned();
    let port = parsed.port().context("адрес не содержит порт")?;
    Ok((host, port))
}

/// Bootstrap-резолв адреса: литеральный `SocketAddr` проходит без DNS, hostname
/// разрешается только по A (IPv4), поэтому IPv6 не возвращается.
async fn resolve_socket_with(resolver: &DnsResolver, address: &str) -> Result<SocketAddr> {
    if let Ok(address) = address.parse::<SocketAddr>() {
        return Ok(address);
    }
    let (host, port) = split_host_port(address)?;
    let ip = resolver
        .lookup_a(&host)
        .await?
        .into_iter()
        .next()
        .context("пустой DNS-ответ")?;
    Ok(SocketAddr::new(ip, port))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicUsize;

    const TEST_IP: Ipv4Addr = Ipv4Addr::new(203, 0, 113, 7);
    const OTHER_IP: Ipv4Addr = Ipv4Addr::new(198, 51, 100, 9);
    const TEST_V6: Ipv6Addr = Ipv6Addr::new(0x2001, 0xdb8, 0, 0, 0, 0, 0, 1);

    #[derive(Clone)]
    enum Behavior {
        Answer {
            v4: Vec<Ipv4Addr>,
            v6: Vec<Ipv6Addr>,
            ttl: u32,
        },
        NxDomain,
        ServFail,
        Silent,
    }

    struct FakeDns {
        address: SocketAddr,
        a_queries: Arc<AtomicUsize>,
        aaaa_queries: Arc<AtomicUsize>,
        task: tokio::task::JoinHandle<()>,
    }

    impl Drop for FakeDns {
        fn drop(&mut self) {
            self.task.abort();
        }
    }

    impl FakeDns {
        async fn spawn(behavior: Behavior) -> Self {
            Self::spawn_delayed(behavior, Duration::ZERO).await
        }

        /// Ответ приходит не раньше `delay` — для проверки параллельности тира.
        async fn spawn_delayed(behavior: Behavior, delay: Duration) -> Self {
            let socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
            let address = socket.local_addr().unwrap();
            let a_queries = Arc::new(AtomicUsize::new(0));
            let aaaa_queries = Arc::new(AtomicUsize::new(0));
            let task = tokio::spawn(serve(
                socket,
                behavior,
                delay,
                a_queries.clone(),
                aaaa_queries.clone(),
            ));
            Self {
                address,
                a_queries,
                aaaa_queries,
                task,
            }
        }

        fn a_count(&self) -> usize {
            self.a_queries.load(Ordering::SeqCst)
        }

        fn aaaa_count(&self) -> usize {
            self.aaaa_queries.load(Ordering::SeqCst)
        }
    }

    async fn serve(
        socket: UdpSocket,
        behavior: Behavior,
        delay: Duration,
        a_queries: Arc<AtomicUsize>,
        aaaa_queries: Arc<AtomicUsize>,
    ) {
        let mut buffer = [0_u8; 1500];
        loop {
            let Ok((length, peer)) = socket.recv_from(&mut buffer).await else {
                return;
            };
            let request = &buffer[..length];
            let Some((query_id, qtype, question_end)) = parse_request(request) else {
                continue;
            };
            match qtype {
                DNS_A_RECORD => {
                    a_queries.fetch_add(1, Ordering::SeqCst);
                }
                DNS_AAAA_RECORD => {
                    aaaa_queries.fetch_add(1, Ordering::SeqCst);
                }
                _ => {}
            }
            let packet = match &behavior {
                Behavior::Silent => continue,
                Behavior::NxDomain => build_response(request, query_id, 3, question_end, &[]),
                Behavior::ServFail => build_response(request, query_id, 2, question_end, &[]),
                Behavior::Answer { v4, v6, ttl } => {
                    let records: Vec<Vec<u8>> = match qtype {
                        DNS_A_RECORD => v4.iter().map(|address| a_record(*address, *ttl)).collect(),
                        DNS_AAAA_RECORD => v6
                            .iter()
                            .map(|address| aaaa_record(*address, *ttl))
                            .collect(),
                        _ => Vec::new(),
                    };
                    build_response(request, query_id, 0, question_end, &records)
                }
            };
            if !delay.is_zero() {
                tokio::time::sleep(delay).await;
            }
            let _ = socket.send_to(&packet, peer).await;
        }
    }

    fn parse_request(request: &[u8]) -> Option<(u16, u16, usize)> {
        if request.len() < 12 {
            return None;
        }
        let query_id = u16::from_be_bytes([request[0], request[1]]);
        let questions = u16::from_be_bytes([request[4], request[5]]);
        if questions != 1 {
            return None;
        }
        let end = skip_name(request, 12).ok()?.checked_add(4)?;
        if end > request.len() {
            return None;
        }
        let qtype = u16::from_be_bytes([request[end - 4], request[end - 3]]);
        Some((query_id, qtype, end))
    }

    fn build_response(
        request: &[u8],
        query_id: u16,
        rcode: u8,
        question_end: usize,
        records: &[Vec<u8>],
    ) -> Vec<u8> {
        let mut packet = Vec::with_capacity(question_end + 16 * records.len());
        packet.extend_from_slice(&query_id.to_be_bytes());
        packet.extend_from_slice(&[0x81, 0x80 | rcode]);
        packet.extend_from_slice(&1_u16.to_be_bytes());
        packet.extend_from_slice(&(records.len() as u16).to_be_bytes());
        packet.extend_from_slice(&0_u16.to_be_bytes());
        packet.extend_from_slice(&0_u16.to_be_bytes());
        packet.extend_from_slice(&request[12..question_end]);
        for record in records {
            packet.extend_from_slice(record);
        }
        packet
    }

    fn a_record(address: Ipv4Addr, ttl: u32) -> Vec<u8> {
        let mut record = vec![0xc0, 0x0c, 0x00, 0x01, 0x00, 0x01];
        record.extend_from_slice(&ttl.to_be_bytes());
        record.extend_from_slice(&4_u16.to_be_bytes());
        record.extend_from_slice(&address.octets());
        record
    }

    fn aaaa_record(address: Ipv6Addr, ttl: u32) -> Vec<u8> {
        let mut record = vec![0xc0, 0x0c, 0x00, 0x1c, 0x00, 0x01];
        record.extend_from_slice(&ttl.to_be_bytes());
        record.extend_from_slice(&16_u16.to_be_bytes());
        record.extend_from_slice(&address.octets());
        record
    }

    fn answer_packet(query_id: u16) -> Vec<u8> {
        let mut packet = Vec::new();
        packet.extend_from_slice(&query_id.to_be_bytes());
        packet.extend_from_slice(&[0x81, 0x80, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00]);
        packet.extend_from_slice(&[
            0x02, b'v', b'k', 0x02, b'r', b'u', 0x00, 0x00, 0x01, 0x00, 0x01,
        ]);
        packet.extend_from_slice(&a_record(Ipv4Addr::new(77, 88, 8, 8), 60));
        packet
    }

    fn header(query_id: u16, rcode: u8, answers: u16, truncated: bool) -> Vec<u8> {
        let mut packet = Vec::new();
        packet.extend_from_slice(&query_id.to_be_bytes());
        let flags: u16 = 0x8180 | u16::from(rcode) | if truncated { 0x0200 } else { 0 };
        packet.extend_from_slice(&flags.to_be_bytes());
        packet.extend_from_slice(&1_u16.to_be_bytes());
        packet.extend_from_slice(&answers.to_be_bytes());
        packet.extend_from_slice(&[0, 0, 0, 0]);
        packet.extend_from_slice(&[
            0x02, b'v', b'k', 0x02, b'r', b'u', 0x00, 0x00, 0x01, 0x00, 0x01,
        ]);
        packet
    }

    fn base_config() -> ResolverConfig {
        ResolverConfig {
            link_budget: Duration::from_millis(80),
            static_budget: Duration::from_millis(80),
            system_budget: Duration::from_millis(40),
            total_budget: Duration::from_millis(200),
            retry_delay: Duration::from_millis(20),
            retry_attempts: 1,
            query_timeout: Duration::from_millis(80),
            failure_threshold: FAILURE_THRESHOLD,
            cooldown_base: Duration::from_secs(5),
            cooldown_max: Duration::from_secs(10),
            positive_cache_max_ttl: Duration::from_secs(900),
            negative_cache_ttl: Duration::from_secs(45),
            failure_cache_base_ttl: FAILURE_CACHE_BASE_TTL,
            failure_cache_max_ttl: FAILURE_CACHE_MAX_TTL,
            flight_wait: Duration::from_secs(2),
            cache_max_entries: 1024,
            link_port: DNS_PORT,
            static_providers: Vec::new(),
        }
    }

    fn provider(name: &'static str, address: SocketAddr, confirms: bool) -> StaticProvider {
        StaticProvider {
            name,
            servers: Box::leak(vec![address].into_boxed_slice()),
            confirms_nxdomain: confirms,
        }
    }

    fn answering(ip: Ipv4Addr) -> Behavior {
        Behavior::Answer {
            v4: vec![ip],
            v6: Vec::new(),
            ttl: 60,
        }
    }

    #[test]
    fn parses_compressed_ipv4_answer_with_its_ttl() {
        let outcome = parse_response(&answer_packet(0x1234), DNS_A_RECORD).unwrap();
        match outcome {
            QueryOutcome::Answer(answer) => {
                assert_eq!(
                    answer.addresses,
                    vec![IpAddr::V4(Ipv4Addr::new(77, 88, 8, 8))]
                );
                assert_eq!(answer.ttl, Duration::from_secs(60));
            }
            _ => panic!("ожидался положительный ответ"),
        }
    }

    #[test]
    fn parse_response_distinguishes_rcodes() {
        assert!(matches!(
            parse_response(&header(1, 3, 0, false), DNS_A_RECORD).unwrap(),
            QueryOutcome::NxDomain
        ));
        assert!(matches!(
            parse_response(&header(1, 0, 0, false), DNS_A_RECORD).unwrap(),
            QueryOutcome::NoData
        ));
        assert!(matches!(
            parse_response(&header(1, 2, 0, false), DNS_A_RECORD).unwrap(),
            QueryOutcome::ServerFailure
        ));
        assert!(matches!(
            parse_response(&header(1, 5, 0, false), DNS_A_RECORD).unwrap(),
            QueryOutcome::ServerFailure
        ));
    }

    #[test]
    fn parse_response_rejects_truncated_answers() {
        assert!(parse_response(&header(1, 0, 0, true), DNS_A_RECORD).is_err());
    }

    #[test]
    fn parse_response_keeps_only_the_requested_type() {
        let mut packet = header(1, 0, 2, false);
        packet.extend_from_slice(&a_record(Ipv4Addr::new(203, 0, 113, 7), 30));
        packet.extend_from_slice(&aaaa_record(TEST_V6, 30));

        match parse_response(&packet, DNS_A_RECORD).unwrap() {
            QueryOutcome::Answer(answer) => {
                assert_eq!(answer.addresses, vec![IpAddr::V4(TEST_IP)]);
            }
            _ => panic!("ожидался A-ответ"),
        }
        match parse_response(&packet, DNS_AAAA_RECORD).unwrap() {
            QueryOutcome::Answer(answer) => {
                assert_eq!(answer.addresses, vec![IpAddr::V6(TEST_V6)]);
            }
            _ => panic!("ожидался AAAA-ответ"),
        }
    }

    #[test]
    fn query_rejects_invalid_dns_name() {
        assert!(build_query("", 1, DNS_A_RECORD).is_err());
        assert!(build_query("a..vk.ru", 1, DNS_A_RECORD).is_err());
    }

    #[tokio::test]
    async fn query_ignores_responses_with_a_foreign_transaction_id() {
        let server = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        let server_address = server.local_addr().unwrap();
        let query = build_query("vk.ru", 0x4321, DNS_A_RECORD).unwrap();
        let client = tokio::spawn(query_dns_server(
            server_address,
            query,
            0x4321,
            DNS_A_RECORD,
        ));
        let mut request = [0_u8; 512];
        let (_, client_address) = server.recv_from(&mut request).await.unwrap();
        server
            .send_to(&answer_packet(0x9999), client_address)
            .await
            .unwrap();
        server
            .send_to(&answer_packet(0x4321), client_address)
            .await
            .unwrap();
        match client.await.unwrap() {
            QueryOutcome::Answer(answer) => assert_eq!(
                answer.addresses,
                vec![IpAddr::V4(Ipv4Addr::new(77, 88, 8, 8))]
            ),
            _ => panic!("ожидался положительный ответ"),
        }
    }

    #[test]
    fn static_providers_have_four_ipv4_servers() {
        assert_eq!(STATIC_PROVIDERS.len(), 2);
        let total: usize = STATIC_PROVIDERS
            .iter()
            .map(|provider| provider.servers.len())
            .sum();
        assert_eq!(total, 4);
        for provider in STATIC_PROVIDERS {
            for server in provider.servers {
                assert!(server.ip().is_ipv4());
                assert_eq!(server.port(), DNS_PORT);
            }
        }
        assert!(STATIC_PROVIDERS[0].confirms_nxdomain);
        assert!(!STATIC_PROVIDERS[1].confirms_nxdomain);
    }

    #[test]
    fn tier_budgets_sum_to_total_budget() {
        let config = ResolverConfig::production();
        assert_eq!(
            config.link_budget + config.static_budget + config.system_budget,
            config.total_budget
        );
        assert_eq!(config.total_budget, Duration::from_millis(5200));
        assert_eq!(config.retry_attempts, 1);
    }

    #[test]
    fn link_servers_parse_ipv4_only_and_cap_two() {
        let (servers, dropped) =
            parse_link_servers(" 95.167.167.95, 95.167.167.96, 8.8.8.8, ::1, bad ");
        assert_eq!(servers.len(), 2);
        assert_eq!(
            servers[0],
            SocketAddr::new(IpAddr::V4(Ipv4Addr::new(95, 167, 167, 95)), DNS_PORT)
        );
        assert_eq!(
            servers[1],
            SocketAddr::new(IpAddr::V4(Ipv4Addr::new(95, 167, 167, 96)), DNS_PORT)
        );
        assert_eq!(dropped, 3);

        let (servers, dropped) = parse_link_servers("1.1.1.1, 1.1.1.1");
        assert_eq!(servers.len(), 1);
        assert_eq!(dropped, 1);

        assert_eq!(parse_link_servers(""), (Vec::new(), 0));
        assert_eq!(parse_link_servers("   "), (Vec::new(), 0));
    }

    #[tokio::test]
    async fn link_servers_empty_is_instant() {
        let static_server = FakeDns::spawn(answering(OTHER_IP)).await;
        let stale_link = FakeDns::spawn(Behavior::Silent).await;
        let config = ResolverConfig {
            link_budget: Duration::from_millis(300),
            static_providers: vec![provider("fake-static", static_server.address, true)],
            ..base_config()
        };
        let resolver = DnsResolver::with_config(config, vec![stale_link.address]);

        let generation = resolver.generation.load(Ordering::Acquire);
        assert_eq!(resolver.update_link_servers(""), (0, 0));
        assert!(resolver.link.read().unwrap().is_empty());
        assert!(resolver.generation.load(Ordering::Acquire) > generation);

        let started = Instant::now();
        let result = resolver.lookup_a("peer.example").await.unwrap();
        assert_eq!(result, vec![IpAddr::V4(OTHER_IP)]);
        assert!(started.elapsed() < Duration::from_millis(150));
        assert_eq!(stale_link.a_count(), 0);
    }

    #[tokio::test]
    async fn waterfall_link_answer_stops_static() {
        let link = FakeDns::spawn(answering(TEST_IP)).await;
        let statics = FakeDns::spawn(answering(OTHER_IP)).await;
        let config = ResolverConfig {
            static_providers: vec![provider("fake-static", statics.address, true)],
            ..base_config()
        };
        let resolver = DnsResolver::with_config(config, vec![link.address]);

        let result = resolver.lookup_a("peer.example").await.unwrap();
        assert_eq!(result, vec![IpAddr::V4(TEST_IP)]);
        assert_eq!(statics.a_count(), 0);
        assert_eq!(link.a_count(), 1);
    }

    #[tokio::test]
    async fn waterfall_static_starts_after_link_timeout() {
        let link = FakeDns::spawn(Behavior::Silent).await;
        let statics = FakeDns::spawn(answering(OTHER_IP)).await;
        let config = ResolverConfig {
            link_budget: Duration::from_millis(120),
            query_timeout: Duration::from_millis(120),
            static_budget: Duration::from_millis(80),
            total_budget: Duration::from_millis(250),
            static_providers: vec![provider("fake-static", statics.address, true)],
            ..base_config()
        };
        let resolver = DnsResolver::with_config(config, vec![link.address]);

        let started = Instant::now();
        let result = resolver.lookup_a("peer.example").await.unwrap();
        let elapsed = started.elapsed();
        assert_eq!(result, vec![IpAddr::V4(OTHER_IP)]);
        assert!(
            elapsed >= Duration::from_millis(80),
            "static должен стартовать после бюджета link, прошло {elapsed:?}"
        );
        assert_eq!(statics.a_count(), 1);
        assert_eq!(link.a_count(), 1);
    }

    #[tokio::test]
    async fn waterfall_empty_link_skips_budget() {
        let statics = FakeDns::spawn(answering(OTHER_IP)).await;
        let config = ResolverConfig {
            link_budget: Duration::from_millis(300),
            total_budget: Duration::from_millis(400),
            static_providers: vec![provider("fake-static", statics.address, true)],
            ..base_config()
        };
        let resolver = DnsResolver::with_config(config, Vec::new());

        let started = Instant::now();
        let result = resolver.lookup_a("peer.example").await.unwrap();
        let elapsed = started.elapsed();
        assert_eq!(result, vec![IpAddr::V4(OTHER_IP)]);
        assert!(
            elapsed < Duration::from_millis(150),
            "пустой link не должен тратить бюджет, прошло {elapsed:?}"
        );
        assert_eq!(statics.a_count(), 1);
    }

    #[tokio::test]
    async fn static_providers_share_one_parallel_budget() {
        // Медленный Яндекс и быстрый НСДИ: оба опрашиваются в одном тире,
        // быстрый положительный ответ выигрывает и отменяет медленный.
        let slow = FakeDns::spawn_delayed(answering(TEST_IP), Duration::from_millis(1_000)).await;
        let fast = FakeDns::spawn_delayed(answering(OTHER_IP), Duration::from_millis(20)).await;
        let config = ResolverConfig {
            link_budget: Duration::from_millis(20),
            static_budget: Duration::from_millis(250),
            system_budget: Duration::from_millis(100),
            total_budget: Duration::from_millis(500),
            query_timeout: Duration::from_millis(250),
            static_providers: vec![
                provider("fake-yandex", slow.address, true),
                provider("fake-nsdi", fast.address, false),
            ],
            ..base_config()
        };
        let resolver = DnsResolver::with_config(config, Vec::new());

        let started = Instant::now();
        let result = resolver.lookup_a("peer.example").await.unwrap();
        let elapsed = started.elapsed();

        assert_eq!(result, vec![IpAddr::V4(OTHER_IP)]);
        assert!(
            elapsed < Duration::from_millis(125),
            "static — один общий бюджет, а не два по 1,5 с; прошло {elapsed:?}"
        );
        assert_eq!(slow.a_count(), 1, "медленный провайдер опрошен параллельно");
        assert_eq!(fast.a_count(), 1);
        assert_eq!(
            resolver.health.read().unwrap().preferred,
            Some(SourceId::Static(1)),
            "победил быстрый провайдер, до system дело не дошло"
        );
        assert!(
            !resolver
                .health
                .read()
                .unwrap()
                .cooldown
                .contains_key(&slow.address),
            "отменённый запрос не считается отказом сервера"
        );
    }

    #[tokio::test]
    async fn retry_after_silence_makes_exactly_two_passes() {
        let link = FakeDns::spawn(Behavior::Silent).await;
        let statics = FakeDns::spawn(Behavior::Silent).await;
        let config = ResolverConfig {
            link_budget: Duration::from_millis(40),
            static_budget: Duration::from_millis(40),
            system_budget: Duration::from_millis(30),
            total_budget: Duration::from_millis(200),
            retry_delay: Duration::from_millis(10),
            query_timeout: Duration::from_millis(30),
            static_providers: vec![provider("fake-static", statics.address, true)],
            ..base_config()
        };
        let resolver = DnsResolver::with_config(config, vec![link.address]);

        let result = resolver.lookup_a("silent.example").await;
        assert!(result.is_err());
        assert_eq!(link.a_count(), 2);
        assert_eq!(statics.a_count(), 2);
        assert_eq!(link.aaaa_count(), 0);
    }

    #[tokio::test]
    async fn retry_ignores_cooldowns() {
        let link = FakeDns::spawn(Behavior::Silent).await;
        let statics = FakeDns::spawn(Behavior::Silent).await;
        let config = ResolverConfig {
            link_budget: Duration::from_millis(40),
            static_budget: Duration::from_millis(40),
            system_budget: Duration::from_millis(30),
            total_budget: Duration::from_millis(200),
            retry_delay: Duration::from_millis(10),
            query_timeout: Duration::from_millis(30),
            cooldown_base: Duration::from_secs(60),
            cooldown_max: Duration::from_secs(60),
            static_providers: vec![provider("fake-static", statics.address, true)],
            ..base_config()
        };
        let resolver = DnsResolver::with_config(config, vec![link.address]);

        assert!(resolver.lookup_a("silent.example").await.is_err());
        assert_eq!(link.a_count(), 2);
        assert_eq!(statics.a_count(), 2);
        let cooling = resolver.health.read().unwrap();
        assert!(
            cooling.cooldown.contains_key(&link.address),
            "сервер должен быть в кулдауне после таймаута"
        );
    }

    #[tokio::test]
    async fn nxdomain_soft_alone_is_not_final() {
        let yandex = FakeDns::spawn(Behavior::NxDomain).await;
        let nsdi = FakeDns::spawn(Behavior::Silent).await;
        let config = ResolverConfig {
            link_budget: Duration::from_millis(20),
            static_budget: Duration::from_millis(60),
            system_budget: Duration::from_millis(40),
            total_budget: Duration::from_millis(200),
            query_timeout: Duration::from_millis(40),
            static_providers: vec![
                provider("fake-yandex", yandex.address, true),
                provider("fake-nsdi", nsdi.address, false),
            ],
            ..base_config()
        };
        let resolver = DnsResolver::with_config(config, Vec::new());

        let error = resolver
            .lookup_a("soft.example")
            .await
            .expect_err("мягкий NXDOMAIN не даёт адреса");
        assert!(
            !error.to_string().contains("NXDOMAIN"),
            "мягкий отказ остаётся мягким: {error}"
        );
        assert_eq!(yandex.a_count(), 1, "мягкий NXDOMAIN не запускает повтор");
        assert!(
            resolver.cache.read().unwrap().is_empty(),
            "мягкий NXDOMAIN не кэшируется"
        );
    }

    #[tokio::test]
    async fn nxdomain_confirmed_by_link_and_yandex() {
        let link = FakeDns::spawn(Behavior::NxDomain).await;
        let yandex = FakeDns::spawn(Behavior::NxDomain).await;
        let nsdi = FakeDns::spawn(Behavior::Silent).await;
        let config = ResolverConfig {
            link_budget: Duration::from_millis(40),
            static_budget: Duration::from_millis(60),
            system_budget: Duration::from_millis(40),
            total_budget: Duration::from_millis(200),
            query_timeout: Duration::from_millis(40),
            static_providers: vec![
                provider("fake-yandex", yandex.address, true),
                provider("fake-nsdi", nsdi.address, false),
            ],
            ..base_config()
        };
        let resolver = DnsResolver::with_config(config, vec![link.address]);

        let error = resolver
            .lookup_a("localhost")
            .await
            .expect_err("подтверждённый NXDOMAIN не даёт адреса");
        assert!(error.to_string().contains("NXDOMAIN"), "{error}");
        assert_eq!(link.a_count(), 1);
        assert_eq!(yandex.a_count(), 1);
        assert!(matches!(
            resolver.cache_lookup("localhost", DNS_A_RECORD),
            CacheLookup::Negative(NegativeKind::NxDomain)
        ));
    }

    #[tokio::test]
    async fn nxdomain_nsdi_never_confirms() {
        let link = FakeDns::spawn(Behavior::NxDomain).await;
        let yandex = FakeDns::spawn(Behavior::Silent).await;
        let nsdi = FakeDns::spawn(Behavior::NxDomain).await;
        let config = ResolverConfig {
            link_budget: Duration::from_millis(40),
            static_budget: Duration::from_millis(50),
            system_budget: Duration::from_millis(300),
            total_budget: Duration::from_millis(400),
            query_timeout: Duration::from_millis(40),
            static_providers: vec![
                provider("fake-yandex", yandex.address, true),
                provider("fake-nsdi", nsdi.address, false),
            ],
            ..base_config()
        };
        let resolver = DnsResolver::with_config(config, vec![link.address]);

        // `localhost` резолвится системой: если бы НСДИ подтверждал NXDOMAIN,
        // лукап завершился бы ошибкой, не дойдя до system.
        let result = resolver.lookup_a("localhost").await.unwrap();
        assert_eq!(result, vec![IpAddr::V4(Ipv4Addr::LOCALHOST)]);
        assert!(!matches!(
            resolver.cache_lookup("localhost", DNS_A_RECORD),
            CacheLookup::Negative(_)
        ));
    }

    #[tokio::test]
    async fn nxdomain_from_both_static_providers_needs_link_and_yandex() {
        // Оба static-провайдера в одном тире ответили NXDOMAIN. Подтверждает
        // только Яндекс: НСДИ не подтверждает никогда.
        let static_config = |yandex: SocketAddr, nsdi: SocketAddr| ResolverConfig {
            link_budget: Duration::from_millis(20),
            static_budget: Duration::from_millis(60),
            system_budget: Duration::from_millis(300),
            total_budget: Duration::from_millis(400),
            query_timeout: Duration::from_millis(40),
            static_providers: vec![
                provider("fake-yandex", yandex, true),
                provider("fake-nsdi", nsdi, false),
            ],
            ..base_config()
        };

        // Без link: Яндекс + НСДИ — всё ещё мягкий NXDOMAIN, system опрашивается.
        let yandex = FakeDns::spawn(Behavior::NxDomain).await;
        let nsdi = FakeDns::spawn(Behavior::NxDomain).await;
        let resolver =
            DnsResolver::with_config(static_config(yandex.address, nsdi.address), Vec::new());
        let result = resolver.lookup_a("localhost").await.unwrap();
        assert_eq!(result, vec![IpAddr::V4(Ipv4Addr::LOCALHOST)]);
        assert_eq!(yandex.a_count(), 1);
        assert_eq!(
            nsdi.a_count(),
            1,
            "оба static-провайдера опрошены в одном тире"
        );
        assert!(!matches!(
            resolver.cache_lookup("localhost", DNS_A_RECORD),
            CacheLookup::Negative(_)
        ));

        // Link + Яндекс: подтверждение, до system дело не доходит.
        let link = FakeDns::spawn(Behavior::NxDomain).await;
        let yandex = FakeDns::spawn(Behavior::NxDomain).await;
        let nsdi = FakeDns::spawn(Behavior::NxDomain).await;
        let resolver = DnsResolver::with_config(
            static_config(yandex.address, nsdi.address),
            vec![link.address],
        );
        let error = resolver
            .lookup_a("localhost")
            .await
            .expect_err("подтверждённый NXDOMAIN не даёт адреса");
        assert!(error.to_string().contains("NXDOMAIN"), "{error}");
        assert!(matches!(
            resolver.cache_lookup("localhost", DNS_A_RECORD),
            CacheLookup::Negative(NegativeKind::NxDomain)
        ));
    }

    #[tokio::test]
    async fn nodata_a_then_aaaa() {
        let link = FakeDns::spawn(Behavior::Answer {
            v4: Vec::new(),
            v6: vec![TEST_V6],
            ttl: 60,
        })
        .await;
        let resolver = DnsResolver::with_config(base_config(), vec![link.address]);

        let result = resolver.lookup("v6.example").await.unwrap();
        assert_eq!(result, vec![IpAddr::V6(TEST_V6)]);
        assert_eq!(link.a_count(), 1);
        assert_eq!(link.aaaa_count(), 1);

        // NODATA для A закэширован: lookup_a отвечает без нового A-запроса.
        let error = resolver
            .lookup_a("v6.example")
            .await
            .expect_err("NODATA для A не даёт адреса");
        assert!(error.to_string().contains("NODATA"), "{error}");
        assert_eq!(link.a_count(), 1, "NODATA берётся из отрицательного кэша");

        let error = resolver
            .lookup_a("peer-only.example")
            .await
            .expect_err("NODATA для A не даёт адреса");
        assert!(error.to_string().contains("NODATA"), "{error}");
        assert_eq!(link.a_count(), 2);
        assert_eq!(link.aaaa_count(), 1, "lookup_a не запрашивает AAAA");
    }

    #[tokio::test]
    async fn system_budget_times_out() {
        let started = Instant::now();
        let outcome = system_answer(Duration::from_millis(10), async {
            tokio::time::sleep(Duration::from_millis(50)).await;
            Ok::<Vec<IpAddr>, std::io::Error>(Vec::new())
        })
        .await;
        let elapsed = started.elapsed();
        assert!(matches!(outcome, SystemOutcome::TimedOut));
        assert!(
            elapsed < Duration::from_millis(45),
            "бюджет system должен прервать future, прошло {elapsed:?}"
        );
    }

    #[tokio::test]
    async fn system_answer_filters_family() {
        assert_eq!(
            family_addresses(vec![IpAddr::V6(TEST_V6), IpAddr::V4(TEST_IP)], DNS_A_RECORD),
            vec![IpAddr::V4(TEST_IP)]
        );
        assert_eq!(
            family_addresses(
                vec![IpAddr::V6(TEST_V6), IpAddr::V4(TEST_IP)],
                DNS_AAAA_RECORD
            ),
            vec![IpAddr::V6(TEST_V6)]
        );
        let outcome = system_answer(Duration::from_millis(50), async {
            Ok::<Vec<IpAddr>, std::io::Error>(Vec::new())
        })
        .await;
        assert!(matches!(outcome, SystemOutcome::NoData));
    }

    #[tokio::test]
    async fn ipv4_only_peer_resolution() {
        let fake = FakeDns::spawn(Behavior::Answer {
            v4: vec![TEST_IP],
            v6: vec![TEST_V6],
            ttl: 60,
        })
        .await;
        let resolver = DnsResolver::with_config(base_config(), vec![fake.address]);

        let socket = resolve_socket_with(&resolver, "peer.example:46000")
            .await
            .unwrap();
        assert_eq!(socket, SocketAddr::new(IpAddr::V4(TEST_IP), 46000));
        assert_eq!(fake.aaaa_count(), 0, "пир резолвится только по A");

        assert_eq!(
            resolve_socket_with(&resolver, "[::1]:46000").await.unwrap(),
            "[::1]:46000".parse().unwrap()
        );
        assert_eq!(fake.a_count(), 1);
    }

    #[tokio::test]
    async fn literal_socket_does_not_require_dns() {
        let resolver = DnsResolver::with_config(base_config(), Vec::new());
        assert_eq!(
            resolve_socket_with(&resolver, "127.0.0.1:46000")
                .await
                .unwrap(),
            "127.0.0.1:46000".parse().unwrap()
        );
        assert_eq!(
            resolve_socket_with(&resolver, "[::1]:46000").await.unwrap(),
            "[::1]:46000".parse().unwrap()
        );
    }

    #[tokio::test]
    async fn cache_positive_ttl_and_hit() {
        let link = FakeDns::spawn(Behavior::Answer {
            v4: vec![TEST_IP],
            v6: Vec::new(),
            ttl: 3000,
        })
        .await;
        let resolver = DnsResolver::with_config(base_config(), vec![link.address]);

        let first = resolver.lookup_a("cached.example").await.unwrap();
        let second = resolver.lookup_a("cached.example").await.unwrap();
        assert_eq!(first, vec![IpAddr::V4(TEST_IP)]);
        assert_eq!(first, second);
        assert_eq!(link.a_count(), 1, "повторный lookup должен попадать в кэш");

        let cache = resolver.cache.read().unwrap();
        let Some(CacheEntry::Positive { expires_at, .. }) =
            cache.get(&("cached.example".to_owned(), DNS_A_RECORD))
        else {
            panic!("ожидалась положительная запись кэша");
        };
        let ttl = expires_at.saturating_duration_since(Instant::now());
        assert!(
            ttl <= POSITIVE_CACHE_MAX_TTL,
            "TTL не больше 900 с: {ttl:?}"
        );
        assert!(
            ttl > Duration::from_secs(880),
            "TTL = min(record, 900): {ttl:?}"
        );
    }

    #[tokio::test]
    async fn cache_negative_confirmed_nxdomain() {
        let link = FakeDns::spawn(Behavior::NxDomain).await;
        let yandex = FakeDns::spawn(Behavior::NxDomain).await;
        let nsdi = FakeDns::spawn(Behavior::Silent).await;
        let config = ResolverConfig {
            link_budget: Duration::from_millis(40),
            static_budget: Duration::from_millis(60),
            system_budget: Duration::from_millis(40),
            total_budget: Duration::from_millis(200),
            query_timeout: Duration::from_millis(40),
            static_providers: vec![
                provider("fake-yandex", yandex.address, true),
                provider("fake-nsdi", nsdi.address, false),
            ],
            ..base_config()
        };
        let resolver = DnsResolver::with_config(config, vec![link.address]);

        let first = resolver.lookup_a("nx.example").await.unwrap_err();
        assert!(first.to_string().contains("NXDOMAIN"), "{first}");
        assert_eq!(link.a_count(), 1);
        assert_eq!(yandex.a_count(), 1);

        let second = resolver.lookup_a("nx.example").await.unwrap_err();
        assert!(second.to_string().contains("NXDOMAIN"), "{second}");
        assert_eq!(
            link.a_count(),
            1,
            "подтверждённый NXDOMAIN отдаётся из кэша"
        );
        assert_eq!(yandex.a_count(), 1);
        assert!(matches!(
            resolver.cache_lookup("nx.example", DNS_A_RECORD),
            CacheLookup::Negative(NegativeKind::NxDomain)
        ));
    }

    #[tokio::test]
    async fn cache_soft_nxdomain_not_cached() {
        let yandex = FakeDns::spawn(Behavior::NxDomain).await;
        let nsdi = FakeDns::spawn(Behavior::Silent).await;
        let config = ResolverConfig {
            link_budget: Duration::from_millis(20),
            static_budget: Duration::from_millis(50),
            system_budget: Duration::from_millis(40),
            total_budget: Duration::from_millis(200),
            query_timeout: Duration::from_millis(40),
            static_providers: vec![
                provider("fake-yandex", yandex.address, true),
                provider("fake-nsdi", nsdi.address, false),
            ],
            ..base_config()
        };
        let resolver = DnsResolver::with_config(config, Vec::new());

        assert!(resolver.lookup_a("soft.example").await.is_err());
        assert!(resolver.lookup_a("soft.example").await.is_err());
        assert_eq!(yandex.a_count(), 2, "мягкий NXDOMAIN не кэшируется");
        assert!(resolver.cache.read().unwrap().is_empty());
    }

    #[tokio::test]
    async fn cache_update_dns_preserves_positive() {
        let link = FakeDns::spawn(answering(TEST_IP)).await;
        let resolver = DnsResolver::with_config(base_config(), vec![link.address]);
        assert_eq!(
            resolver.lookup_a("kept.example").await.unwrap(),
            vec![IpAddr::V4(TEST_IP)]
        );
        assert_eq!(link.a_count(), 1);

        resolver.update_link_servers("127.0.0.1");
        assert_eq!(
            resolver.lookup_a("kept.example").await.unwrap(),
            vec![IpAddr::V4(TEST_IP)]
        );
        assert_eq!(link.a_count(), 1, "положительный кэш переживает UPDATE_DNS");
        assert!(
            resolver.health.read().unwrap().preferred.is_none(),
            "health сбрасывается"
        );

        // Отрицательный кэш после UPDATE_DNS должен быть сброшен.
        let link = FakeDns::spawn(Behavior::NxDomain).await;
        let yandex = FakeDns::spawn(Behavior::NxDomain).await;
        let config = ResolverConfig {
            link_budget: Duration::from_millis(40),
            static_budget: Duration::from_millis(60),
            system_budget: Duration::from_millis(40),
            total_budget: Duration::from_millis(200),
            query_timeout: Duration::from_millis(40),
            static_providers: vec![provider("fake-yandex", yandex.address, true)],
            ..base_config()
        };
        let resolver = DnsResolver::with_config(config, vec![link.address]);
        assert!(resolver.lookup_a("nx.example").await.is_err());
        assert!(matches!(
            resolver.cache_lookup("nx.example", DNS_A_RECORD),
            CacheLookup::Negative(NegativeKind::NxDomain)
        ));
        resolver.update_link_servers("");
        assert!(matches!(
            resolver.cache_lookup("nx.example", DNS_A_RECORD),
            CacheLookup::Miss
        ));
        assert!(resolver.health.read().unwrap().cooldown.is_empty());
        assert!(resolver.health.read().unwrap().preferred.is_none());
    }

    #[tokio::test]
    async fn update_dns_replaces_link_and_cancels_inflight() {
        let silent = FakeDns::spawn(Behavior::Silent).await;
        let replacement = FakeDns::spawn(answering(TEST_IP)).await;
        let statics = FakeDns::spawn(answering(OTHER_IP)).await;
        let config = ResolverConfig {
            link_budget: Duration::from_millis(400),
            static_budget: Duration::from_millis(80),
            system_budget: Duration::from_millis(40),
            total_budget: Duration::from_millis(500),
            query_timeout: Duration::from_millis(400),
            link_port: replacement.address.port(),
            static_providers: vec![provider("fake-static", statics.address, true)],
            ..base_config()
        };
        let resolver = Arc::new(DnsResolver::with_config(config, vec![silent.address]));

        let lookup = tokio::spawn({
            let resolver = resolver.clone();
            async move { resolver.lookup_a("peer.example").await }
        });
        tokio::time::sleep(Duration::from_millis(60)).await;
        assert_eq!(silent.a_count(), 1, "первый проход должен висеть на link");

        assert_eq!(resolver.update_link_servers("127.0.0.1"), (1, 0));
        let result = lookup.await.unwrap().unwrap();
        assert_eq!(result, vec![IpAddr::V4(TEST_IP)]);
        assert_eq!(replacement.a_count(), 1, "проход перезапущен с новым link");
        assert_eq!(statics.a_count(), 0, "до static дело не дошло");
    }

    #[tokio::test]
    async fn update_dns_replaces_the_link_list_and_bumps_generation() {
        let resolver = DnsResolver::with_config(base_config(), Vec::new());
        let generation = resolver.generation.load(Ordering::Acquire);

        assert_eq!(
            resolver.update_link_servers(" 95.167.167.95, 95.167.167.96, ::1 "),
            (2, 1)
        );
        let link: Vec<String> = resolver
            .link
            .read()
            .unwrap()
            .iter()
            .map(|server| server.ip().to_string())
            .collect();
        assert_eq!(link, vec!["95.167.167.95", "95.167.167.96"]);
        assert!(resolver.generation.load(Ordering::Acquire) > generation);

        assert_eq!(resolver.update_link_servers(""), (0, 0));
        assert!(resolver.link.read().unwrap().is_empty());
    }

    #[tokio::test]
    async fn health_skips_timed_out_server() {
        let silent = FakeDns::spawn(Behavior::Silent).await;
        let statics = FakeDns::spawn(answering(TEST_IP)).await;
        let config = ResolverConfig {
            link_budget: Duration::from_millis(60),
            static_budget: Duration::from_millis(40),
            system_budget: Duration::from_millis(30),
            total_budget: Duration::from_millis(140),
            query_timeout: Duration::from_millis(25),
            cooldown_base: Duration::from_secs(60),
            cooldown_max: Duration::from_secs(60),
            static_providers: vec![provider("fake-static", statics.address, true)],
            ..base_config()
        };
        let resolver = DnsResolver::with_config(config, vec![silent.address]);

        // Первый таймаут — только счётчик, кулдауна ещё нет.
        assert_eq!(
            resolver.lookup_a("one.example").await.unwrap(),
            vec![IpAddr::V4(TEST_IP)]
        );
        assert_eq!(silent.a_count(), 1);
        assert!(
            !resolver
                .health
                .read()
                .unwrap()
                .cooldown
                .contains_key(&silent.address),
            "один таймаут не уводит сервер в кулдаун"
        );

        // Второй подряд — кулдаун.
        resolver.health.write().unwrap().preferred = None;
        assert_eq!(
            resolver.lookup_a("two.example").await.unwrap(),
            vec![IpAddr::V4(TEST_IP)]
        );
        assert_eq!(silent.a_count(), 2);
        assert!(
            resolver
                .health
                .read()
                .unwrap()
                .cooldown
                .contains_key(&silent.address)
        );

        assert!(resolver.ordered_servers(SourceId::Link, false).is_empty());
        assert_eq!(
            resolver.ordered_servers(SourceId::Link, true),
            vec![silent.address],
            "повтор игнорирует кулдаун"
        );

        // Снимаем предпочтение, чтобы проверить именно кулдаун link.
        resolver.health.write().unwrap().preferred = None;
        assert_eq!(
            resolver.lookup_a("three.example").await.unwrap(),
            vec![IpAddr::V4(TEST_IP)]
        );
        assert_eq!(silent.a_count(), 2, "сервер в кулдауне не опрашивается");
    }

    #[tokio::test]
    async fn health_preferred_source_first() {
        let silent = FakeDns::spawn(Behavior::Silent).await;
        let statics = FakeDns::spawn(answering(TEST_IP)).await;
        let config = ResolverConfig {
            link_budget: Duration::from_millis(100),
            static_budget: Duration::from_millis(40),
            system_budget: Duration::from_millis(30),
            total_budget: Duration::from_millis(200),
            query_timeout: Duration::from_millis(40),
            cooldown_base: Duration::from_secs(60),
            cooldown_max: Duration::from_secs(60),
            static_providers: vec![provider("fake-static", statics.address, true)],
            ..base_config()
        };
        let resolver = DnsResolver::with_config(config, vec![silent.address]);

        let started = Instant::now();
        assert_eq!(
            resolver.lookup_a("one.example").await.unwrap(),
            vec![IpAddr::V4(TEST_IP)]
        );
        assert!(started.elapsed() >= Duration::from_millis(30));
        assert_eq!(silent.a_count(), 1);

        assert_eq!(
            resolver.health.read().unwrap().preferred,
            Some(SourceId::Static(0))
        );
        assert_eq!(resolver.pass_order().first().copied(), Some(Stage::Static));

        let started = Instant::now();
        assert_eq!(
            resolver.lookup_a("two.example").await.unwrap(),
            vec![IpAddr::V4(TEST_IP)]
        );
        assert!(
            started.elapsed() < Duration::from_millis(25),
            "предпочитаемый источник отвечает первым"
        );
        assert_eq!(silent.a_count(), 1);
        assert_eq!(statics.a_count(), 2);
    }

    #[tokio::test]
    async fn dns_cache_hit_avoids_network_queries() {
        let resolver = DnsResolver::with_config(base_config(), Vec::new());
        let ip = IpAddr::V4(Ipv4Addr::new(93, 184, 216, 34));
        resolver.cache.write().unwrap().insert(
            ("cached.example.ru".to_string(), DNS_A_RECORD),
            CacheEntry::Positive {
                addresses: vec![ip],
                expires_at: Instant::now() + Duration::from_secs(300),
            },
        );
        assert_eq!(
            resolver.lookup_a("cached.example.ru").await.unwrap(),
            vec![ip]
        );
    }

    #[tokio::test]
    async fn dns_stale_cache_serves_requests_when_dns_fails() {
        let statics = FakeDns::spawn(Behavior::Silent).await;
        let config = ResolverConfig {
            link_budget: Duration::from_millis(20),
            static_budget: Duration::from_millis(30),
            system_budget: Duration::from_millis(30),
            total_budget: Duration::from_millis(150),
            query_timeout: Duration::from_millis(20),
            retry_delay: Duration::from_millis(10),
            static_providers: vec![provider("fake-static", statics.address, true)],
            ..base_config()
        };
        let resolver = DnsResolver::with_config(config, Vec::new());
        let ip = IpAddr::V4(Ipv4Addr::new(1, 2, 3, 4));
        resolver.cache.write().unwrap().insert(
            ("unreachable.example".to_string(), DNS_A_RECORD),
            CacheEntry::Positive {
                addresses: vec![ip],
                expires_at: Instant::now() - Duration::from_secs(10),
            },
        );
        assert_eq!(
            resolver.lookup_a("unreachable.example").await.unwrap(),
            vec![ip]
        );
    }

    #[tokio::test]
    async fn servfail_needs_two_consecutive_failures_for_cooldown() {
        let link = FakeDns::spawn(Behavior::ServFail).await;
        let statics = FakeDns::spawn(answering(OTHER_IP)).await;
        let config = ResolverConfig {
            link_budget: Duration::from_millis(40),
            static_budget: Duration::from_millis(40),
            system_budget: Duration::from_millis(30),
            total_budget: Duration::from_millis(140),
            query_timeout: Duration::from_millis(30),
            cooldown_base: Duration::from_secs(60),
            cooldown_max: Duration::from_secs(60),
            static_providers: vec![provider("fake-static", statics.address, true)],
            ..base_config()
        };
        let resolver = DnsResolver::with_config(config, vec![link.address]);

        assert_eq!(
            resolver.lookup_a("servfail.example").await.unwrap(),
            vec![IpAddr::V4(OTHER_IP)]
        );
        assert_eq!(link.a_count(), 1);
        {
            let health = resolver.health.read().unwrap();
            assert!(
                !health.cooldown.contains_key(&link.address),
                "один SERVFAIL не уводит сервер в кулдаун"
            );
            assert_eq!(health.strikes.get(&link.address), Some(&1));
        }

        // Снимаем предпочтение, чтобы link снова опросили первым.
        resolver.health.write().unwrap().preferred = None;
        assert_eq!(
            resolver.lookup_a("servfail-2.example").await.unwrap(),
            vec![IpAddr::V4(OTHER_IP)]
        );
        assert_eq!(link.a_count(), 2);
        assert!(
            resolver
                .health
                .read()
                .unwrap()
                .cooldown
                .contains_key(&link.address),
            "второй подряд SERVFAIL — кулдаун"
        );
    }

    /// Ждёт выполнения условия, опрашивая его раз в 5 мс (не дольше секунды).
    async fn wait_until(mut condition: impl FnMut() -> bool) -> bool {
        for _ in 0..200 {
            if condition() {
                return true;
            }
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
        false
    }

    /// Состояние записи негативного кэша отказов: (strikes, остаток TTL).
    fn failure_state(resolver: &DnsResolver, host: &str, qtype: u16) -> (u32, Duration) {
        let failures = resolver.failures.read().unwrap();
        let entry = failures
            .get(&(host.to_owned(), qtype))
            .expect("ожидалась запись негативного кэша отказов");
        (
            entry.strikes,
            entry.expires_at.saturating_duration_since(Instant::now()),
        )
    }

    #[tokio::test]
    async fn singleflight_shares_one_ladder_pass() {
        let link = FakeDns::spawn_delayed(answering(TEST_IP), Duration::from_millis(80)).await;
        let config = ResolverConfig {
            link_budget: Duration::from_millis(400),
            total_budget: Duration::from_millis(500),
            query_timeout: Duration::from_millis(400),
            ..base_config()
        };
        let resolver = Arc::new(DnsResolver::with_config(config, vec![link.address]));

        let mut tasks = Vec::new();
        for _ in 0..8 {
            let resolver = Arc::clone(&resolver);
            tasks.push(tokio::spawn(async move {
                resolver.lookup_a("burst.example").await
            }));
        }
        for task in tasks {
            assert_eq!(task.await.unwrap().unwrap(), vec![IpAddr::V4(TEST_IP)]);
        }
        assert_eq!(
            link.a_count(),
            1,
            "все параллельные запросы делят один проход ладдера"
        );
        assert!(
            resolver.flights.read().unwrap().is_empty(),
            "общий проход снят из карты после завершения"
        );
    }

    #[tokio::test]
    async fn singleflight_leader_abort_does_not_cancel_follower() {
        let link = FakeDns::spawn_delayed(answering(TEST_IP), Duration::from_millis(150)).await;
        let config = ResolverConfig {
            link_budget: Duration::from_millis(400),
            total_budget: Duration::from_millis(500),
            query_timeout: Duration::from_millis(400),
            ..base_config()
        };
        let resolver = Arc::new(DnsResolver::with_config(config, vec![link.address]));

        let leader = tokio::spawn({
            let resolver = Arc::clone(&resolver);
            async move { resolver.lookup_a("slow.example").await }
        });
        assert!(
            wait_until(|| link.a_count() > 0).await,
            "ведущий не отправил запрос"
        );
        let follower = tokio::spawn({
            let resolver = Arc::clone(&resolver);
            async move { resolver.lookup_a("slow.example").await }
        });
        tokio::time::sleep(Duration::from_millis(20)).await;
        assert_eq!(
            resolver.flights.read().unwrap().len(),
            1,
            "ведомый присоединяется к общему проходу"
        );

        leader.abort();
        assert_eq!(
            follower.await.unwrap().unwrap(),
            vec![IpAddr::V4(TEST_IP)],
            "отмена ведущего не отменяет общий проход"
        );
        assert_eq!(link.a_count(), 1, "проход остался один");
        assert!(
            resolver.flights.read().unwrap().is_empty(),
            "запись общего прохода не залипла"
        );
    }

    #[tokio::test]
    async fn failure_cache_written_only_after_retry_exhaustion() {
        let link = FakeDns::spawn(Behavior::Silent).await;
        let config = ResolverConfig {
            link_budget: Duration::from_millis(30),
            static_budget: Duration::from_millis(30),
            system_budget: Duration::from_millis(20),
            total_budget: Duration::from_millis(100),
            query_timeout: Duration::from_millis(20),
            retry_delay: Duration::from_millis(300),
            ..base_config()
        };
        let resolver = Arc::new(DnsResolver::with_config(config, vec![link.address]));

        let lookup = tokio::spawn({
            let resolver = Arc::clone(&resolver);
            async move { resolver.lookup_a("flaky.example").await }
        });
        assert!(wait_until(|| link.a_count() >= 1).await);
        assert!(
            resolver.failures.read().unwrap().is_empty(),
            "после первого прохода отказ ещё не запоминается"
        );

        let error = lookup.await.unwrap().unwrap_err();
        assert!(
            error.to_string().contains("ни один источник не ответил"),
            "{error}"
        );
        assert_eq!(link.a_count(), 2, "оба прохода исчерпаны");
        let (strikes, ttl) = failure_state(&resolver, "flaky.example", DNS_A_RECORD);
        assert_eq!(strikes, 1);
        assert!(
            ttl >= Duration::from_millis(1600) && ttl <= Duration::from_millis(2400),
            "TTL базы 2 с с разбросом ±20 %: {ttl:?}"
        );
    }

    #[tokio::test]
    async fn failure_cache_backoff_and_clear() {
        let link = FakeDns::spawn(Behavior::Silent).await;
        let config = ResolverConfig {
            // Кулдаун длиннее теста: сервер не должен выпадать из ладдера раньше
            // второго отказа, иначе проход сократится.
            cooldown_base: Duration::from_secs(60),
            cooldown_max: Duration::from_secs(60),
            retry_delay: Duration::from_millis(10),
            ..base_config()
        };
        let resolver = DnsResolver::with_config(config, vec![link.address]);

        assert!(resolver.lookup_a("down.example").await.is_err());
        let (strikes, ttl) = failure_state(&resolver, "down.example", DNS_A_RECORD);
        assert_eq!(strikes, 1);
        assert!(ttl <= Duration::from_millis(2400), "{ttl:?}");

        // Просрочиваем запись: следующий подряд отказ удваивает TTL.
        {
            let mut failures = resolver.failures.write().unwrap();
            if let Some(entry) = failures.get_mut(&("down.example".to_owned(), DNS_A_RECORD)) {
                entry.expires_at = Instant::now() - Duration::from_millis(1);
            }
        }
        assert!(resolver.lookup_a("down.example").await.is_err());
        let (strikes, ttl) = failure_state(&resolver, "down.example", DNS_A_RECORD);
        assert_eq!(strikes, 2);
        assert!(
            ttl >= Duration::from_millis(3200) && ttl <= Duration::from_millis(4800),
            "второй подряд отказ удваивает TTL: {ttl:?}"
        );

        // Успешный ответ стирает запись негативного кэша отказов.
        let link = FakeDns::spawn(answering(TEST_IP)).await;
        let resolver = DnsResolver::with_config(base_config(), vec![link.address]);
        resolver.failures.write().unwrap().insert(
            ("healed.example".to_owned(), DNS_A_RECORD),
            FailureEntry {
                strikes: 3,
                expires_at: Instant::now() - Duration::from_secs(1),
                message: "DNS: ни один источник не ответил для healed.example".to_owned(),
            },
        );
        assert_eq!(
            resolver.lookup_a("healed.example").await.unwrap(),
            vec![IpAddr::V4(TEST_IP)]
        );
        assert!(
            resolver.failures.read().unwrap().is_empty(),
            "успех стирает запись отказов"
        );

        // NODATA тоже считается состоявшимся ответом и стирает запись.
        let nodata = FakeDns::spawn(Behavior::Answer {
            v4: Vec::new(),
            v6: Vec::new(),
            ttl: 60,
        })
        .await;
        let resolver = DnsResolver::with_config(base_config(), vec![nodata.address]);
        resolver.failures.write().unwrap().insert(
            ("empty.example".to_owned(), DNS_A_RECORD),
            FailureEntry {
                strikes: 3,
                expires_at: Instant::now() - Duration::from_secs(1),
                message: "DNS: ни один источник не ответил для empty.example".to_owned(),
            },
        );
        assert!(resolver.lookup_a("empty.example").await.is_err());
        assert!(
            resolver.failures.read().unwrap().is_empty(),
            "NODATA стирает запись отказов"
        );
    }

    #[test]
    fn cooldown_needs_two_consecutive_failures() {
        let resolver = DnsResolver::with_config(base_config(), Vec::new());
        let server = SocketAddr::new(IpAddr::V4(TEST_IP), DNS_PORT);
        let other = SocketAddr::new(IpAddr::V4(OTHER_IP), DNS_PORT);

        resolver.mark_failure(server);
        {
            let health = resolver.health.read().unwrap();
            assert!(
                !health.cooldown.contains_key(&server),
                "первый отказ не уводит сервер в кулдаун"
            );
            assert_eq!(health.strikes.get(&server), Some(&1));
        }

        resolver.mark_failure(server);
        {
            let health = resolver.health.read().unwrap();
            assert_eq!(health.strikes.get(&server), Some(&2));
            let remaining = health
                .cooldown
                .get(&server)
                .copied()
                .expect("второй подряд отказ даёт кулдаун")
                .saturating_duration_since(Instant::now());
            assert!(
                remaining > Duration::from_secs(4) && remaining <= Duration::from_secs(5),
                "первый кулдаун равен базе: {remaining:?}"
            );
        }

        // Третий подряд отказ удваивает кулдаун, четвёртый упирается в потолок.
        for expected_seconds in [10_u64, 10] {
            resolver
                .health
                .write()
                .unwrap()
                .cooldown
                .insert(server, Instant::now() - Duration::from_millis(1));
            resolver.mark_failure(server);
            let health = resolver.health.read().unwrap();
            let remaining = health
                .cooldown
                .get(&server)
                .copied()
                .expect("кулдаун продлевается")
                .saturating_duration_since(Instant::now());
            assert!(
                remaining > Duration::from_secs(expected_seconds - 1)
                    && remaining <= Duration::from_secs(expected_seconds),
                "кулдаун растёт до потолка: {remaining:?}"
            );
        }

        // Успех сбрасывает свой счётчик и не трогает чужой.
        resolver.mark_failure(other);
        resolver.mark_success(SourceId::Link, server);
        {
            let health = resolver.health.read().unwrap();
            assert!(!health.cooldown.contains_key(&server));
            assert_eq!(health.strikes.get(&server), None);
            assert_eq!(
                health.strikes.get(&other),
                Some(&1),
                "успех одного сервера не сбрасывает счётчик другого"
            );
        }

        // Системный тир живёт по тому же правилу: второй таймаут — кулдаун.
        resolver.mark_system_failure();
        assert!(
            resolver.health.read().unwrap().system_cooldown.is_none(),
            "первый таймаут системы не уводит её в кулдаун"
        );
        resolver.mark_system_failure();
        assert!(resolver.health.read().unwrap().system_cooldown.is_some());
        resolver.mark_system_success();
        {
            let health = resolver.health.read().unwrap();
            assert!(health.system_cooldown.is_none());
            assert_eq!(health.system_strikes, 0);
        }
    }

    #[tokio::test]
    async fn local_failure_does_not_strike_server() {
        let server = SocketAddr::new(IpAddr::V4(TEST_IP), DNS_PORT);
        let resolver = DnsResolver::with_config(base_config(), vec![server]);

        // Некорректное имя валит `build_query` — это локальная ошибка, а не
        // ответ сервера.
        assert!(resolver.lookup_a("bad..name").await.is_err());
        let health = resolver.health.read().unwrap();
        assert!(
            health.strikes.is_empty(),
            "локальная ошибка не считается отказом сервера"
        );
        assert!(health.cooldown.is_empty());
        assert_eq!(health.system_strikes, 0);
    }
}
