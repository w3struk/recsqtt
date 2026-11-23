use crate::model::now;
use crate::web_panel::*;
use axum::Json;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::response::Response;
use serde::Deserialize;
use std::sync::atomic::Ordering;

#[derive(Deserialize)]
pub(crate) struct BatchToggleRequest {
    pub(crate) passwords: Vec<String>,
    pub(crate) active: bool,
}

pub(crate) async fn api_clients_batch_toggle(
    State(state): State<WebState>,
    Json(req): Json<BatchToggleRequest>,
) -> Response {
    let mut updated = 0usize;
    let mut passwords_to_disconnect = Vec::new();
    let revision = {
        let mut db = state.app.db.write().await;
        for pwd in &req.passwords {
            if let Some(entry) = db.passwords.get_mut(pwd) {
                entry.is_deactivated = !req.active;
                updated += 1;
                if !req.active {
                    passwords_to_disconnect.push(pwd.clone());
                }
            }
        }
        if updated > 0 {
            Some(state.app.db_persistence.submit(db.clone()))
        } else {
            None
        }
    };
    if let Some(rev) = revision {
        let _ = wait_for_persistence(&state, rev).await;
    }
    for pwd in &passwords_to_disconnect {
        crate::protocol::drop_password_sessions(&state.app, pwd).await;
    }
    if updated > 0 {
        let _ = refresh_credentials_report(&state).await;
    }
    (
        StatusCode::OK,
        Json(serde_json::json!({ "ok": true, "updated": updated })),
    )
        .into_response()
}

#[derive(Deserialize)]
pub(crate) struct BatchExtendRequest {
    pub(crate) passwords: Vec<String>,
    pub(crate) days: i64,
}

pub(crate) async fn api_clients_batch_extend(
    State(state): State<WebState>,
    Json(req): Json<BatchExtendRequest>,
) -> Response {
    if req.days <= 0 || req.days > 3650 {
        return (StatusCode::BAD_REQUEST, "days must be between 1 and 3650").into_response();
    }
    let add_secs = req.days.saturating_mul(86400);
    let current_time = now();
    let mut updated = 0usize;
    let revision = {
        let mut db = state.app.db.write().await;
        for pwd in &req.passwords {
            if let Some(entry) = db.passwords.get_mut(pwd) {
                let base = if entry.expires_at > current_time {
                    entry.expires_at
                } else {
                    current_time
                };
                entry.expires_at = base.saturating_add(add_secs);
                updated += 1;
            }
        }
        if updated > 0 {
            Some(state.app.db_persistence.submit(db.clone()))
        } else {
            None
        }
    };
    if let Some(rev) = revision {
        let _ = wait_for_persistence(&state, rev).await;
    }
    (
        StatusCode::OK,
        Json(serde_json::json!({ "ok": true, "updated": updated })),
    )
        .into_response()
}

#[derive(Deserialize)]
pub(crate) struct BatchPasswordsRequest {
    pub(crate) passwords: Vec<String>,
}

pub(crate) async fn api_clients_batch_reset_traffic(
    State(state): State<WebState>,
    Json(req): Json<BatchPasswordsRequest>,
) -> Response {
    let mut updated = 0usize;
    let revision = {
        let mut db = state.app.db.write().await;
        for pwd in &req.passwords {
            if let Some(entry) = db.passwords.get_mut(pwd) {
                entry.down_bytes = 0;
                entry.up_bytes = 0;
                updated += 1;
            }
        }
        if updated > 0 {
            Some(state.app.db_persistence.submit(db.clone()))
        } else {
            None
        }
    };
    if let Some(rev) = revision {
        let _ = wait_for_persistence(&state, rev).await;
    }
    for pwd in &req.passwords {
        for s in state.app.sessions.iter() {
            if &s.password == pwd {
                s.down_bytes.store(0, Ordering::Relaxed);
                s.up_bytes.store(0, Ordering::Relaxed);
            }
        }
    }
    (
        StatusCode::OK,
        Json(serde_json::json!({ "ok": true, "updated": updated })),
    )
        .into_response()
}

pub(crate) async fn api_clients_batch_delete(
    State(state): State<WebState>,
    Json(req): Json<BatchPasswordsRequest>,
) -> Response {
    let mut deleted = 0usize;
    let revision = {
        let mut db = state.app.db.write().await;
        for pwd in &req.passwords {
            if db.passwords.remove(pwd).is_some() {
                state.app.derived_keys.remove(pwd);
                deleted += 1;
            }
        }
        if deleted > 0 {
            Some(state.app.db_persistence.submit(db.clone()))
        } else {
            None
        }
    };
    if let Some(rev) = revision {
        let _ = wait_for_persistence(&state, rev).await;
    }
    for pwd in &req.passwords {
        crate::protocol::drop_password_sessions(&state.app, pwd).await;
    }
    if deleted > 0 {
        let _ = refresh_credentials_report(&state).await;
    }
    (
        StatusCode::OK,
        Json(serde_json::json!({ "ok": true, "deleted": deleted })),
    )
        .into_response()
}

#[derive(Deserialize)]
pub(crate) struct BatchGroupAssignRequest {
    pub(crate) passwords: Vec<String>,
    pub(crate) group_id: String,
}

pub(crate) async fn api_clients_batch_group(
    State(state): State<WebState>,
    Json(req): Json<BatchGroupAssignRequest>,
) -> Response {
    let mut moved = 0usize;
    let (revision, affected) = {
        let mut db = state.app.db.write().await;
        let gid = req.group_id.trim();
        if !gid.is_empty() && !db.groups.contains_key(gid) {
            return (StatusCode::BAD_REQUEST, "target group not found").into_response();
        }
        let affected = batch_group_affected_passwords(&db, &req.passwords, gid);
        for pwd in &req.passwords {
            if let Some(entry) = db.passwords.get_mut(pwd) {
                entry.group_id = gid.to_string();
                moved += 1;
            }
        }
        let revision = if moved > 0 {
            Some(state.app.db_persistence.submit(db.clone()))
        } else {
            None
        };
        (revision, affected)
    };
    if let Some(rev) = revision {
        let _ = wait_for_persistence(&state, rev).await;
    }
    notify_routing_changed_or_log(&state, &affected).await;
    (
        StatusCode::OK,
        Json(serde_json::json!({ "ok": true, "moved": moved })),
    )
        .into_response()
}
