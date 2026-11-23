use crate::model;
use crate::web_panel::*;
use axum::Json;
use axum::extract::State;
use axum::http::HeaderMap;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::response::Response;
use serde::Deserialize;
use serde::Serialize;

pub(crate) fn url_encode(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    for b in input.bytes() {
        match b {
            b'a'..=b'z' | b'A'..=b'Z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char);
            }
            _ => {
                use std::fmt::Write;
                let _ = write!(out, "%{:02X}", b);
            }
        }
    }
    out
}

pub const CSQTT_CIPHER_KEY_SEED: &str = "csqtt-chiper-key";

pub fn encrypt_csqtt_link(payload: &str) -> String {
    use aws_lc_rs::aead::{AES_256_GCM, Aad, LessSafeKey, Nonce, UnboundKey};
    use base64::Engine;
    use hmac::{Hmac, KeyInit, Mac};
    use sha2::{Digest, Sha256};

    let key_hash = Sha256::digest(CSQTT_CIPHER_KEY_SEED.as_bytes());
    let unbound = match UnboundKey::new(&AES_256_GCM, &key_hash) {
        Ok(k) => k,
        Err(_) => {
            let b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(payload.as_bytes());
            return format!("csqtt://{b64}");
        }
    };
    let key = LessSafeKey::new(unbound);

    let mut iv = [0u8; 12];
    type HmacSha256 = Hmac<Sha256>;
    if let Ok(mut mac) = HmacSha256::new_from_slice(&key_hash) {
        mac.update(payload.as_bytes());
        let result = mac.finalize().into_bytes();
        iv.copy_from_slice(&result[..12]);
    } else {
        use rand::RngCore;
        rand::rngs::OsRng.fill_bytes(&mut iv);
    }
    let nonce = match Nonce::try_assume_unique_for_key(&iv) {
        Ok(n) => n,
        Err(_) => {
            let b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(payload.as_bytes());
            return format!("csqtt://{b64}");
        }
    };

    let mut in_out = payload.as_bytes().to_vec();
    if key
        .seal_in_place_append_tag(nonce, Aad::empty(), &mut in_out)
        .is_err()
    {
        let b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(payload.as_bytes());
        return format!("csqtt://{b64}");
    }

    let mut combined = Vec::with_capacity(12 + in_out.len());
    combined.extend_from_slice(&iv);
    combined.extend_from_slice(&in_out);

    let b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(&combined);
    format!("csqtt://{b64}")
}

#[allow(dead_code)]
pub fn decrypt_csqtt_link(raw_link: &str) -> Option<String> {
    use aws_lc_rs::aead::{AES_256_GCM, Aad, LessSafeKey, Nonce, UnboundKey};
    use base64::Engine;
    use sha2::{Digest, Sha256};

    let payload = raw_link.trim().strip_prefix("csqtt://")?.trim();
    let clean = payload.replace(['\n', '\r'], "");
    let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(&clean)
        .or_else(|_| base64::engine::general_purpose::URL_SAFE.decode(&clean))
        .or_else(|_| base64::engine::general_purpose::STANDARD_NO_PAD.decode(&clean))
        .or_else(|_| base64::engine::general_purpose::STANDARD.decode(&clean))
        .ok()?;

    if bytes.len() >= 28 {
        let key_hash = Sha256::digest(CSQTT_CIPHER_KEY_SEED.as_bytes());
        if let Ok(unbound) = UnboundKey::new(&AES_256_GCM, &key_hash) {
            let key = LessSafeKey::new(unbound);
            let mut iv = [0u8; 12];
            iv.copy_from_slice(&bytes[..12]);
            if let Ok(nonce) = Nonce::try_assume_unique_for_key(&iv) {
                let mut ciphertext = bytes[12..].to_vec();
                if let Ok(decrypted) = key.open_in_place(nonce, Aad::empty(), &mut ciphertext)
                    && let Ok(s) = String::from_utf8(decrypted.to_vec())
                {
                    return Some(s);
                }
            }
        }
    }

    None
}

