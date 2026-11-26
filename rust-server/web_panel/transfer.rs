use crate::log_event;
use crate::model;
use crate::model::ClientGroup;
use crate::model::PasswordEntry;
use crate::model::RoutingRule;
use crate::model::now;
use crate::web_panel::*;
use axum::Json;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::response::Response;
use serde::Deserialize;
use serde::Serialize;

/// Version 2: the owner is an ordinary `clients` entry, so there is no
/// separate `main_password` field any more.
#[derive(Serialize)]
pub(crate) struct ServerExportData {
    pub(crate) version: u32,
    pub(crate) exported_at: i64,
    pub(crate) dns: String,
    pub(crate) dns_provider: String,
    pub(crate) auto_restart_interval_hours: Option<u8>,
    pub(crate) vk_access_token: String,
    #[serde(default)]
    pub(crate) vk_access_token_user_id: String,
    pub(crate) vk_hashes: String,
    pub(crate) groups: std::collections::BTreeMap<String, ClientGroup>,
    pub(crate) routing_rules: std::collections::BTreeMap<String, RoutingRule>,
    pub(crate) clients: Vec<ExportClientEntry>,
}

/// Accepted import payload, v2. The owner is an ordinary `clients` entry;
/// unknown or future fields are ignored rather than rejected.
#[derive(Deserialize)]
pub(crate) struct ServerImportConfig {
    // Not used by the importer, but keeps the payload shape of a real export:
    // a body without it is rejected instead of importing an empty config.
    #[allow(dead_code)]
    pub(crate) exported_at: i64,
    #[serde(default)]
    pub(crate) dns: String,
    #[serde(default)]
    pub(crate) dns_provider: String,
    #[serde(default)]
    pub(crate) auto_restart_interval_hours: Option<u8>,
    #[serde(default)]
    pub(crate) vk_access_token: String,
    #[serde(default)]
    pub(crate) vk_access_token_user_id: String,
    #[serde(default)]
    pub(crate) vk_hashes: String,
    #[serde(default)]
    pub(crate) groups: std::collections::BTreeMap<String, ClientGroup>,
    #[serde(default)]
    pub(crate) routing_rules: std::collections::BTreeMap<String, RoutingRule>,
    #[serde(default)]
    pub(crate) clients: Vec<ExportClientEntry>,
}

#[derive(Serialize, Deserialize, Clone)]
pub(crate) struct ExportClientEntry {
    pub(crate) password: String,
    pub(crate) name: String,
    pub(crate) expires_at: i64,
    pub(crate) down_bytes: i64,
    pub(crate) up_bytes: i64,
    #[serde(default)]
    pub(crate) vk_hash: String,
    #[serde(default)]
    pub(crate) vk_hashes: String,
    #[serde(default)]
    pub(crate) vk_token_enabled: bool,
    #[serde(default)]
    pub(crate) ports: String,
    #[serde(default)]
    pub(crate) dtls_port: u16,
    #[serde(default)]
    pub(crate) local_port: u16,
    #[serde(default)]
    pub(crate) group_id: String,
    #[serde(default)]
    pub(crate) routing_rule_id: String,
    #[serde(default)]
    pub(crate) is_deactivated: bool,
    #[serde(default)]
    pub(crate) sub_uuid: String,
    #[serde(default)]
    pub(crate) workers: Option<u32>,
    #[serde(default)]
    pub(crate) ip: String,
}

pub(crate) async fn api_export_get(State(state): State<WebState>) -> impl IntoResponse {
    let db = state.app.db.read().await;
    let clients: Vec<ExportClientEntry> = db
        .passwords
        .iter()
        .map(|(pwd, entry)| ExportClientEntry {
            password: pwd.clone(),
            name: entry.name.clone(),
            expires_at: entry.expires_at,
            down_bytes: entry.down_bytes,
            up_bytes: entry.up_bytes,
            vk_hash: entry.vk_hash.clone(),
            vk_hashes: entry.vk_hashes.clone(),
            vk_token_enabled: entry.vk_token_enabled,
            ports: entry.ports.clone(),
            dtls_port: entry.dtls_port,
            local_port: entry.local_port,
            group_id: entry.group_id.clone(),
            routing_rule_id: entry.routing_rule_id.clone(),
            is_deactivated: entry.is_deactivated,
            sub_uuid: entry.sub_uuid.clone(),
            workers: entry.workers,
            ip: entry.ip.clone(),
        })
        .collect();
    let export = ServerExportData {
        version: 2,
        exported_at: now(),
        dns: db.dns.clone(),
        dns_provider: db.dns_provider.clone(),
        auto_restart_interval_hours: db.auto_restart_interval_hours,
        vk_access_token: db.vk_access_token.clone(),
        vk_access_token_user_id: db.vk_access_token_user_id.clone(),
        vk_hashes: db.vk_hashes.clone(),
        groups: db.groups.clone(),
        routing_rules: db.routing_rules.clone(),
        clients,
    };
    Json(export)
}

