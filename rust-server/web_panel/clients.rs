use crate::model;
use crate::model::PasswordEntry;
use crate::model::is_expired;
use crate::model::now;
use crate::model::random_password;
use crate::protocol;
use crate::web_panel::*;
use axum::Json;
use axum::extract::Path;
use axum::extract::Query;
use axum::extract::State;
use axum::http::HeaderMap;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::response::Response;
use serde::Deserialize;
use serde::Serialize;
use std::sync::Arc;
use std::sync::atomic::Ordering;

#[derive(Deserialize)]
pub(crate) struct CreateClientRequest {
    pub(crate) name: String,
    pub(crate) days: i64,
    #[serde(default)]
    pub(crate) workers: Option<u32>,
    #[serde(default)]
    pub(crate) password: Option<String>,
    #[serde(default)]
    pub(crate) hash: String,
    #[serde(default)]
    pub(crate) vk_token: bool,
    #[serde(default, alias = "dtls_port")]
    pub(crate) peer_port: Option<u16>,
    #[serde(default)]
    pub(crate) local_port: Option<u16>,
    #[serde(default)]
    pub(crate) group_id: Option<String>,
}

#[derive(Deserialize)]
pub(crate) struct ExtendClientRequest {
    pub(crate) days: i64,
}

#[derive(Serialize)]
pub(crate) struct ClientInfo {
    pub(crate) password: String,
    pub(crate) down: i64,
    pub(crate) up: i64,
    pub(crate) expires: i64,
    pub(crate) active: bool,
    pub(crate) active_sessions: usize,
    pub(crate) last_online: i64,
    pub(crate) presence_window_secs: i64,
    pub(crate) server_now: i64,
    pub(crate) vk_hash: String,
    pub(crate) ports: String,
    pub(crate) ip: String,
    pub(crate) name: String,
    pub(crate) vk_hashes: String,
    pub(crate) vk_token: bool,
    pub(crate) peer_port: u16,
    pub(crate) local_port: u16,
    #[serde(default)]
    pub(crate) group_id: String,
    #[serde(default)]
    pub(crate) routing_rule_id: String,
    #[serde(default)]
    pub(crate) link: String,
    #[serde(default)]
    pub(crate) sub_uuid: String,
    #[serde(default)]
    pub(crate) workers: Option<u32>,
}

#[derive(Deserialize, Default)]
pub(crate) struct ClientListQuery {
    #[serde(default)]
    pub(crate) group_id: Option<String>,
    #[serde(default)]
    pub(crate) active: Option<bool>,
    #[serde(default)]
    pub(crate) search: Option<String>,
}

pub(crate) fn live_presence(
    sessions: &dashmap::DashMap<u64, Arc<crate::protocol::Session>>,
    password: &str,
) -> (usize, u64) {
    if password.is_empty() {
        return (0, 0);
    }
    let mut count = 0;
    let mut last_inbound = 0;
    for entry in sessions.iter() {
        let session = entry.value();
        if session.password == password {
            count += 1;
            last_inbound = last_inbound.max(session.last_inbound.load(Ordering::Relaxed));
        }
    }
    (count, last_inbound)
}

pub(crate) fn count_active_sessions(
    sessions: &dashmap::DashMap<u64, Arc<crate::protocol::Session>>,
    password: &str,
) -> usize {
    live_presence(sessions, password).0
}

