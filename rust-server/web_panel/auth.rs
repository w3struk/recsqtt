use crate::model::secret_eq;
use crate::{
    App, log_event,
    model::{now, random_token},
};
use axum::{
    Json,
    body::Body,
    extract::{ConnectInfo, State},
    http::{HeaderMap, Request, StatusCode, header},
    middleware::Next,
    response::{IntoResponse, Response},
};
use base64::Engine as _;
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::{net::SocketAddr, sync::Arc, time::Duration};

use crate::web_panel::*;

#[derive(Deserialize)]
pub(crate) struct LoginRequest {
    pub(crate) user: String,
    pub(crate) pass: String,
}

pub(crate) const MAX_LOGIN_FIELD_BYTES: usize = 256;

pub(crate) fn validate_login_request(request: &LoginRequest) -> Result<(), &'static str> {
    validate_text_field(
        &request.user,
        MAX_LOGIN_FIELD_BYTES,
        "login fields are too long or invalid",
    )?;
    validate_text_field(
        &request.pass,
        MAX_LOGIN_FIELD_BYTES,
        "login fields are too long or invalid",
    )
}

pub(crate) const CAESAR_SHIFT: u8 = 47;

pub(crate) const MAX_WEB_SESSIONS: usize = 256;

pub(crate) const MAX_LOGIN_LIMITS: usize = 4_096;

pub(crate) fn caesar_decode(value: &str) -> String {
    if let Some(rest) = value.strip_prefix("c1:")
        && let Ok(bytes) = base64::engine::general_purpose::STANDARD.decode(rest)
    {
        let decoded: Vec<u8> = bytes.iter().map(|b| b.wrapping_sub(CAESAR_SHIFT)).collect();
        if let Ok(s) = String::from_utf8(decoded) {
            return s;
        }
    }
    value.to_owned()
}

pub(crate) fn hash_token(token: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(token.as_bytes());
    hex::encode(hasher.finalize())
}

pub(crate) fn cookie_tokens(headers: &HeaderMap) -> Vec<String> {
    headers
        .get_all(header::COOKIE)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|value| value.split(';'))
        .map(str::trim)
        .filter_map(|part| part.strip_prefix("csqtt_session=").map(str::to_owned))
        .collect()
}

pub(crate) fn authorized(app: &App, headers: &HeaderMap) -> bool {
    if cookie_tokens(headers).iter().any(|token| {
        app.web_sessions
            .get(&hash_token(token))
            .map(|expiry| *expiry > now())
            .unwrap_or(false)
    }) {
        return true;
    }

    // A legacy `Database::main_password` is not a panel credential.
    if let Some(key) = headers
        .get("x-api-key")
        .and_then(|v| v.to_str().ok())
        .map(str::trim)
        && !key.is_empty()
    {
        if secret_eq(key, &app.web_pass) {
            return true;
        }
        if app
            .web_sessions
            .get(&hash_token(key))
            .is_some_and(|expiry| *expiry > now())
        {
            return true;
        }
    }

    if let Some(auth) = headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .map(str::trim)
        && let Some(token) = auth
            .strip_prefix("Bearer ")
            .or_else(|| auth.strip_prefix("bearer "))
    {
        let token = token.trim();
        if !token.is_empty() {
            if secret_eq(token, &app.web_pass) {
                return true;
            }
            if app
                .web_sessions
                .get(&hash_token(token))
                .is_some_and(|expiry| *expiry > now())
            {
                return true;
            }
        }
    }

    false
}

pub(crate) async fn auth_middleware(
    State(state): State<WebState>,
    request: Request<Body>,
    next: Next,
) -> Response {
    if authorized(&state.app, request.headers()) {
        return next.run(request).await;
    }
    let has_auth_header = request.headers().contains_key("x-api-key")
        || request
            .headers()
            .get(header::AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .map(|s| s.starts_with("Bearer ") || s.starts_with("bearer "))
            .unwrap_or(false)
        || !cookie_tokens(request.headers()).is_empty();

    if !has_auth_header {
        if let Some(token) = request.extensions().get::<Arc<StealthAbortController>>() {
            token.abort();
        }
        return stealth_abort_response();
    }

    let mut response = StatusCode::UNAUTHORIZED.into_response();
    if !cookie_tokens(request.headers()).is_empty() {
        response.headers_mut().insert(
            header::SET_COOKIE,
            expired_session_cookie().parse().unwrap(),
        );
    }
    response
}

pub(crate) fn expired_session_cookie() -> &'static str {
    "csqtt_session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax; Secure"
}