pub(crate) fn make_client_link(
    host: &str,
    port: u16,
    password: &str,
    vk_hashes: &str,
    vk_token: bool,
    shared_token: &str,
    shared_user_id: &str,
) -> String {
    let mut payload = format!(
        "connect?v=2&host={}&peer={}",
        url_encode(host),
        if port == 0 { 46000 } else { port },
    );
    if !password.is_empty() {
        payload.push_str("&password=");
        payload.push_str(&url_encode(password));
    }
    let hashes: Vec<&str> = vk_hashes
        .split(',')
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .collect();
    if !hashes.is_empty() {
        payload.push_str("&hashes=");
        payload.push_str(
            &hashes
                .iter()
                .map(|h| url_encode(h))
                .collect::<Vec<_>>()
                .join("+"),
        );
    } else if vk_token && !shared_token.is_empty() {
        payload.push_str("&token=");
        payload.push_str(&url_encode(shared_token));
        // `user_id` only ever accompanies `token`; an empty id is a degradation
        // (the client falls back to the token fingerprint), not an error.
        if !shared_user_id.is_empty() {
            payload.push_str("&user_id=");
            payload.push_str(&url_encode(shared_user_id));
        }
    }
    encrypt_csqtt_link(&payload)
}

#[inline]
pub(crate) fn make_entry_link(
    host: &str,
    port: u16,
    password: &str,
    entry: &model::PasswordEntry,
    shared_token: &str,
    shared_user_id: &str,
) -> String {
    make_client_link(
        host,
        port,
        password,
        &entry.vk_hashes,
        entry.vk_token_enabled,
        shared_token,
        shared_user_id,
    )
}

#[derive(Serialize)]
pub(crate) struct ClientLinkItem {
    pub(crate) name: String,
    pub(crate) password: String,
    pub(crate) link: String,
    pub(crate) expires: i64,
    pub(crate) peer_port: u16,
    pub(crate) group_id: String,
    #[serde(default)]
    pub(crate) sub_uuid: String,
}

pub(crate) async fn api_links_get(
    State(state): State<WebState>,
    headers: HeaderMap,
) -> impl IntoResponse {
    let db = state.app.db.read().await;
    let host = resolve_client_host(&headers, &state).await;
    let list: Vec<ClientLinkItem> = db
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
            ClientLinkItem {
                name: entry.name.clone(),
                password: password.clone(),
                link: make_entry_link(
                    &host,
                    client_peer_port,
                    password,
                    entry,
                    effective_token,
                    effective_user_id,
                ),
                expires: entry.expires_at,
                peer_port: client_peer_port,
                group_id: entry.group_id.clone(),
                sub_uuid: entry.sub_uuid.clone(),
            }
        })
        .collect();
    Json(list)
}

#[derive(Deserialize)]
pub(crate) struct BatchVkRequest {
    #[serde(default)]
    pub(crate) vk_token: Option<String>,
    #[serde(default)]
    pub(crate) vk_token_user_id: Option<String>,
    #[serde(default)]
    pub(crate) vk_hashes: Option<String>,
    #[serde(default)]
    pub(crate) apply_to_all_clients: Option<bool>,
}

pub(crate) async fn api_links_batch_vk(
    State(state): State<WebState>,
    Json(request): Json<BatchVkRequest>,
) -> Response {
    let token = match request.vk_token {
        Some(t) => match normalize_vk_access_token(&t) {
            Ok(v) => Some(v),
            Err(e) => return (StatusCode::BAD_REQUEST, e).into_response(),
        },
        None => None,
    };
    let token_user_id = match request.vk_token_user_id.as_deref() {
        Some(raw) => match normalize_vk_user_id(raw) {
            Ok(user_id) => Some(user_id),
            Err(e) => return (StatusCode::BAD_REQUEST, e).into_response(),
        },
        None => None,
    };
    let hashes = match request.vk_hashes {
        Some(h) => match normalize_vk_hashes(&h) {
            Ok(v) => Some(v),
            Err(e) => return (StatusCode::BAD_REQUEST, e).into_response(),
        },
        None => None,
    };
    let apply_all = request.apply_to_all_clients.unwrap_or(true);
    let mut updated_count = 0usize;
    let revision = {
        let mut db = state.app.db.write().await;
        if let Some(ref t) = token {
            db.vk_access_token = t.clone();
        }
        if let Some(user_id) = token_user_id {
            db.vk_access_token_user_id = user_id.unwrap_or_default();
        }
        if let Some(ref h) = hashes {
            db.vk_hashes = h.clone();
        }
        if apply_all {
            for entry in db.passwords.values_mut() {
                if let Some(ref h) = hashes {
                    entry.vk_hashes = h.clone();
                }
                if let Some(ref t) = token {
                    entry.vk_token_enabled = !t.is_empty();
                }
                updated_count += 1;
            }
        }
        state.app.db_persistence.submit(db.clone())
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    let _ = refresh_credentials_report(&state).await;
    Json(serde_json::json!({
        "ok": true,
        "updated_clients": updated_count
    }))
    .into_response()
}
