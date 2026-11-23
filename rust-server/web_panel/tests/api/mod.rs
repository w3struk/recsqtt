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
    async fn new(mut db: Database) -> Self {
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
            web_secret_path: String::new(),
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