pub(crate) async fn clients_get(
    State(state): State<WebState>,
    headers: HeaderMap,
    Query(query): Query<ClientListQuery>,
) -> impl IntoResponse {
    let db = state.app.db.read().await;
    let host = resolve_client_host(&headers, &state).await;
    let current = now();

    let mut list: Vec<ClientInfo> = db
        .passwords
        .iter()
        .map(|(password, entry)| {
            let client_peer_port = if entry.dtls_port == 0 {
                state.app.listen.port()
            } else {
                entry.dtls_port
            };
            let (effective_token, effective_user_id) =
                effective_vk_credentials(&entry.group_id, &db);
            let (active_sessions, live) = live_presence(&state.app.sessions, password);
            let (last_online, _) = protocol::presence_view(entry.last_online, live, current);
            ClientInfo {
                password: password.clone(),
                down: entry.down_bytes,
                up: entry.up_bytes,
                expires: entry.expires_at,
                active: !entry.is_deactivated && !is_expired(entry),
                active_sessions,
                last_online,
                presence_window_secs: protocol::PRESENCE_WINDOW_SECS,
                server_now: current,
                vk_hash: entry.vk_hash.clone(),
                ports: entry.ports.clone(),
                ip: entry.ip.clone(),
                name: entry.name.clone(),
                vk_hashes: entry.vk_hashes.clone(),
                vk_token: entry.vk_token_enabled,
                peer_port: client_peer_port,
                local_port: entry.local_port,
                group_id: entry.group_id.clone(),
                routing_rule_id: entry.routing_rule_id.clone(),
                link: make_entry_link(
                    &host,
                    client_peer_port,
                    password,
                    entry,
                    effective_token,
                    effective_user_id,
                ),
                sub_uuid: entry.sub_uuid.clone(),
                workers: entry.workers,
            }
        })
        .collect();
    if let Some(ref gid) = query.group_id {
        let g = gid.trim();
        if g == "root" || g == "ungrouped" {
            list.retain(|c| c.group_id.is_empty());
        } else {
            list.retain(|c| c.group_id == g);
        }
    }
    if let Some(act) = query.active {
        list.retain(|c| c.active == act);
    }
    if let Some(ref s) = query.search {
        let q = s.trim().to_lowercase();
        if !q.is_empty() {
            list.retain(|c| {
                c.name.to_lowercase().contains(&q) || c.password.to_lowercase().contains(&q)
            });
        }
    }

    Json(list)
}

