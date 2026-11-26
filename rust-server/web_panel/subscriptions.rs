use crate::App;
use crate::model;
use crate::web_assets::CLIENT_SUB_HTML;
use crate::web_assets::GROUP_SUB_HTML;
use crate::web_panel::*;
use axum::Json;
use axum::extract::Extension;
use axum::extract::Path;
use axum::extract::State;
use axum::http::HeaderMap;
use axum::http::StatusCode;
use axum::response::Html;
use axum::response::IntoResponse;
use axum::response::Response;
use serde::Serialize;
use std::sync::Arc;

pub(crate) fn html_escape(input: &str) -> String {
    input
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&#39;")
}

/// Тип capability-UUID: по нему JSON-эндпоинт выбирает страницу подписки.
#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum SubUuidKind {
    Client,
    Group,
}

/// Единственное определение «подписка известна»: его используют JSON-эндпоинт,
/// стелс-фильтр plain-HTTP и ассеты страниц подписки. Одна блокировка на
/// проверку — прежние копии брали её дважды.
pub(crate) async fn known_sub_uuid(app: &App, uuid: &str) -> Option<SubUuidKind> {
    let uuid = uuid.trim();
    if uuid.is_empty() {
        return None;
    }
    let db = app.db.read().await;
    if db
        .passwords
        .values()
        .any(|entry| !entry.sub_uuid.is_empty() && entry.sub_uuid == uuid)
    {
        return Some(SubUuidKind::Client);
    }
    if db
        .groups
        .values()
        .any(|group| !group.sub_uuid.is_empty() && group.sub_uuid == uuid)
    {
        return Some(SubUuidKind::Group);
    }
    None
}

/// Базовый путь capability-страницы: ассеты подставляются абсолютно, потому
/// что панель раздаёт ссылку без завершающего слэша (`/{uuid}/sub`), и
/// относительный `../` там разрешился бы в корень.
pub(crate) fn sub_asset_base(sub_uuid: &str) -> String {
    html_escape(&format!("/{}", sub_uuid.trim()))
}

pub(crate) fn render_client_sub_html(
    client_name: &str,
    csqtt_link: &str,
    asset_base: &str,
) -> String {
    let name_esc = html_escape(client_name);
    let link_esc = html_escape(csqtt_link);
    CLIENT_SUB_HTML
        .replace("__ASSET_BASE__", asset_base)
        .replace("__CLIENT_NAME__", &name_esc)
        .replace("__CSQTT_LINK__", &link_esc)
}

pub(crate) fn render_group_sub_html(
    group_name: &str,
    clients_json: &str,
    count: usize,
    asset_base: &str,
) -> String {
    let name_esc = html_escape(group_name);
    let json_esc = js_json_embed(clients_json);
    GROUP_SUB_HTML
        .replace("__ASSET_BASE__", asset_base)
        .replace("__GROUP_NAME__", &name_esc)
        .replace("__CLIENTS_COUNT__", &count.to_string())
        .replace("__CLIENTS_JSON__", &json_esc)
}

pub(crate) fn js_json_embed(json: &str) -> String {
    json.replace('<', "\\u003C")
        .replace('>', "\\u003E")
        .replace('&', "\\u0026")
        .replace('\u{2028}', "\\u2028")
        .replace('\u{2029}', "\\u2029")
}

pub(crate) async fn client_sub_page(
    State(state): State<WebState>,
    Path(sub_uuid): Path<String>,
    headers: HeaderMap,
    token: Option<Extension<Arc<StealthAbortController>>>,
) -> Response {
    let sub_uuid = sub_uuid.trim();
    if sub_uuid.is_empty() {
        if let Some(Extension(ctrl)) = token {
            ctrl.abort();
        }
        return stealth_abort_response();
    }
    let db = state.app.db.read().await;
    let host = resolve_client_host(&headers, &state).await;

    let client_entry = db
        .passwords
        .iter()
        .find(|(_, entry)| !entry.sub_uuid.is_empty() && entry.sub_uuid == sub_uuid);
    let Some((password, entry)) = client_entry else {
        if let Some(Extension(ctrl)) = token {
            ctrl.abort();
        }
        return stealth_abort_response();
    };
    let client_peer_port = if entry.dtls_port == 0 {
        state.app.listen.port()
    } else {
        entry.dtls_port
    };
    let (effective_vk_token, effective_vk_user_id) = effective_vk_credentials(&entry.group_id, &db);
    let link = make_entry_link(
        &host,
        client_peer_port,
        password,
        entry,
        effective_vk_token,
        effective_vk_user_id,
    );
    let client_name = if entry.name.is_empty() {
        "Клиент".to_string()
    } else {
        entry.name.clone()
    };
    let asset_base = sub_asset_base(sub_uuid);
    Html(render_client_sub_html(&client_name, &link, &asset_base)).into_response()
}