pub(crate) async fn login(
    State(state): State<WebState>,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Json(request): Json<LoginRequest>,
) -> Response {
    if let Err(message) = validate_login_request(&request) {
        return (StatusCode::BAD_REQUEST, message).into_response();
    }
    let source = addr.ip().to_string();
    if let Ok(forwarded) = std::env::var("CSQTT_TRUST_XFF")
        && (forwarded == "1" || forwarded.eq_ignore_ascii_case("true"))
        && let Some(forwarded) = headers
            .get("x-forwarded-for")
            .and_then(|v| v.to_str().ok())
            .and_then(|s| s.split(',').next())
            .map(str::trim)
            .filter(|s| !s.is_empty())
    {
        log_event(
            &state.app,
            "INFO",
            "AUTH",
            &format!("Login attempt from {source} (XFF: {forwarded})"),
        );
    }

    let current = now();
    if let Some(value) = state.app.login_limits.get(&source)
        && value.1 > current
        && value.0 >= 3
    {
        return (StatusCode::TOO_MANY_REQUESTS, "too many attempts").into_response();
    }

    let user = caesar_decode(&request.user);
    let pass = caesar_decode(&request.pass);
    let valid = secret_eq(&user, &state.app.web_user) & secret_eq(&pass, &state.app.web_pass);

    if !valid {
        if !state.record_failed_login(source, current) {
            return (StatusCode::TOO_MANY_REQUESTS, "too many login sources").into_response();
        }
        return (StatusCode::UNAUTHORIZED, "invalid credentials").into_response();
    }

    state.app.login_limits.remove(&source);
    let token = random_token(32);
    if !state.web_session_insert(token.clone()) {
        return (
            StatusCode::TOO_MANY_REQUESTS,
            "too many active web sessions",
        )
            .into_response();
    }
    let cookie =
        format!("csqtt_session={token}; Path=/; Max-Age=86400; HttpOnly; SameSite=Lax; Secure");
    let mut response = (StatusCode::OK, [(header::SET_COOKIE, cookie)], "ok").into_response();
    response.headers_mut().insert(
        header::CACHE_CONTROL,
        header::HeaderValue::from_static("no-store, no-cache, must-revalidate, private"),
    );
    response
}

pub(crate) async fn logout(State(state): State<WebState>, headers: HeaderMap) -> Response {
    let tokens = cookie_tokens(&headers);
    if !tokens.is_empty() {
        for token in &tokens {
            state.app.web_sessions.remove(&hash_token(token));
        }
    }
    (
        StatusCode::OK,
        [(header::SET_COOKIE, expired_session_cookie())],
        "ok",
    )
        .into_response()
}

pub(crate) async fn logout_all(State(state): State<WebState>) -> Response {
    state.app.web_sessions.clear();
    (
        StatusCode::OK,
        [(header::SET_COOKIE, expired_session_cookie())],
        "ok",
    )
        .into_response()
}

pub(crate) async fn web_session_janitor(app: Arc<App>) {
    let mut timer = tokio::time::interval(Duration::from_secs(300));
    loop {
        timer.tick().await;
        let current = now();
        app.web_sessions.retain(|_, expiry| *expiry > current);
        app.login_limits.retain(|_, value| value.1 > current);
        app.web_sessions.shrink_to_fit();
        app.login_limits.shrink_to_fit();
    }
}

/// Неудачные входы и вставка сессии — auth-логика, поэтому живут здесь, а не в
/// фасаде; тип `WebState` объявлен там же, где `run`.
impl super::WebState {
    pub(crate) fn record_failed_login(&self, source: String, current: i64) -> bool {
        let _admission = lock_unpoison(&self.app.web_auth_admission);
        self.app.login_limits.retain(|_, value| value.1 > current);
        if !self.app.login_limits.contains_key(&source)
            && self.app.login_limits.len() >= MAX_LOGIN_LIMITS
        {
            return false;
        }
        self.app
            .login_limits
            .entry(source)
            .and_modify(|value| {
                if value.1 <= current {
                    *value = (1, current + 180);
                } else {
                    value.0 = value.0.saturating_add(1);
                }
            })
            .or_insert((1, current + 180));
        true
    }

    pub(crate) fn web_session_insert(&self, token: String) -> bool {
        let _admission = lock_unpoison(&self.app.web_auth_admission);
        let current = now();
        self.app.web_sessions.retain(|_, expiry| *expiry > current);
        if self.app.web_sessions.len() >= MAX_WEB_SESSIONS {
            return false;
        }
        let key = hash_token(&token);
        self.app.web_sessions.insert(key, current + 86400);
        true
    }
}
