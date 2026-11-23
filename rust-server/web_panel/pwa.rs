use crate::web_assets::{LOGIN_HTML, PANEL_HTML, QRCODE_JS};
use axum::{
    extract::{Extension, State},
    http::{HeaderMap, header},
    response::{Html, IntoResponse, Redirect, Response},
};
use std::sync::Arc;

use crate::web_panel::*;

pub(crate) fn render_root(state: &WebState, headers: &HeaderMap) -> Response {
    if authorized(&state.app, headers) {
        let mut response = Html(PANEL_HTML.as_str()).into_response();
        response.headers_mut().insert(
            header::CACHE_CONTROL,
            header::HeaderValue::from_static("no-store, no-cache, must-revalidate, private"),
        );
        return response;
    }
    let mut response = Html(LOGIN_HTML.as_str()).into_response();
    response.headers_mut().insert(
        header::CACHE_CONTROL,
        header::HeaderValue::from_static("no-store, no-cache, must-revalidate, private"),
    );
    if !cookie_tokens(headers).is_empty() {
        response.headers_mut().insert(
            header::SET_COOKIE,
            expired_session_cookie().parse().unwrap(),
        );
    }
    response
}

pub(crate) async fn panel_root(State(state): State<WebState>, headers: HeaderMap) -> Response {
    render_root(&state, &headers)
}

pub(crate) async fn favicon() -> Response {
    pwa_response(PWA_ICON_SVG, "image/svg+xml", "public, max-age=604800")
}

pub(crate) async fn fallback_reject(
    token: Option<Extension<Arc<StealthAbortController>>>,
) -> Response {
    if let Some(Extension(controller)) = token {
        controller.abort();
    }
    stealth_abort_response()
}

pub(crate) fn pwa_response(
    body: impl IntoResponse,
    content_type: &'static str,
    cache_control: &'static str,
) -> Response {
    let mut response = body.into_response();
    let headers = response.headers_mut();
    headers.insert(
        header::CONTENT_TYPE,
        header::HeaderValue::from_static(content_type),
    );
    headers.insert(
        header::CACHE_CONTROL,
        header::HeaderValue::from_static(cache_control),
    );
    response
}

pub(crate) fn pwa_binary_response(
    body: Vec<u8>,
    content_type: &'static str,
    cache_control: &'static str,
) -> Response {
    let mut response = body.into_response();
    let headers = response.headers_mut();
    headers.insert(
        header::CONTENT_TYPE,
        header::HeaderValue::from_static(content_type),
    );
    headers.insert(
        header::CACHE_CONTROL,
        header::HeaderValue::from_static(cache_control),
    );
    response
}

pub(crate) async fn root_with_secret(
    State(state): State<WebState>,
    headers: HeaderMap,
    token: Option<Extension<Arc<StealthAbortController>>>,
) -> Response {
    if authorized(&state.app, &headers) {
        let secret = state.app.web_secret_path.trim().trim_matches('/');
        let target = if secret.is_empty() {
            "/".to_string()
        } else {
            format!("/{}/", secret)
        };
        return Redirect::temporary(&target).into_response();
    }
    fallback_reject(token).await
}

pub(crate) async fn pwa_manifest(State(state): State<WebState>) -> Response {
    let secret = state.app.web_secret_path.trim().trim_matches('/');
    if secret.is_empty() {
        pwa_response(
            PWA_MANIFEST,
            "application/manifest+json; charset=utf-8",
            "no-cache",
        )
    } else {
        let prefix = format!("/{}", secret);
        let base = format!("{}/", prefix);
        let manifest = PWA_MANIFEST
            .replace("\"id\": \"/\"", &format!("\"id\": \"{}\"", base))
            .replace(
                "\"start_url\": \"/\"",
                &format!("\"start_url\": \"{}\"", base),
            )
            .replace("\"scope\": \"/\"", &format!("\"scope\": \"{}\"", base))
            .replace(
                "\"/pwa-icon-192.png\"",
                &format!("\"{}/pwa-icon-192.png\"", prefix),
            )
            .replace(
                "\"/pwa-icon-512.png\"",
                &format!("\"{}/pwa-icon-512.png\"", prefix),
            )
            .replace("\"/pwa-icon.svg\"", &format!("\"{}/pwa-icon.svg\"", prefix));
        pwa_response(
            manifest,
            "application/manifest+json; charset=utf-8",
            "no-cache",
        )
    }
}

pub(crate) async fn pwa_service_worker() -> Response {
    let mut response = pwa_response(
        PWA_SERVICE_WORKER,
        "application/javascript; charset=utf-8",
        "no-cache",
    );
    response.headers_mut().insert(
        header::HeaderName::from_static("service-worker-allowed"),
        header::HeaderValue::from_static("/"),
    );
    response
}

pub(crate) async fn pwa_icon() -> Response {
    pwa_response(PWA_ICON_SVG, "image/svg+xml", "public, max-age=604800")
}

pub(crate) async fn pwa_icon_192() -> Response {
    pwa_binary_response(
        PWA_ICON_192_PNG.clone(),
        "image/png",
        "public, max-age=604800",
    )
}

pub(crate) async fn pwa_icon_512() -> Response {
    pwa_binary_response(
        PWA_ICON_512_PNG.clone(),
        "image/png",
        "public, max-age=604800",
    )
}

pub(crate) async fn qrcode_js() -> Response {
    pwa_response(
        QRCODE_JS,
        "application/javascript; charset=utf-8",
        "public, max-age=604800, immutable",
    )
}
