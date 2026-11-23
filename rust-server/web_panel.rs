use crate::App;
use crate::lock_unpoison;
use base64::Engine as _;
use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::LazyLock;

mod auth;
mod clients;
mod clients_batch;
mod dns;
mod groups;
mod host;
mod links;
mod local_proxy;
mod logs;
mod persistence;
mod pwa;
mod router;
mod routing;
mod settings;
mod stats;
mod stealth;
mod subscriptions;
mod transfer;
mod validation;
mod vk;

// Внутри панели модули видят друг друга через этот реэкспорт. Наружу
// (`main.rs`, `protocol.rs`) панель отдаёт только `run`, шифрование ссылок
// (`encrypt_csqtt_link`, `decrypt_csqtt_link`, `CSQTT_CIPHER_KEY_SEED`) и
// DNS-хелперы (`dns_provider_id`, `dns_provider_is_known`, `provider_dns_by_id`,
// `deploy_dns_profile_or_yandex`); остальные имена — внутренние для панели.
pub(crate) use auth::*;
pub(crate) use clients::*;
pub(crate) use clients_batch::*;
pub(crate) use dns::*;
pub(crate) use groups::*;
pub(crate) use host::*;
pub(crate) use links::*;
pub(crate) use local_proxy::*;
pub(crate) use logs::*;
pub(crate) use persistence::*;
pub(crate) use pwa::*;
pub(crate) use router::*;
pub(crate) use routing::*;
pub(crate) use settings::*;
pub(crate) use stats::*;
pub(crate) use stealth::*;
pub(crate) use subscriptions::*;
pub(crate) use transfer::*;
pub(crate) use validation::*;
pub(crate) use vk::*;

#[derive(Clone)]
pub(crate) struct WebState {
    pub(crate) app: Arc<App>,
}

pub(crate) const WEB_BODY_LIMIT_BYTES: usize = 16 * 1024;

/// Лимит тела для создания/правки маршрутного правила и импорта базы.
///
/// Правило доставляется событием ≤ 65 536 байт, но его JSON в запросе панели
/// содержит ещё явные адреса, обвязку и экранирование; худший валидный случай
/// (~1 024 домена по ~58 символов + 512 адресов + blob) — около 113 КБ. Общий
/// лимит 16 КиБ отсекал такие запросы до валидатора (413 вместо 400), из-за
/// чего ветка «правило не доставляется» была недостижима. Импорт получает тот
/// же лимит: иначе сохранённое правило 16–256 КБ нельзя было бы восстановить
/// из экспорта. 256 КиБ оставляют запас и не расширяют остальные endpoint'ы.
pub(crate) const ROUTING_RULE_BODY_LIMIT_BYTES: usize = 256 * 1024;
// D1: тело правила обязано проходить дальше общего лимита и достигать
// валидатора, который и возвращает 400 про размер события.

const _: () = assert!(ROUTING_RULE_BODY_LIMIT_BYTES > 65_536);

const _: () = assert!(ROUTING_RULE_BODY_LIMIT_BYTES >= 4 * WEB_BODY_LIMIT_BYTES);

pub async fn run(
    app: Arc<App>,
    tls_config: axum_server::tls_rustls::RustlsConfig,
) -> anyhow::Result<()> {
    let router = panel_router(app.clone());
    tokio::spawn(web_session_janitor(app.clone()));
    tokio::spawn(web_slowloris_watchdog());
    println!("[WEB] HTTP/HTTPS listening on 0.0.0.0:{}", app.web_port);
    let acceptor = DualAcceptor::new(tls_config, app.clone());
    axum_server::Server::bind(SocketAddr::from(([0, 0, 0, 0], app.web_port)))
        .acceptor(acceptor)
        .serve(router.into_make_service_with_connect_info::<SocketAddr>())
        .await?;
    Ok(())
}

pub(crate) const PWA_MANIFEST: &str = r##"{
  "id": "/",
  "name": "reCSQTT",
  "short_name": "reCSQTT",
  "description": "Панель управления сервером reCSQTT",
  "lang": "ru",
  "start_url": "/",
  "scope": "/",
  "display": "standalone",
  "display_override": ["window-controls-overlay", "standalone"],
  "prefer_related_applications": false,
  "background_color": "#0a0a0c",
  "theme_color": "#0a0a0c",
  "icons": [
    {
      "src": "/pwa-icon-192.png",
      "sizes": "192x192",
      "type": "image/png",
      "purpose": "any maskable"
    },
    {
      "src": "/pwa-icon-512.png",
      "sizes": "512x512",
      "type": "image/png",
      "purpose": "any maskable"
    },
    {
      "src": "/pwa-icon.svg",
      "sizes": "any",
      "type": "image/svg+xml",
      "purpose": "any maskable"
    }
  ]
}"##;

pub(crate) const PWA_ICON_SVG: &str = r##"<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512"><rect width="512" height="512" rx="122" fill="#0a0a0c"/><defs><linearGradient id="c" x1="128" y1="152" x2="390" y2="360" gradientUnits="userSpaceOnUse"><stop stop-color="#55adff"/><stop offset="1" stop-color="#0875f5"/></linearGradient></defs><path fill="url(#c)" d="M374 151a148 148 0 1 0 0 210l-44-37a91 91 0 1 1 0-136z"/></svg>"##;

pub(crate) static PWA_ICON_192_PNG: LazyLock<Vec<u8>> = LazyLock::new(|| {
    base64::engine::general_purpose::STANDARD
        .decode("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")
        .unwrap_or_default()
});

pub(crate) static PWA_ICON_512_PNG: LazyLock<Vec<u8>> = LazyLock::new(|| {
    base64::engine::general_purpose::STANDARD
        .decode("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")
        .unwrap_or_default()
});

pub(crate) const PWA_SERVICE_WORKER: &str = r##"const CACHE_NAME = 'csqtt-panel-shell-2.5.4-pwa6';
const base = self.location.pathname.replace(/\/sw\.js$/, '');
const SHELL_ASSETS = ['/manifest.webmanifest', '/pwa-icon.svg', '/pwa-icon-192.png', '/pwa-icon-512.png', '/qrcode.min.js'].map(p => (base ? base : '') + p);

self.addEventListener('install', event => {
  event.waitUntil((async () => {
    try {
      const cache = await caches.open(CACHE_NAME);
      await cache.addAll(SHELL_ASSETS);
    } catch (_) {}
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', event => {
  event.waitUntil((async () => {
    const names = await caches.keys();
    await Promise.all(names.filter(name => name !== CACHE_NAME).map(name => caches.delete(name)));
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', event => {
  const request = event.request;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin || url.pathname.startsWith('/api/') || url.pathname.includes('/api/')) return;

  if (SHELL_ASSETS.includes(url.pathname)) {
    event.respondWith(caches.match(request).then(cached => cached || fetch(request)));
  }
});
"##;

// Разметка, стили и скрипты панели живут файлами в `rust-server/web/**` и
// собираются в документы модулем `crate::web_assets`; здесь их нет. Остаются
// только PWA-ассеты: версию в `PWA_SERVICE_WORKER` правит
// `scripts/bump_version.sh` именно в этом файле (см. `scripts/check_versions.sh`
// и `.github/tests/`).

#[cfg(test)]
mod tests;