pub(crate) async fn client_sub_json(
    State(state): State<WebState>,
    Path(sub_uuid): Path<String>,
    headers: HeaderMap,
    token: Option<Extension<Arc<StealthAbortController>>>,
) -> Response {
    let sub_uuid = sub_uuid.trim();
    if sub_uuid.is_empty() {
        if let Some(Extension(ctrl)) = token {
            ctrl.abort();
        }
        return stealth_abort_response();
    }
    let db = state.app.db.read().await;
    let host = resolve_client_host(&headers, &state).await;

    let client_entry = db
        .passwords
        .iter()
        .find(|(_, entry)| !entry.sub_uuid.is_empty() && entry.sub_uuid == sub_uuid);
    let Some((password, entry)) = client_entry else {
        if let Some(Extension(ctrl)) = token {
            ctrl.abort();
        }
        return stealth_abort_response();
    };
    let client_peer_port = if entry.dtls_port == 0 {
        state.app.listen.port()
    } else {
        entry.dtls_port
    };
    let (effective_vk_token, effective_vk_user_id) = effective_vk_credentials(&entry.group_id, &db);
    let link = make_entry_link(
        &host,
        client_peer_port,
        password,
        entry,
        effective_vk_token,
        effective_vk_user_id,
    );
    let client_name = if entry.name.is_empty() {
        "Клиент".to_string()
    } else {
        entry.name.clone()
    };
    (
        StatusCode::OK,
        Json(serde_json::json!({
            "ok": true,
            "name": client_name,
            "link": link,
            "sub_uuid": entry.sub_uuid,
        })),
    )
        .into_response()
}

#[derive(Serialize)]
pub(crate) struct GroupSubClientItem {
    pub(crate) name: String,
    pub(crate) link: String,
    pub(crate) sub_uuid: String,
}

pub(crate) async fn group_sub_page(
    State(state): State<WebState>,
    Path(group_sub_uuid): Path<String>,
    headers: HeaderMap,
    token: Option<Extension<Arc<StealthAbortController>>>,
) -> Response {
    let group_sub_uuid = group_sub_uuid.trim();
    if group_sub_uuid.is_empty() {
        if let Some(Extension(ctrl)) = token {
            ctrl.abort();
        }
        return stealth_abort_response();
    }
    let db = state.app.db.read().await;
    let group_entry = db
        .groups
        .values()
        .find(|g| !g.sub_uuid.is_empty() && g.sub_uuid == group_sub_uuid);
    let Some(group) = group_entry else {
        if let Some(Extension(ctrl)) = token {
            ctrl.abort();
        }
        return stealth_abort_response();
    };

    let host = resolve_client_host(&headers, &state).await;
    let (effective_vk_token, effective_vk_user_id) = effective_vk_credentials(&group.id, &db);
    let mut items = Vec::new();
    for (password, entry) in &db.passwords {
        if entry.group_id == group.id {
            let client_peer_port = if entry.dtls_port == 0 {
                state.app.listen.port()
            } else {
                entry.dtls_port
            };
            let link = make_entry_link(
                &host,
                client_peer_port,
                password,
                entry,
                effective_vk_token,
                effective_vk_user_id,
            );
            let name = if entry.name.is_empty() {
                "Клиент".to_string()
            } else {
                entry.name.clone()
            };
            items.push(GroupSubClientItem {
                name,
                link,
                sub_uuid: entry.sub_uuid.clone(),
            });
        }
    }
    items.sort_by(|a, b| a.name.cmp(&b.name));
    let count = items.len();
    let clients_json = serde_json::to_string(&items).unwrap_or_else(|_| "[]".to_string());
    let asset_base = sub_asset_base(group_sub_uuid);
    Html(render_group_sub_html(&group.name, &clients_json, count, &asset_base)).into_response()
}

