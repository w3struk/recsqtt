use crate::model;
use crate::model::ClientGroup;
use crate::model::is_expired;
use crate::model::now;
use crate::model::random_token;
use crate::protocol;
use crate::web_panel::*;
use axum::Json;
use axum::extract::Path;
use axum::extract::State;
use axum::http::HeaderMap;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::response::Response;
use serde::Deserialize;
use serde::Serialize;

pub(crate) async fn group_clients_get(
    State(state): State<WebState>,
    Path(id): Path<String>,
    headers: HeaderMap,
) -> Response {
    let db = state.app.db.read().await;
    if !db.groups.contains_key(&id) {
        return (
            StatusCode::NOT_FOUND,
            Json(serde_json::json!({ "error": "group not found" })),
        )
            .into_response();
    }
    let host = resolve_client_host(&headers, &state).await;
    let current = now();
    let (effective_vk_token, effective_vk_user_id) = effective_vk_credentials(&id, &db);
    let list: Vec<ClientInfo> = db
        .passwords
        .iter()
        .filter(|(_, entry)| entry.group_id == id)
        .map(|(password, entry)| {
            let client_peer_port = if entry.dtls_port == 0 {
                state.app.listen.port()
            } else {
                entry.dtls_port
            };
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
                    password,
                    entry,
                    effective_vk_token,
                    effective_vk_user_id,
                ),
                sub_uuid: entry.sub_uuid.clone(),
                workers: entry.workers,
            }
        })
        .collect();
    Json(list).into_response()
}

#[derive(Serialize)]
pub(crate) struct GroupInfo {
    pub(crate) id: String,
    pub(crate) name: String,
    pub(crate) routing_rule_id: String,
    pub(crate) sub_uuid: String,
    pub(crate) vk_token_user_id: String,
    pub(crate) member_count: usize,
    pub(crate) up: i64,
    pub(crate) down: i64,
    pub(crate) active_sessions: usize,
}

pub(crate) async fn groups_get(State(state): State<WebState>) -> Response {
    let db = state.app.db.read().await;
    let mut list = Vec::new();
    for group in db.groups.values() {
        let mut member_count = 0;
        let mut up = 0i64;
        let mut down = 0i64;
        let mut active_sessions = 0;
        for (_pass, p_entry) in &db.passwords {
            if p_entry.group_id == group.id {
                member_count += 1;
                up += p_entry.up_bytes;
                down += p_entry.down_bytes;
                active_sessions += count_active_sessions(&state.app.sessions, _pass);
            }
        }
        list.push(GroupInfo {
            id: group.id.clone(),
            name: group.name.clone(),
            routing_rule_id: group.routing_rule_id.clone(),
            sub_uuid: group.sub_uuid.clone(),
            vk_token_user_id: group.vk_token_user_id.clone().unwrap_or_default(),
            member_count,
            up,
            down,
            active_sessions,
        });
    }
    Json(list).into_response()
}

#[derive(Deserialize)]
pub(crate) struct CreateGroupRequest {
    pub(crate) name: String,
    #[serde(default)]
    pub(crate) vk_token_user_id: Option<String>,
}

