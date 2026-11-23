use crate::log_event;
use crate::web_panel::*;
use axum::Json;
use axum::extract::State;
use axum::http::StatusCode;
use axum::http::header;
use axum::response::IntoResponse;
use axum::response::Response;
use serde::Deserialize;
use std::sync::atomic::Ordering;

#[derive(Deserialize)]
pub(crate) struct SettingsRequest {
    #[serde(default)]
    pub(crate) dns_provider: Option<String>,
    #[serde(default)]
    pub(crate) dns_primary: Option<String>,
    #[serde(default)]
    pub(crate) dns_secondary: Option<String>,
    #[serde(default)]
    pub(crate) auto_restart_interval_hours: Option<u8>,
    #[serde(default)]
    pub(crate) vk_token: Option<String>,
    #[serde(default)]
    pub(crate) vk_token_user_id: Option<String>,
    #[serde(default)]
    pub(crate) vk_hashes: Option<String>,
}

pub(crate) async fn settings_get(State(state): State<WebState>) -> impl IntoResponse {
    let db = state.app.db.read().await;
    let dns = db.dns.clone();
    let mut parts = dns.splitn(2, ',');
    let primary = parts.next().unwrap_or("77.88.8.8").trim().to_owned();
    let secondary = parts.next().unwrap_or("77.88.8.1").trim().to_owned();
    let provider = if db.dns_provider == "custom" || dns_provider_by_id(&db.dns_provider).is_some()
    {
        db.dns_provider.as_str()
    } else {
        dns_provider_id(&dns).unwrap_or("custom")
    };
    let vk_hashes = if !db.vk_hashes.is_empty() {
        db.vk_hashes.clone()
    } else {
        db.passwords
            .values()
            .find(|p| !p.vk_hashes.is_empty())
            .map(|p| p.vk_hashes.clone())
            .unwrap_or_default()
    };
    Json(serde_json::json!({
        "dns_provider": provider,
        "dns_primary": primary,
        "dns_secondary": secondary,
        "auto_restart_interval_hours": db.auto_restart_interval_hours(),
        "vk_token": db.vk_access_token,
        "vk_token_user_id": db.vk_access_token_user_id,
        "vk_hashes": vk_hashes,
        "peer_port": state.app.listen.port()
    }))
}

