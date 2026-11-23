use crate::model;
use crate::web_panel::*;
use axum::Json;
use axum::extract::Path;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::response::Response;
use serde::Deserialize;

pub(crate) fn normalize_vk_access_token(raw: &str) -> Result<String, &'static str> {
    let token = raw.trim();
    if token.is_empty() {
        return Ok(String::new());
    }
    if token.len() > 4096 || token.chars().any(char::is_whitespace) {
        return Err("VK token is too long or contains whitespace");
    }
    Ok(token.to_owned())
}

/// A VK user id is entered by hand in the panel: digits only, 1 to 20 of them.
/// Empty input is valid and means "not set"; it is not an error.
pub(crate) fn normalize_vk_user_id(raw: &str) -> Result<Option<String>, &'static str> {
    let value = raw.trim();
    if value.is_empty() {
        return Ok(None);
    }
    if value.len() > 20 || !value.bytes().all(|byte| byte.is_ascii_digit()) {
        return Err("VK user id must be 1 to 20 digits");
    }
    Ok(Some(value.to_owned()))
}

/// Picks the pair (token, user id) that a link must carry for a group. Mirrors
/// the group-token-else-global rule exactly, so a group token never inherits
/// the global user id: that would point the calls at the wrong owner.
pub(crate) fn normalize_vk_hashes(raw: &str) -> Result<String, &'static str> {
    let value = raw.trim();
    if value.is_empty() {
        return Ok(String::new());
    }
    if value.len() > 1024 {
        return Err("VK hashes are too long");
    }
    if value.chars().any(char::is_whitespace) {
        return Err("VK hashes must be comma-separated without spaces");
    }
    let hashes: Vec<_> = value.split(',').collect();
    if hashes.len() > 6 || hashes.iter().any(|hash| hash.len() < 16) {
        return Err("provide 1 to 6 valid VK hashes");
    }
    Ok(hashes.join(","))
}

pub(crate) fn effective_vk_credentials<'a>(
    group_id: &str,
    db: &'a model::Database,
) -> (&'a str, &'a str) {
    if let Some(group) = db.groups.get(group_id)
        && let Some(token) = group.vk_token.as_deref()
    {
        return (token, group.vk_token_user_id.as_deref().unwrap_or_default());
    }
    (&db.vk_access_token, db.vk_access_token_user_id.as_str())
}

#[derive(Deserialize)]
pub(crate) struct ClientVkUpdateRequest {
    #[serde(default)]
    pub(crate) vk_token: Option<bool>,
    #[serde(default)]
    pub(crate) vk_hashes: Option<String>,
}

pub(crate) async fn api_client_vk_update(
    State(state): State<WebState>,
    Path(password): Path<String>,
    Json(request): Json<ClientVkUpdateRequest>,
) -> Response {
    let hashes = match request.vk_hashes {
        Some(h) => match normalize_vk_hashes(&h) {
            Ok(v) => Some(v),
            Err(e) => return (StatusCode::BAD_REQUEST, e).into_response(),
        },
        None => None,
    };
    let revision = {
        let mut db = state.app.db.write().await;
        let Some(entry) = db.passwords.get_mut(&password) else {
            return (StatusCode::NOT_FOUND, "client not found").into_response();
        };
        if let Some(h) = hashes {
            entry.vk_hashes = h;
        }
        if let Some(token_enabled) = request.vk_token {
            entry.vk_token_enabled = token_enabled;
        }
        state.app.db_persistence.submit(db.clone())
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    let _ = refresh_credentials_report(&state).await;
    Json(serde_json::json!({ "ok": true })).into_response()
}
