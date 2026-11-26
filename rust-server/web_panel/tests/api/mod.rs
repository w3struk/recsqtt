//! Роутер и REST API панели: аутентификация, импорт/экспорт, пакетные операции.

use crate::web_panel::{ROUTING_RULE_BODY_LIMIT_BYTES, WEB_BODY_LIMIT_BYTES, panel_router};
use crate::{
    App,
    model::{self, Database, DatabasePersistence, PasswordEntry, RoutingRule},
};
use axum::{
    Router,
    body::{Body, to_bytes},
    http::{Request, StatusCode},
    response::Response,
};
use serde_json::{Value, json};
use std::sync::Arc;
use tower_service::Service;

mod auth;
mod clients;
mod groups;
mod settings;
mod transfer;

/// The production router, auth and SQLite writer, without listeners or a dataplane.
struct Fixture {
    app: Arc<App>,
    router: Router,
}

impl Fixture {
    async fn new(db: Database) -> Self {
        Self::new_with_secret(db, "").await
    }

    /// Фикстура с секретным префиксом панели: нужно проверять, что секрет не
    /// отдаётся из корня.
    async fn new_with_secret(mut db: Database, web_secret_path: &str) -> Self {
        // Keep snapshot reads from repairing (and rewriting) an incomplete fixture.
        let _ = db.repair_stale_password_bindings();
        let config_dir = std::env::temp_dir().join("opencode").join(format!(
            "csqtt-panel-residual-{}-{}",
            std::process::id(),
            model::random_token(16)
        ));
        std::fs::create_dir_all(&config_dir).unwrap();
        let db_persistence = DatabasePersistence::new(config_dir.clone()).unwrap();
        let revision = db_persistence.submit(db.clone());
        db_persistence.wait(revision).await.unwrap();
        let (auto_restart_interval_tx, _) =
            tokio::sync::watch::channel(db.auto_restart_interval_hours());
        let app = Arc::new(App {
            dns: tokio::sync::RwLock::new(db.dns.clone()),
            db: tokio::sync::RwLock::new(db),
            db_persistence,
            log_file_path: config_dir.join("csqtt.log"),
            config_dir,
            listen: "127.0.0.1:0".parse().unwrap(),
            web_port: 0,
            web_user: "test".to_owned(),
            web_pass: "panel-residual-test-key".to_owned(),
            web_secret_path: web_secret_path.to_owned(),
            public_ip: Default::default(),
            secure_cookie: false,
            sessions: Default::default(),
            password_epochs: Default::default(),
            web_sessions: Default::default(),
            login_limits: Default::default(),
            web_auth_admission: Default::default(),
            bytes_from_client: Default::default(),
            bytes_to_client: Default::default(),
            total_connections: Default::default(),
            cpu_percent: Default::default(),
            cpu_cores: Default::default(),
            started: model::now(),
            derived_keys: Default::default(),
            logs: Default::default(),
            logging_active: Default::default(),
            stream_debug_active: Default::default(),
            proxy_route: Default::default(),
            proxy_operation: Default::default(),
            proxy_trigger: Default::default(),
            proxy_port_listening: Default::default(),
            proxy_health_error: Default::default(),
            memory_trim_gate: Default::default(),
            memory_trim_count: Default::default(),
            memory_trim_last_unix: Default::default(),
            auto_restart_interval_tx,
            restart_pending: Default::default(),
            dataplane: Default::default(),
        });
        let router = panel_router(app.clone());
        Self { app, router }
    }

    async fn request(&self, method: &str, uri: &str, body: Vec<u8>) -> Response {
        self.router
            .clone()
            .call(
                Request::builder()
                    .method(method)
                    .uri(uri)
                    .header("x-api-key", &self.app.web_pass)
                    .header("content-type", "application/json")
                    .body(Body::from(body))
                    .unwrap(),
            )
            .await
            .unwrap()
    }

    async fn request_with_header(
        &self,
        method: &str,
        uri: &str,
        header: &'static str,
        value: &str,
    ) -> Response {
        self.router
            .clone()
            .call(
                Request::builder()
                    .method(method)
                    .uri(uri)
                    .header(header, value)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap()
    }

    async fn export(&self) -> Vec<u8> {
        let response = self.request("GET", "/api/export", Vec::new()).await;
        assert_eq!(response.status(), StatusCode::OK);
        to_bytes(response.into_body(), ROUTING_RULE_BODY_LIMIT_BYTES)
            .await
            .unwrap()
            .to_vec()
    }

    async fn snapshots(&self) -> (Value, Value) {
        (
            serde_json::to_value(&*self.app.db.read().await).unwrap(),
            serde_json::to_value(model::load_database(&self.app.config_dir).unwrap()).unwrap(),
        )
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.app.config_dir);
    }
}