pub(crate) async fn settings_post(
    State(state): State<WebState>,
    Json(request): Json<SettingsRequest>,
) -> Response {
    let dns = match request.dns_provider.as_deref() {
        Some("custom") | None
            if request.dns_primary.is_some() || request.dns_secondary.is_some() =>
        {
            match manual_dns(
                request.dns_primary.as_deref(),
                request.dns_secondary.as_deref(),
            ) {
                Ok(dns) => Some((dns, "custom".to_owned())),
                Err(message) => return (StatusCode::BAD_REQUEST, message).into_response(),
            }
        }
        Some("custom") => {
            return (StatusCode::BAD_REQUEST, "primary DNS is required").into_response();
        }
        Some(provider_id) => {
            let Some(provider) = dns_provider_by_id(provider_id) else {
                return (StatusCode::BAD_REQUEST, "unknown DNS provider").into_response();
            };
            Some((provider_dns(provider), provider_id.to_owned()))
        }
        None => None,
    };
    if let Some(hours) = request.auto_restart_interval_hours
        && !matches!(hours, 0 | 12 | 24 | 48 | 72)
    {
        return (
            StatusCode::BAD_REQUEST,
            "auto restart interval must be 0, 12, 24, 48, or 72 hours",
        )
            .into_response();
    }
    let requested_vk_access_token = match request.vk_token {
        Some(token) => match normalize_vk_access_token(&token) {
            Ok(token) => Some(token),
            Err(message) => return (StatusCode::BAD_REQUEST, message).into_response(),
        },
        None => None,
    };
    let requested_vk_access_token_user_id = match request.vk_token_user_id.as_deref() {
        Some(raw) => match normalize_vk_user_id(raw) {
            Ok(user_id) => Some(user_id),
            Err(message) => return (StatusCode::BAD_REQUEST, message).into_response(),
        },
        None => None,
    };
    let requested_vk_hashes = match request.vk_hashes {
        Some(raw) => match normalize_vk_hashes(&raw) {
            Ok(hashes) => Some(hashes),
            Err(message) => return (StatusCode::BAD_REQUEST, message).into_response(),
        },
        None => None,
    };
    let requested_auto_restart_interval = request.auto_restart_interval_hours;
    let (revision, applied_dns) = {
        let mut db = state.app.db.write().await;
        let applied_dns = dns
            .as_ref()
            .and_then(|(dns, _)| (db.dns != *dns).then(|| dns.clone()));
        if let Some((dns, provider)) = dns.as_ref() {
            db.dns.clone_from(dns);
            db.dns_provider.clone_from(provider);
        }
        if let Some(hours) = requested_auto_restart_interval {
            db.set_auto_restart_interval_hours(hours);
        }
        if let Some(token) = requested_vk_access_token {
            db.vk_access_token = token;
        }
        if let Some(user_id) = requested_vk_access_token_user_id {
            db.vk_access_token_user_id = user_id.unwrap_or_default();
        }
        if let Some(hashes) = requested_vk_hashes {
            db.vk_hashes = hashes;
        }
        (state.app.db_persistence.submit(db.clone()), applied_dns)
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    if let Some(hours) = requested_auto_restart_interval {
        state.app.auto_restart_interval_tx.send_replace(hours);
    }
    if let Some(dns) = applied_dns {
        *state.app.dns.write().await = dns;
        match crate::protocol::broadcast_tunnel_configuration(&state.app).await {
            Ok(sent) => log_event(
                &state.app,
                "INFO",
                "DNS",
                &format!("DNS configuration applied to {sent} active paths"),
            ),
            Err(error) => {
                let warning = format!("saved but DNS apply failed: {error:#}");
                return (StatusCode::OK, [("x-csqtt-reload-error", warning)], "saved")
                    .into_response();
            }
        }
    }
    Json(serde_json::json!({ "ok": true })).into_response()
}

#[derive(Deserialize)]
pub(crate) struct RestartIntervalRequest {
    pub(crate) interval_hours: u8,
}

pub(crate) async fn api_settings_restart_interval(
    State(state): State<WebState>,
    Json(request): Json<RestartIntervalRequest>,
) -> Response {
    if !matches!(request.interval_hours, 0 | 12 | 24 | 48 | 72) {
        return (
            StatusCode::BAD_REQUEST,
            "auto restart interval must be 0, 12, 24, 48, or 72 hours",
        )
            .into_response();
    }
    let revision = {
        let mut db = state.app.db.write().await;
        db.set_auto_restart_interval_hours(request.interval_hours);
        state.app.db_persistence.submit(db.clone())
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    state
        .app
        .auto_restart_interval_tx
        .send_replace(request.interval_hours);
    Json(serde_json::json!({ "ok": true, "interval_hours": request.interval_hours }))
        .into_response()
}

pub(crate) async fn reboot(State(state): State<WebState>) -> Response {
    if state.app.restart_pending.swap(true, Ordering::AcqRel) {
        return (
            StatusCode::ACCEPTED,
            [
                (header::RETRY_AFTER, "3"),
                (header::CACHE_CONTROL, "no-store"),
            ],
        )
            .into_response();
    }

    match crate::request_managed_restart(&state.app, "panel") {
        Ok(()) => (
            StatusCode::ACCEPTED,
            [
                (header::RETRY_AFTER, "3"),
                (header::CACHE_CONTROL, "no-store"),
            ],
        )
            .into_response(),
        Err(error) => {
            state.app.restart_pending.store(false, Ordering::Release);
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                format!("restart failed: {error:#}"),
            )
                .into_response()
        }
    }
}