pub(crate) async fn group_sub_json(
    State(state): State<WebState>,
    Path(group_sub_uuid): Path<String>,
    headers: HeaderMap,
    token: Option<Extension<Arc<StealthAbortController>>>,
) -> Response {
    let group_sub_uuid = group_sub_uuid.trim();
    if group_sub_uuid.is_empty() {
        if let Some(Extension(ctrl)) = token {
            ctrl.abort();
        }
        return stealth_abort_response();
    }
    let db = state.app.db.read().await;
    let group_entry = db
        .groups
        .values()
        .find(|g| !g.sub_uuid.is_empty() && g.sub_uuid == group_sub_uuid);
    let Some(group) = group_entry else {
        if let Some(Extension(ctrl)) = token {
            ctrl.abort();
        }
        return stealth_abort_response();
    };

    let host = resolve_client_host(&headers, &state).await;
    let (effective_vk_token, effective_vk_user_id) = effective_vk_credentials(&group.id, &db);
    let mut items = Vec::new();
    for (password, entry) in &db.passwords {
        if entry.group_id == group.id {
            let client_peer_port = if entry.dtls_port == 0 {
                state.app.listen.port()
            } else {
                entry.dtls_port
            };
            let link = make_entry_link(
                &host,
                client_peer_port,
                password,
                entry,
                effective_vk_token,
                effective_vk_user_id,
            );
            let name = if entry.name.is_empty() {
                "Клиент".to_string()
            } else {
                entry.name.clone()
            };
            items.push(GroupSubClientItem {
                name,
                link,
                sub_uuid: entry.sub_uuid.clone(),
            });
        }
    }
    items.sort_by(|a, b| a.name.cmp(&b.name));

    (
        StatusCode::OK,
        Json(serde_json::json!({
            "ok": true,
            "group": {
                "id": group.id,
                "name": group.name,
                "sub_uuid": group.sub_uuid,
            },
            "clients": items,
        })),
    )
        .into_response()
}

pub(crate) async fn universal_sub_json(
    State(state): State<WebState>,
    Path(uuid): Path<String>,
    headers: HeaderMap,
    token: Option<Extension<Arc<StealthAbortController>>>,
) -> Response {
    let trimmed = uuid.trim();
    if trimmed.is_empty() {
        if let Some(Extension(ctrl)) = token {
            ctrl.abort();
        }
        return stealth_abort_response();
    }
    match known_sub_uuid(&state.app, trimmed).await {
        Some(SubUuidKind::Client) => {
            client_sub_json(State(state), Path(uuid), headers, token).await
        }
        Some(SubUuidKind::Group) => {
            group_sub_json(State(state), Path(uuid), headers, token).await
        }
        None => {
            if let Some(Extension(ctrl)) = token {
                ctrl.abort();
            }
            stealth_abort_response()
        }
    }
}

pub(crate) async fn client_sub_link_regenerate(
    State(state): State<WebState>,
    Path(password): Path<String>,
) -> Response {
    let (revision, new_sub) = {
        let mut db = state.app.db.write().await;
        if let Some(entry) = db.passwords.get_mut(&password) {
            entry.sub_uuid = model::random_uuid_v4();
            let new_sub = entry.sub_uuid.clone();
            let rev = state.app.db_persistence.submit(db.clone());
            (rev, new_sub)
        } else {
            return StatusCode::NOT_FOUND.into_response();
        }
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    (
        StatusCode::OK,
        Json(serde_json::json!({
            "ok": true,
            "sub_uuid": new_sub
        })),
    )
        .into_response()
}

pub(crate) async fn group_sub_link_regenerate(
    State(state): State<WebState>,
    Path(id): Path<String>,
) -> Response {
    let (revision, new_sub) = {
        let mut db = state.app.db.write().await;
        let Some(group) = db.groups.get_mut(&id) else {
            return (StatusCode::NOT_FOUND, "group not found").into_response();
        };
        group.sub_uuid = model::random_uuid_v4();
        let new_sub = group.sub_uuid.clone();
        let rev = state.app.db_persistence.submit(db.clone());
        (rev, new_sub)
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    (
        StatusCode::OK,
        Json(serde_json::json!({
            "ok": true,
            "sub_uuid": new_sub
        })),
    )
        .into_response()
}