mod secret_path {
    use super::*;
    use crate::model::ClientGroup;
    use crate::web_assets::{CLIENT_SUB_HTML, GROUP_SUB_HTML, LOGIN_HTML, PANEL_HTML};

    const SECRET: &str = "sup3rsecret";
    const SUB_UUID: &str = "capabilityuuid";
    const GROUP_UUID: &str = "groupcapabilityuuid";

    async fn body_of(response: Response) -> String {
        String::from_utf8_lossy(&to_bytes(response.into_body(), 1 << 20).await.unwrap())
            .into_owned()
    }

    fn db_with_capabilities() -> Database {
        let mut db = Database::default();
        db.passwords.insert(
            "client-password".to_owned(),
            PasswordEntry {
                ip: "10.66.67.2".to_owned(),
                group_id: "group-1".to_owned(),
                sub_uuid: SUB_UUID.to_owned(),
                ..Default::default()
            },
        );
        db.groups.insert(
            "group-1".to_owned(),
            ClientGroup {
                id: "group-1".to_owned(),
                name: "Группа".to_owned(),
                sub_uuid: GROUP_UUID.to_owned(),
                ..Default::default()
            },
        );
        db
    }

    /// Корень не должен отдавать ни одного PWA-ассета панели: в манифесте
    /// подставлен секретный префикс, и один анонимный GET раскрывал бы путь;
    /// иконки и `qrcode.min.js` по известным путям выдавали бы саму панель.
    #[tokio::test]
    async fn root_serves_no_panel_assets() {
        let target = Fixture::new_with_secret(Database::default(), SECRET).await;

        for uri in [
            "/manifest.webmanifest",
            "/sw.js",
            "/pwa-icon.svg",
            "/pwa-icon-192.png",
            "/pwa-icon-512.png",
            "/qrcode.min.js",
            "/favicon.ico",
        ] {
            let response = target
                .request_with_header("GET", uri, "user-agent", "bot")
                .await;
            let body = body_of(response).await;
            assert!(body.is_empty(), "{uri} из корня отдаёт контент: {body}");
        }
    }

    #[tokio::test]
    async fn panel_assets_are_served_under_the_secret_prefix() {
        let target = Fixture::new_with_secret(Database::default(), SECRET).await;

        let uri = format!("/{SECRET}/manifest.webmanifest");
        let response = target
            .request_with_header("GET", &uri, "user-agent", "panel")
            .await;
        assert_eq!(response.status(), StatusCode::OK);
        let body = body_of(response).await;
        assert!(body.contains(&format!("\"start_url\": \"/{SECRET}/\"")));
        assert!(body.contains(&format!("\"scope\": \"/{SECRET}/\"")));

        for (uri, marker) in [
            (format!("/{SECRET}/pwa-icon.svg"), "<svg"),
            (format!("/{SECRET}/qrcode.min.js"), "var qrcode"),
            (format!("/{SECRET}/sw.js"), "CACHE_NAME"),
        ] {
            let response = target
                .request_with_header("GET", &uri, "user-agent", "panel")
                .await;
            assert_eq!(response.status(), StatusCode::OK, "{uri}");
            let body = body_of(response).await;
            assert!(body.contains(marker), "{uri} не отдаёт свой payload");
        }
    }

    /// Ассеты страниц подписки живут под capability-UUID: корень закрыт, а
    /// неизвестный UUID не должен выдавать панель отдачей иконки.
    #[tokio::test]
    async fn subscription_assets_require_a_known_capability_uuid() {
        let target = Fixture::new_with_secret(db_with_capabilities(), SECRET).await;

        for (uri, marker) in [
            (format!("/{SUB_UUID}/pwa-icon.svg"), "<svg"),
            (format!("/{SUB_UUID}/pwa-icon-192.png"), "PNG"),
            (format!("/{SUB_UUID}/qrcode.min.js"), "var qrcode"),
            (format!("/{GROUP_UUID}/qrcode.min.js"), "var qrcode"),
        ] {
            let response = target
                .request_with_header("GET", &uri, "user-agent", "browser")
                .await;
            assert_eq!(response.status(), StatusCode::OK, "{uri}");
            let body = body_of(response).await;
            assert!(body.contains(marker), "{uri} не отдаёт свой payload");
        }

        let unknown = "/deadbeefdeadbeef/qrcode.min.js".to_owned();
        let response = target
            .request_with_header("GET", &unknown, "user-agent", "bot")
            .await;
        assert!(
            body_of(response).await.is_empty(),
            "{unknown} не должен отдаваться"
        );
    }