pub(crate) async fn groups_create(
    State(state): State<WebState>,
    Json(request): Json<CreateGroupRequest>,
) -> Response {
    let name = request.name.trim();
    if name.is_empty() || name.len() > 64 {
        return (StatusCode::BAD_REQUEST, "invalid group name").into_response();
    }
    let vk_token_user_id = match request.vk_token_user_id.as_deref() {
        Some(raw) => match normalize_vk_user_id(raw) {
            Ok(user_id) => user_id,
            Err(message) => return (StatusCode::BAD_REQUEST, message).into_response(),
        },
        None => None,
    };
    let id = format!("grp_{}", random_token(8));
    let group = ClientGroup {
        id: id.clone(),
        name: name.to_string(),
        routing_rule_id: String::new(),
        sub_uuid: model::random_uuid_v4(),
        workers: None,
        vk_token: None,
        vk_token_user_id,
        vk_hashes: None,
    };
    let revision = {
        let mut db = state.app.db.write().await;
        db.groups.insert(id, group.clone());
        state.app.db_persistence.submit(db.clone())
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    Json(group).into_response()
}

#[derive(Deserialize)]
pub(crate) struct UpdateGroupRequest {
    pub(crate) name: Option<String>,
    pub(crate) routing_rule_id: Option<String>,
    #[serde(default)]
    pub(crate) vk_token_user_id: Option<String>,
}

pub(crate) async fn groups_update(
    State(state): State<WebState>,
    Path(id): Path<String>,
    Json(request): Json<UpdateGroupRequest>,
) -> Response {
    let requested_vk_token_user_id = match request.vk_token_user_id.as_deref() {
        Some(raw) => match normalize_vk_user_id(raw) {
            Ok(user_id) => Some(user_id),
            Err(message) => return (StatusCode::BAD_REQUEST, message).into_response(),
        },
        None => None,
    };
    let (revision, affected) = {
        let mut db = state.app.db.write().await;
        // Снимок «до» строится по ПРЕЖНЕМУ значению привязки: после мутации
        // группа уже не указывает на старое правило, и union собрать было бы
        // нечем. Отдельного прохода по клиентам ради этого не делаем —
        // пересохранение имени или лимита воркеров никого не будит.
        let Some(previous_rule_id) = db
            .groups
            .get(&id)
            .map(|group| group.routing_rule_id.clone())
        else {
            return StatusCode::NOT_FOUND.into_response();
        };
        let next_rule_id = request.routing_rule_id.clone();
        let binding_changed = next_rule_id
            .as_ref()
            .is_some_and(|next| next != &previous_rule_id);
        let before = if binding_changed {
            group_affected_passwords(&db, &id)
        } else {
            Vec::new()
        };

        {
            let Some(group) = db.groups.get_mut(&id) else {
                return StatusCode::NOT_FOUND.into_response();
            };
            if let Some(name) = request.name {
                let n = name.trim();
                if n.is_empty() || n.len() > 64 {
                    return (StatusCode::BAD_REQUEST, "invalid group name").into_response();
                }
                group.name = n.to_string();
            }
            if let Some(r_id) = request.routing_rule_id {
                group.routing_rule_id = r_id;
            }
            if let Some(user_id) = requested_vk_token_user_id {
                group.vk_token_user_id = user_id;
            }
        }
        // Union «до» и «после»: участники группы попадают в оба набора (их
        // ожидание определяется членством), а main — только в «до», потому что
        // после снятия привязки его правило уже не совпадает с групповым. Без
        // «до» main остался бы живым туннелем со снятым правилом.
        let affected = if binding_changed {
            merge_affected_passwords(&before, &group_affected_passwords(&db, &id))
        } else {
            Vec::new()
        };
        (state.app.db_persistence.submit(db.clone()), affected)
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    notify_routing_changed_or_log(&state, &affected).await;
    let _ = crate::protocol::broadcast_tunnel_configuration(&state.app).await;
    StatusCode::OK.into_response()
}

pub(crate) async fn groups_delete(
    State(state): State<WebState>,
    Path(id): Path<String>,
) -> Response {
    let (revision, deleted_passwords) = {
        let mut db = state.app.db.write().await;
        if db.groups.remove(&id).is_none() {
            return StatusCode::NOT_FOUND.into_response();
        }
        let mut deleted = Vec::new();
        db.passwords.retain(|pwd, entry| {
            if entry.group_id == id {
                deleted.push(pwd.clone());
                false
            } else {
                true
            }
        });
        for pwd in &deleted {
            state.app.derived_keys.remove(pwd);
        }
        let rev = state.app.db_persistence.submit(db.clone());
        (rev, deleted)
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    // Уведомление о смене правила здесь не нужно и не могло бы сработать:
    // участники группы удаляются вместе с ней, а `drop_password_sessions`
    // уничтожает ровно те сессии, которым notice был бы адресован. Выживших
    // клиентов, чьё разрешённое правило изменилось бы, нет: через группу
    // резолвятся только её участники (все удалены). Удалённые клиенты при
    // переподключении не пройдут аутентификацию; остальные получат актуальное
    // решение обычным `GETCONF`. Тот же случай, что `client_unbind`/`client_delete`.
    for pwd in &deleted_passwords {
        crate::protocol::drop_password_sessions(&state.app, pwd).await;
    }
    if let Err(message) = refresh_credentials_report(&state).await {
        let warning = format!("saved but reload failed: {message}");
        return (StatusCode::OK, [("x-csqtt-reload-error", warning)], "saved").into_response();
    }
    StatusCode::OK.into_response()
}
