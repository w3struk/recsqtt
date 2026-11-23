use crate::log_event;
use crate::read_unpoison;
use crate::web_panel::*;
use axum::Json;
use axum::extract::Path;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::response::Response;
use serde::Deserialize;
use std::time::Duration;

#[derive(Deserialize)]
pub(crate) struct ProfileRequest {
    #[serde(default)]
    pub(crate) name: String,
    pub(crate) port: u16,
    #[serde(default)]
    pub(crate) username: String,
    #[serde(default)]
    pub(crate) password: String,
}

pub(crate) async fn local_proxy_get(State(state): State<WebState>) -> impl IntoResponse {
    let (proxy_state, active, tcp_sessions, udp_flows) = {
        let db = state.app.db.read().await;
        let route = state.app.proxy_route.read().await;
        let active = route.as_ref().is_some_and(|r| r.is_alive());
        let (tcp_sessions, udp_flows) =
            route.as_ref().map(|r| r.stats_snapshot()).unwrap_or((0, 0));
        (db.local_proxy.clone(), active, tcp_sessions, udp_flows)
    };
    let profiles: Vec<serde_json::Value> = proxy_state
        .profiles
        .iter()
        .map(|p| {
            serde_json::json!({
                "id": p.id,
                "name": p.name,
                "port": p.port,
                "username": p.username,
                "password": p.password,
            })
        })
        .collect();
    Json(serde_json::json!({
        "active_profile_id": proxy_state.active_profile_id,
        "profiles": profiles,
        "route_active": active,
        "port_listening": state.app.proxy_port_listening.load(std::sync::atomic::Ordering::Acquire),
        "health_error": crate::read_unpoison(&state.app.proxy_health_error).clone(),
        "tcp_sessions": tcp_sessions,
        "udp_flows": udp_flows,
    }))
}