    /// Панель раздаёт ссылку без завершающего слэша (`/{uuid}/sub`), поэтому
    /// относительный `../` разрешился бы в корень. Обе формы обязаны вести на
    /// capability-путь ассетов.
    #[tokio::test]
    async fn subscription_pages_link_assets_by_capability_path() {
        let target = Fixture::new_with_secret(db_with_capabilities(), SECRET).await;

        for (uri, base) in [
            (format!("/{SUB_UUID}/sub"), format!("/{SUB_UUID}")),
            (format!("/{SUB_UUID}/sub/"), format!("/{SUB_UUID}")),
            (format!("/{GROUP_UUID}/sub-group"), format!("/{GROUP_UUID}")),
            (
                format!("/{GROUP_UUID}/sub-group/"),
                format!("/{GROUP_UUID}"),
            ),
        ] {
            let response = target
                .request_with_header("GET", &uri, "user-agent", "browser")
                .await;
            assert_eq!(response.status(), StatusCode::OK, "{uri}");
            let body = body_of(response).await;
            for asset in ["qrcode.min.js", "pwa-icon.svg"] {
                assert!(
                    body.contains(&format!("\"{base}/{asset}\"")),
                    "{uri} не ссылается на {base}/{asset}"
                );
                assert!(
                    !body.contains(&format!("\"/{asset}\"")),
                    "{uri} ссылается на /{asset} из корня"
                );
            }
        }
    }

    /// Относительная ссылка на манифест разрешается только от пути со
    /// завершающим слэшем, поэтому `/{secret}` обязан редиректить — вместе с
    /// обычными заголовками безопасности.
    #[tokio::test]
    async fn secret_root_without_trailing_slash_redirects() {
        let target = Fixture::new_with_secret(Database::default(), SECRET).await;
        let uri = format!("/{SECRET}");
        let response = target
            .request_with_header("GET", &uri, "user-agent", "panel")
            .await;
        assert_eq!(response.status(), StatusCode::PERMANENT_REDIRECT);
        let headers = response.headers();
        assert_eq!(
            headers.get("location").unwrap().to_str().unwrap(),
            format!("/{SECRET}/")
        );
        for name in ["x-content-type-options", "strict-transport-security"] {
            assert!(headers.contains_key(name), "редирект без заголовка {name}");
        }
    }

    /// Ни один документ не должен ссылаться на PWA-ассеты от корня: такие
    /// запросы уходят до JS и раскрывали бы панель.
    #[test]
    fn documents_never_link_panel_assets_from_the_root() {
        for (name, document, assets) in [
            (
                "LOGIN_HTML",
                LOGIN_HTML.as_str(),
                &["manifest.webmanifest", "pwa-icon.svg", "pwa-icon-192.png"][..],
            ),
            (
                "PANEL_HTML",
                PANEL_HTML.as_str(),
                &[
                    "manifest.webmanifest",
                    "pwa-icon.svg",
                    "pwa-icon-192.png",
                    "qrcode.min.js",
                ][..],
            ),
        ] {
            for asset in assets {
                assert!(
                    document.contains(&format!("\"{asset}\"")),
                    "{name} не ссылается на {asset} относительно себя"
                );
                assert!(
                    !document.contains(&format!("\"/{asset}\"")),
                    "{name} ссылается на /{asset} из корня"
                );
            }
            for tag in ["href=\"/", "src=\"/"] {
                assert!(
                    !document.contains(tag),
                    "{name} содержит абсолютный от корня {tag}…"
                );
            }
        }

        for (name, document) in [
            ("CLIENT_SUB_HTML", CLIENT_SUB_HTML.as_str()),
            ("GROUP_SUB_HTML", GROUP_SUB_HTML.as_str()),
        ] {
            for asset in ["pwa-icon.svg", "qrcode.min.js"] {
                assert!(
                    document.contains(&format!("\"__ASSET_BASE__/{asset}\"")),
                    "{name} не подставляет capability-базу для {asset}"
                );
                assert!(
                    !document.contains(&format!("\"/{asset}\"")),
                    "{name} ссылается на /{asset} из корня"
                );
                assert!(
                    !document.contains(&format!("\"../{asset}\"")),
                    "{name} использует относительный ../ — на ссылке без слэша он уходит в корень"
                );
            }
        }
    }
}
