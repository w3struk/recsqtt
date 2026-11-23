use crate::web_panel::*;
use axum::http::HeaderMap;
use axum::http::header;

pub(crate) fn is_valid_hostname(name: &str) -> bool {
    if name.is_empty() || name.len() > 253 {
        return false;
    }
    name.split('.').all(|label| {
        !label.is_empty()
            && label.len() <= 63
            && label
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'-')
            && !label.starts_with('-')
            && !label.ends_with('-')
    })
}

pub(crate) fn parse_host_header(host: &str) -> Option<(String, Option<String>)> {
    let host = host.trim();
    if host.is_empty() || host.len() > 255 {
        return None;
    }
    let (name, port) = if let Some(rest) = host.strip_prefix('[') {
        let (name, rest) = rest.split_once(']')?;
        let port = match rest.strip_prefix(':') {
            Some(p) => Some(p.to_string()),
            None if rest.is_empty() => None,
            None => return None,
        };
        (name.to_string(), port)
    } else if let Some((name, port)) = host.rsplit_once(':') {
        (name.to_string(), Some(port.to_string()))
    } else {
        (host.to_string(), None)
    };

    if let Some(port) = port.as_deref()
        && (port.is_empty() || port.len() > 5 || !port.bytes().all(|b| b.is_ascii_digit()))
    {
        return None;
    }

    if name.parse::<std::net::IpAddr>().is_err() && !is_valid_hostname(&name) {
        return None;
    }
    Some((name, port))
}

pub(crate) async fn resolve_client_host(headers: &HeaderMap, state: &WebState) -> String {
    let host_header = headers
        .get(header::HOST)
        .and_then(|h| h.to_str().ok())
        .and_then(parse_host_header)
        .map(|(name, _)| name)
        .unwrap_or_default();

    let is_local_or_unspecified = host_header.is_empty()
        || host_header == "0.0.0.0"
        || host_header == "127.0.0.1"
        || host_header == "localhost"
        || host_header == "::1";

    if !is_local_or_unspecified {
        return host_header.to_string();
    }

    let public_ip = state.app.public_ip.read().await.clone();
    if !public_ip.is_empty() && public_ip != "0.0.0.0" && public_ip != "127.0.0.1" {
        return public_ip;
    }

    let listen_ip = state.app.listen.ip().to_string();
    if listen_ip != "0.0.0.0" && listen_ip != "127.0.0.1" {
        return listen_ip;
    }

    if !host_header.is_empty() {
        return host_header.to_string();
    }

    "0.0.0.0".to_string()
}