pub(crate) async fn local_proxy_create(
    State(state): State<WebState>,
    Json(request): Json<ProfileRequest>,
) -> Response {
    if let Err(message) = validate_proxy_profile_name(&request.name) {
        return (StatusCode::BAD_REQUEST, message).into_response();
    }
    let port = if request.port == 0 {
        crate::model::DEFAULT_LOCAL_PROXY_PORT
    } else {
        request.port
    };
    let profile = crate::model::LocalProxyProfile {
        id: crate::model::LocalProxyProfile::new_id(),
        name: if request.name.is_empty() {
            format!("SOCKS5 :{port}")
        } else {
            request.name
        },
        port,
        username: request.username,
        password: request.password,
    };
    if let Err(error) = crate::proxy_route::validate_config(&profile) {
        return (StatusCode::BAD_REQUEST, error.to_string()).into_response();
    }
    let id = profile.id.clone();
    let revision = {
        let mut db = state.app.db.write().await;
        if db.local_proxy.profiles.len() >= 20 {
            return (StatusCode::BAD_REQUEST, "Too many profiles (max 20)").into_response();
        }
        db.local_proxy.profiles.push(profile);
        state.app.db_persistence.submit(db.clone())
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    log_event(
        &state.app,
        "INFO",
        "PROXY",
        &format!("SOCKS5 profile created: {id}"),
    );
    let port_listening = crate::proxy_route::port_is_listening(port).await;
    Json(serde_json::json!({ "id": id, "port_listening": port_listening })).into_response()
}

pub(crate) async fn local_proxy_update(
    State(state): State<WebState>,
    Path(id): Path<String>,
    Json(request): Json<ProfileRequest>,
) -> Response {
    if let Err(message) = validate_proxy_profile_name(&request.name) {
        return (StatusCode::BAD_REQUEST, message).into_response();
    }
    let port = if request.port == 0 {
        crate::model::DEFAULT_LOCAL_PROXY_PORT
    } else {
        request.port
    };
    let temp_profile = crate::model::LocalProxyProfile {
        id: id.clone(),
        name: request.name.clone(),
        port,
        username: request.username.clone(),
        password: request.password.clone(),
    };
    if let Err(error) = crate::proxy_route::validate_config(&temp_profile) {
        return (StatusCode::BAD_REQUEST, error.to_string()).into_response();
    }
    let (was_active, revision) = {
        let mut db = state.app.db.write().await;
        let was_active = db.local_proxy.active_profile_id == id;
        let Some(profile) = db.local_proxy.find_profile_mut(&id) else {
            return (StatusCode::NOT_FOUND, "Profile not found").into_response();
        };
        if !request.name.is_empty() {
            profile.name = request.name;
        }
        profile.port = port;
        profile.username = request.username;
        profile.password = request.password;
        (was_active, state.app.db_persistence.submit(db.clone()))
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    if was_active {
        state.app.proxy_trigger.notify_one();
    }
    log_event(
        &state.app,
        "INFO",
        "PROXY",
        &format!("SOCKS5 profile updated: {id}"),
    );
    let port_listening = crate::proxy_route::port_is_listening(port).await;
    Json(serde_json::json!({ "updated": true, "port_listening": port_listening })).into_response()
}

pub(crate) async fn local_proxy_delete(
    State(state): State<WebState>,
    Path(id): Path<String>,
) -> Response {
    let (was_active, revision) = {
        let mut db = state.app.db.write().await;
        let was_active = db.local_proxy.active_profile_id == id;
        if !db.local_proxy.remove_profile(&id) {
            return (StatusCode::NOT_FOUND, "Profile not found").into_response();
        }
        (was_active, state.app.db_persistence.submit(db.clone()))
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    if was_active {
        state.app.proxy_trigger.notify_one();
    }
    log_event(
        &state.app,
        "INFO",
        "PROXY",
        &format!("SOCKS5 profile deleted: {id}"),
    );
    Json(serde_json::json!({ "deleted": true })).into_response()
}

pub(crate) async fn wait_proxy_route_applied(
    state: &WebState,
    want_active: bool,
) -> Result<(), String> {
    tokio::time::sleep(Duration::from_millis(250)).await;
    let deadline = std::time::Instant::now() + Duration::from_secs(8);
    loop {
        let route_active = state.app.proxy_route.read().await.is_some();
        let health_error = read_unpoison(&state.app.proxy_health_error).clone();
        if want_active {
            if route_active && health_error.is_none() {
                return Ok(());
            }
            if let Some(error) = health_error {
                return Err(error);
            }
        } else if !route_active {
            return Ok(());
        }
        if std::time::Instant::now() >= deadline {
            return Err("route is still applying; check status in a few seconds".to_owned());
        }
        tokio::time::sleep(Duration::from_millis(150)).await;
    }
}

pub(crate) async fn local_proxy_activate(
    State(state): State<WebState>,
    Path(id): Path<String>,
) -> Response {
    let revision = {
        let mut db = state.app.db.write().await;
        if db.local_proxy.find_profile(&id).is_none() {
            return (StatusCode::NOT_FOUND, "Profile not found").into_response();
        }
        db.local_proxy.active_profile_id = id.clone();
        state.app.db_persistence.submit(db.clone())
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    log_event(
        &state.app,
        "INFO",
        "PROXY",
        "Local SOCKS5 settings saved; applying TPROXY route",
    );
    state.app.proxy_trigger.notify_one();
    match wait_proxy_route_applied(&state, true).await {
        Ok(()) => Json(serde_json::json!({ "activated": true })).into_response(),
        Err(error) => (StatusCode::BAD_GATEWAY, error).into_response(),
    }
}

pub(crate) async fn local_proxy_deactivate(State(state): State<WebState>) -> Response {
    let revision = {
        let mut db = state.app.db.write().await;
        db.local_proxy.active_profile_id.clear();
        state.app.db_persistence.submit(db.clone())
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    log_event(
        &state.app,
        "INFO",
        "PROXY",
        "Local SOCKS5 routing disabled; restoring direct route",
    );
    state.app.proxy_trigger.notify_one();
    match wait_proxy_route_applied(&state, false).await {
        Ok(()) => Json(serde_json::json!({ "deactivated": true })).into_response(),
        Err(error) => (StatusCode::BAD_GATEWAY, error).into_response(),
    }
}