#[derive(Deserialize)]
pub(crate) struct ServerImportPayload {
    #[serde(default)]
    pub(crate) import_clients: Option<bool>,
    #[serde(default)]
    pub(crate) import_settings: Option<bool>,
    #[serde(default)]
    pub(crate) config: Option<serde_json::Value>,
    #[serde(flatten)]
    pub(crate) direct_config: serde_json::Map<String, serde_json::Value>,
}

pub(crate) async fn api_import_post(
    State(state): State<WebState>,
    Json(payload): Json<ServerImportPayload>,
) -> Response {
    let config_value = match payload.config {
        Some(config) => config,
        None => serde_json::Value::Object(payload.direct_config),
    };
    let Ok(export) = serde_json::from_value::<ServerImportConfig>(config_value) else {
        return (StatusCode::BAD_REQUEST, "invalid or empty config").into_response();
    };
    let do_clients = payload.import_clients.unwrap_or(true);
    let do_settings = payload.import_settings.unwrap_or(true);
    let mut added_clients = 0usize;
    let mut applied_dns = None;
    let mut applied_interval = None;

    for (rid, rule) in &export.routing_rules {
        if let Err(message) = crate::protocol::validate_routing_rule(rule) {
            log_event(
                &state.app,
                "WARN",
                "import",
                &format!("routing rule {rid} rejected: {message}"),
            );
            return (StatusCode::BAD_REQUEST, format!("правило {rid}: {message}")).into_response();
        }
    }

    let revision =
        {
            let mut db = state.app.db.write().await;
            if do_clients {
                for c in export.clients {
                    if !db.passwords.contains_key(&c.password) {
                        let sub_uuid = if c.sub_uuid.trim().is_empty() {
                            model::random_uuid_v4()
                        } else {
                            c.sub_uuid
                        };
                        let ip = model::import_ip(&db, &c.ip);
                        let client_name = if c.name.trim().is_empty()
                            || model::is_client_name_taken(
                                model::existing_client_names_in_scope(&db, &c.group_id, None),
                                &c.name,
                            ) {
                            model::generate_unique_client_name(
                                model::existing_client_names_in_scope(&db, &c.group_id, None),
                            )
                        } else {
                            c.name
                        };
                        db.passwords.insert(
                            c.password.clone(),
                            PasswordEntry {
                                ip,
                                expires_at: c.expires_at,
                                down_bytes: c.down_bytes,
                                up_bytes: c.up_bytes,
                                last_online: 0,
                                vk_hash: c.vk_hash,
                                ports: c.ports,
                                is_deactivated: c.is_deactivated,
                                name: client_name,
                                vk_hashes: c.vk_hashes,
                                vk_token_enabled: c.vk_token_enabled,
                                dtls_port: c.dtls_port,
                                wg_port: 0,
                                local_port: c.local_port,
                                group_id: c.group_id,
                                routing_rule_id: c.routing_rule_id,
                                sub_uuid,
                                workers: c.workers.filter(|&w| (3..=100).contains(&w)),
                            },
                        );
                        added_clients += 1;
                    }
                }
                for (gid, g) in export.groups {
                    db.groups.entry(gid).or_insert(g);
                }
                for (rid, r) in export.routing_rules {
                    db.routing_rules.entry(rid).or_insert(r);
                }
            }
            if do_settings {
                if !export.dns.is_empty() {
                    let (dns, dns_provider) =
                        normalize_dns_selection(&export.dns, &export.dns_provider);
                    applied_dns = (db.dns != dns).then(|| dns.clone());
                    db.dns = dns;
                    db.dns_provider = dns_provider;
                }
                if let Some(hours) = export.auto_restart_interval_hours {
                    db.set_auto_restart_interval_hours(hours);
                    applied_interval = Some(hours);
                }
                if !export.vk_access_token.is_empty() {
                    db.vk_access_token = export.vk_access_token;
                }
                if !export.vk_access_token_user_id.is_empty() {
                    db.vk_access_token_user_id = export.vk_access_token_user_id;
                }
                if !export.vk_hashes.is_empty() {
                    db.vk_hashes = export.vk_hashes;
                }
            }
            state.app.db_persistence.submit(db.clone())
        };

    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    if let Some(dns) = applied_dns {
        *state.app.dns.write().await = dns;
        let _ = crate::protocol::broadcast_tunnel_configuration(&state.app).await;
    }
    if let Some(hours) = applied_interval {
        state.app.auto_restart_interval_tx.send_replace(hours);
    }
    let _ = refresh_credentials_report(&state).await;

    Json(serde_json::json!({
        "ok": true,
        "added_clients": added_clients,
        "settings_imported": do_settings
    }))
    .into_response()
}