pub(crate) async fn clients_create(
    State(state): State<WebState>,
    headers: HeaderMap,
    Json(request): Json<CreateClientRequest>,
) -> Response {
    if let Err(message) = validate_client_name(&request.name) {
        return (StatusCode::BAD_REQUEST, message).into_response();
    }
    if request.days < 0 || request.days > 3650 {
        return (StatusCode::BAD_REQUEST, "days must be 0..3650").into_response();
    }
    let vk_hashes = match normalize_vk_hashes(&request.hash) {
        Ok(value) => value,
        Err(message) => return (StatusCode::BAD_REQUEST, message).into_response(),
    };
    if request.vk_token && !vk_hashes.is_empty() {
        return (StatusCode::BAD_REQUEST, "choose VK hashes or VK token").into_response();
    }

    let default_peer_port = state.app.listen.port();
    let peer_port = request.peer_port.unwrap_or(default_peer_port);
    let local_port = request.local_port.unwrap_or(0);
    let ports = format!("{},0,{}", peer_port, local_port);
    let group_id = request.group_id.unwrap_or_default();
    let validated_workers = match validate_workers(request.workers) {
        Ok(w) => w,
        Err(msg) => return (StatusCode::BAD_REQUEST, msg).into_response(),
    };

    let host = resolve_client_host(&headers, &state).await;

    let (password, expires, revision, link, sub_uuid) = {
        let mut db = state.app.db.write().await;
        if request.vk_token && db.vk_access_token.is_empty() {
            return (StatusCode::BAD_REQUEST, "configure a VK token first").into_response();
        }
        db.passwords.retain(|_, entry| !is_expired(entry));

        let client_name = if request.name.trim().is_empty() {
            model::generate_unique_client_name(model::existing_client_names_in_scope(
                &db, &group_id, None,
            ))
        } else {
            let trimmed = request.name.trim().to_string();
            if model::is_client_name_taken(
                model::existing_client_names_in_scope(&db, &group_id, None),
                &trimmed,
            ) {
                return (
                    StatusCode::BAD_REQUEST,
                    "Участник с таким именем уже существует",
                )
                    .into_response();
            }
            trimmed
        };

        let password = if let Some(ref custom_pw) = request.password {
            let pw = custom_pw.trim();
            if pw.is_empty() || pw.len() > 128 {
                return (
                    StatusCode::BAD_REQUEST,
                    "password must be 1..128 characters",
                )
                    .into_response();
            }
            if db.passwords.contains_key(pw) {
                return (StatusCode::BAD_REQUEST, "password already exists").into_response();
            }
            pw.to_string()
        } else {
            let candidate = (0..64)
                .map(|_| random_password())
                .find(|c| !db.passwords.contains_key(c));
            let Some(p) = candidate else {
                return StatusCode::INTERNAL_SERVER_ERROR.into_response();
            };
            p
        };

        let expires = if request.days == 0 {
            0
        } else {
            now().saturating_add(request.days.saturating_mul(86400))
        };
        let sub_uuid = model::random_uuid_v4();
        let ip = model::get_next_ip(&db).unwrap_or_default();
        db.passwords.insert(
            password.clone(),
            PasswordEntry {
                ip,
                expires_at: expires,
                down_bytes: 0,
                up_bytes: 0,
                last_online: 0,
                vk_hash: vk_hashes.clone(),
                ports,
                is_deactivated: false,
                name: client_name,
                vk_hashes: vk_hashes.clone(),
                vk_token_enabled: request.vk_token,
                dtls_port: peer_port,
                wg_port: 0,
                local_port,
                group_id: group_id.clone(),
                routing_rule_id: String::new(),
                sub_uuid: sub_uuid.clone(),
                workers: validated_workers,
            },
        );
        let (effective_token, effective_user_id) = effective_vk_credentials(&group_id, &db);
        let link = make_client_link(
            &host,
            peer_port,
            &password,
            &vk_hashes,
            request.vk_token,
            effective_token,
            effective_user_id,
        );
        let revision = state.app.db_persistence.submit(db.clone());
        (password, expires, revision, link, sub_uuid)
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    let reload_error = refresh_credentials_report(&state).await.err();

    Json(serde_json::json!({
        "password": password,
        "expires": expires,
        "peer_port": peer_port,
        "local_port": local_port,
        "vk_hashes": vk_hashes,
        "vk_token": request.vk_token,
        "group_id": group_id,
        "workers": validated_workers.unwrap_or(0),
        "reload_error": reload_error,
        "link": link,
        "sub_uuid": sub_uuid,
    }))
    .into_response()
}

pub(crate) async fn client_update(
    State(state): State<WebState>,
    Path(password): Path<String>,
    Json(request): Json<CreateClientRequest>,
) -> Response {
    if let Err(message) = validate_client_name(&request.name) {
        return (StatusCode::BAD_REQUEST, message).into_response();
    }
    if request.days < 0 || request.days > 3650 {
        return (StatusCode::BAD_REQUEST, "days must be 0..3650").into_response();
    }
    let vk_hashes = match normalize_vk_hashes(&request.hash) {
        Ok(value) => value,
        Err(message) => return (StatusCode::BAD_REQUEST, message).into_response(),
    };
    if request.vk_token && !vk_hashes.is_empty() {
        return (StatusCode::BAD_REQUEST, "choose VK hashes or VK token").into_response();
    }
    let validated_workers = match validate_workers(request.workers) {
        Ok(w) => w,
        Err(msg) => return (StatusCode::BAD_REQUEST, msg).into_response(),
    };

    let (revision, affected) = {
        let mut db = state.app.db.write().await;
        if request.vk_token && db.vk_access_token.is_empty() {
            return (StatusCode::BAD_REQUEST, "configure a VK token first").into_response();
        }
        let current_group_id = match db.passwords.get(&password) {
            Some(e) => e.group_id.clone(),
            None => return StatusCode::NOT_FOUND.into_response(),
        };
        let target_group_id = request.group_id.as_deref().unwrap_or(&current_group_id);
        let scope_names =
            model::existing_client_names_in_scope(&db, target_group_id, Some(&password));
        let client_name = if request.name.trim().is_empty() {
            model::generate_unique_client_name(&scope_names)
        } else {
            let trimmed = request.name.trim().to_string();
            if model::is_client_name_taken(&scope_names, &trimmed) {
                return (
                    StatusCode::BAD_REQUEST,
                    "Участник с таким именем уже существует",
                )
                    .into_response();
            }
            trimmed
        };

        let entry = db.passwords.get_mut(&password).unwrap();

        // Смена группы меняет разрешённое правило только у клиента без
        // собственного: групповое правило стоит ниже собственного в
        // порядке `resolve_routing_rule`. Если собственного правила нет и
        // группа другая — старое решение больше не действительно, и
        // клиенту нужен новый `GETCONF`.
        let rule_binding_changed =
            entry.routing_rule_id.is_empty() && target_group_id != current_group_id;
        let default_peer_port = state.app.listen.port();
        let fallback_port = if entry.dtls_port == 0 {
            default_peer_port
        } else {
            entry.dtls_port
        };
        let peer_port = request.peer_port.unwrap_or(fallback_port);
        let local_port = request.local_port.unwrap_or(0);

        entry.name = client_name;
        entry.expires_at = if request.days == 0 {
            0
        } else {
            now().saturating_add(request.days.saturating_mul(86400))
        };
        entry.vk_hash = vk_hashes.clone();
        entry.vk_hashes = vk_hashes;
        entry.vk_token_enabled = request.vk_token;
        entry.dtls_port = peer_port;
        entry.wg_port = 0;
        entry.local_port = local_port;
        entry.ports = format!("{},0,{}", peer_port, local_port);
        if let Some(ref g_id) = request.group_id {
            entry.group_id = g_id.clone();
        }
        entry.workers = validated_workers;
        let affected = if rule_binding_changed {
            vec![password.clone()]
        } else {
            Vec::new()
        };
        (state.app.db_persistence.submit(db.clone()), affected)
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    notify_routing_changed_or_log(&state, &affected).await;
    if let Err(message) = refresh_credentials_report(&state).await {
        let warning = format!("saved but reload failed: {message}");
        return (StatusCode::OK, [("x-csqtt-reload-error", warning)], "saved").into_response();
    }
    StatusCode::OK.into_response()
}

pub(crate) async fn client_toggle(
    State(state): State<WebState>,
    Path(password): Path<String>,
) -> Response {
    let revision = {
        let mut db = state.app.db.write().await;
        let Some(entry) = db.passwords.get_mut(&password) else {
            return StatusCode::NOT_FOUND.into_response();
        };
        entry.is_deactivated = !entry.is_deactivated;
        state.app.db_persistence.submit(db.clone())
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }

    crate::protocol::drop_password_sessions(&state.app, &password).await;
    if let Err(message) = refresh_credentials_report(&state).await {
        let warning = format!("saved but reload failed: {message}");
        return (StatusCode::OK, [("x-csqtt-reload-error", warning)], "saved").into_response();
    }

    StatusCode::OK.into_response()
}

pub(crate) async fn client_unbind(
    State(state): State<WebState>,
    Path(password): Path<String>,
) -> Response {
    // Уведомление о смене правила здесь не нужно и не могло бы сработать:
    // привязка правила этим handler'ом не меняется, а `drop_password_sessions`
    // уничтожает ровно те сессии, которым notice был бы адресован. Клиент
    // переподключится и получит актуальное решение обычным `GETCONF`.
    crate::protocol::drop_password_sessions(&state.app, &password).await;
    crate::protocol::forget_idle_password_epoch(&state.app, &password);
    StatusCode::OK.into_response()
}

pub(crate) async fn client_delete(
    State(state): State<WebState>,
    Path(password): Path<String>,
) -> Response {
    let revision = {
        let mut db = state.app.db.write().await;
        if db.passwords.remove(&password).is_none() {
            return StatusCode::NOT_FOUND.into_response();
        };
        state.app.derived_keys.remove(&password);
        state.app.db_persistence.submit(db.clone())
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }

    crate::protocol::drop_password_sessions(&state.app, &password).await;
    if let Err(message) = refresh_credentials_report(&state).await {
        let warning = format!("saved but reload failed: {message}");
        return (StatusCode::OK, [("x-csqtt-reload-error", warning)], "saved").into_response();
    }

    StatusCode::OK.into_response()
}

pub(crate) async fn client_get(
    State(state): State<WebState>,
    headers: HeaderMap,
    Path(password): Path<String>,
) -> Response {
    let db = state.app.db.read().await;
    let host = resolve_client_host(&headers, &state).await;
    let current = now();

    if let Some(entry) = db.passwords.get(&password) {
        let (active_sessions, live) = live_presence(&state.app.sessions, &password);
        let (last_online, _) = protocol::presence_view(entry.last_online, live, current);
        let client_peer_port = if entry.dtls_port == 0 {
            state.app.listen.port()
        } else {
            entry.dtls_port
        };
        let (effective_token, effective_user_id) = effective_vk_credentials(&entry.group_id, &db);
        let info = ClientInfo {
            password: password.clone(),
            down: entry.down_bytes,
            up: entry.up_bytes,
            expires: entry.expires_at,
            active: !entry.is_deactivated && !is_expired(entry),
            active_sessions,
            last_online,
            presence_window_secs: protocol::PRESENCE_WINDOW_SECS,
            server_now: current,
            vk_hash: entry.vk_hash.clone(),
            ports: entry.ports.clone(),
            ip: if entry.ip.is_empty() {
                "10.66.67.3".to_string()
            } else {
                entry.ip.clone()
            },
            name: entry.name.clone(),
            vk_hashes: entry.vk_hashes.clone(),
            vk_token: entry.vk_token_enabled,
            peer_port: client_peer_port,
            local_port: entry.local_port,
            group_id: entry.group_id.clone(),
            routing_rule_id: entry.routing_rule_id.clone(),
            link: make_entry_link(
                &host,
                client_peer_port,
                &password,
                entry,
                effective_token,
                effective_user_id,
            ),
            sub_uuid: entry.sub_uuid.clone(),
            workers: entry.workers,
        };
        return Json(info).into_response();
    }

    (StatusCode::NOT_FOUND, "client not found").into_response()
}

pub(crate) async fn client_reset_traffic(
    State(state): State<WebState>,
    Path(password): Path<String>,
) -> Response {
    let result = {
        let mut db = state.app.db.write().await;
        if let Some(entry) = db.passwords.get_mut(&password) {
            entry.up_bytes = 0;
            entry.down_bytes = 0;
            for s in state.app.sessions.iter() {
                if s.value().password == password {
                    s.value().up_bytes.store(0, Ordering::Relaxed);
                    s.value().down_bytes.store(0, Ordering::Relaxed);
                }
            }
            let revision = state.app.db_persistence.submit(db.clone());
            Some(revision)
        } else {
            None
        }
    };

    let Some(revision) = result else {
        return (StatusCode::NOT_FOUND, "client not found").into_response();
    };

    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    Json(serde_json::json!({ "ok": true })).into_response()
}

pub(crate) async fn client_extend(
    State(state): State<WebState>,
    Path(password): Path<String>,
    Json(request): Json<ExtendClientRequest>,
) -> Response {
    if request.days <= 0 || request.days > 3650 {
        return (StatusCode::BAD_REQUEST, "days must be 1..3650").into_response();
    }
    let current = now();
    let result = {
        let mut db = state.app.db.write().await;
        if let Some(entry) = db.passwords.get_mut(&password) {
            let base = if entry.expires_at > current {
                entry.expires_at
            } else {
                current
            };
            let new_expires = base.saturating_add(request.days.saturating_mul(86400));
            entry.expires_at = new_expires;
            let revision = state.app.db_persistence.submit(db.clone());
            Some((new_expires, revision))
        } else {
            None
        }
    };

    let Some((expires, revision)) = result else {
        return (StatusCode::NOT_FOUND, "client not found").into_response();
    };

    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    let _ = refresh_credentials_report(&state).await;
    Json(serde_json::json!({ "ok": true, "expires": expires })).into_response()
}
