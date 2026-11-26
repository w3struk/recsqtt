use crate::web_panel::*;
use axum::Json;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::response::Response;
use serde::Deserialize;
use std::net::Ipv4Addr;

#[derive(Clone, Copy)]
pub(crate) struct DnsProvider {
    pub(crate) id: &'static str,
    pub(crate) primary: &'static str,
    pub(crate) secondary: &'static str,
}

/// Резолверы, которые панель предлагает как пресеты.
///
/// Остаются только нейтральные рекурсивные резолверы: DNS применяется к
/// приложениям в туннеле, и запросы выходят из сети VPS. Первый элемент —
/// default (`cloudflare`).
pub(crate) const DNS_PROVIDERS: [DnsProvider; 6] = [
    DnsProvider {
        id: "cloudflare",
        primary: "1.1.1.1",
        secondary: "1.0.0.1",
    },
    DnsProvider {
        id: "google",
        primary: "8.8.8.8",
        secondary: "8.8.4.4",
    },
    DnsProvider {
        id: "adguard",
        primary: "94.140.14.14",
        secondary: "94.140.15.14",
    },
    DnsProvider {
        id: "quad9",
        primary: "9.9.9.9",
        secondary: "149.112.112.112",
    },
    DnsProvider {
        id: "opendns",
        primary: "208.67.222.222",
        secondary: "208.67.220.220",
    },
    DnsProvider {
        id: "nextdns",
        primary: "45.90.28.181",
        secondary: "45.90.30.181",
    },
];

pub(crate) fn dns_provider_by_id(id: &str) -> Option<DnsProvider> {
    DNS_PROVIDERS
        .iter()
        .copied()
        .find(|provider| provider.id == id)
}

pub(crate) fn dns_provider_id(dns: &str) -> Option<&'static str> {
    DNS_PROVIDERS
        .iter()
        .find(|provider| dns == format!("{},{}", provider.primary, provider.secondary))
        .map(|provider| provider.id)
}

pub(crate) fn provider_dns(provider: DnsProvider) -> String {
    format!("{},{}", provider.primary, provider.secondary)
}

pub(crate) fn provider_dns_by_id(id: &str) -> Option<String> {
    dns_provider_by_id(id).map(provider_dns)
}

pub(crate) fn manual_dns(
    primary: Option<&str>,
    secondary: Option<&str>,
) -> Result<String, &'static str> {
    let Some(primary) = primary else {
        return Err("primary DNS is required");
    };
    let primary = primary.trim();
    let secondary = secondary.unwrap_or_default().trim();
    if primary.is_empty() {
        return Err("primary DNS is required");
    }
    if primary.parse::<Ipv4Addr>().is_err()
        || (!secondary.is_empty() && secondary.parse::<Ipv4Addr>().is_err())
    {
        return Err("DNS must be a valid IPv4 address");
    }
    Ok(if secondary.is_empty() {
        primary.to_owned()
    } else {
        format!("{primary},{secondary}")
    })
}

pub(crate) fn deploy_dns_profile_or_default(existing_dns: &str) -> String {
    let addresses: Vec<_> = existing_dns
        .split(',')
        .map(str::trim)
        .filter(|address| !address.is_empty())
        .collect();
    let first = addresses.first().copied();
    let selected = DNS_PROVIDERS
        .iter()
        .filter_map(|provider| {
            let matches = addresses
                .iter()
                .filter(|address| **address == provider.primary || **address == provider.secondary)
                .count();
            (matches > 0).then_some((
                provider,
                (
                    matches,
                    usize::from(first == Some(provider.primary)),
                    usize::from(first == Some(provider.secondary)),
                ),
            ))
        })
        .max_by_key(|(_, score)| *score)
        .map(|(provider, _)| *provider)
        .unwrap_or(DNS_PROVIDERS[0]);
    provider_dns(selected)
}

/// Приводит сохранённую пару `dns`/`dns_provider` к каталогу пресетов.
///
/// `custom` не трогаем, известный id разворачиваем в его пару, а удалённые и
/// неизвестные значения нормализуем по адресам с откатом на `cloudflare`.
/// Идемпотентна; используется при старте и при импорте конфигурации.
pub(crate) fn normalize_dns_selection(dns: &str, provider: &str) -> (String, String) {
    if provider == "custom" {
        return (dns.to_owned(), provider.to_owned());
    }
    if let Some(known_dns) = provider_dns_by_id(provider) {
        return (known_dns, provider.to_owned());
    }
    let dns = deploy_dns_profile_or_default(dns);
    let provider = dns_provider_id(&dns).unwrap_or("cloudflare").to_owned();
    (dns, provider)
}

#[derive(Deserialize)]
pub(crate) struct ApiDnsRequest {
    #[serde(default)]
    pub(crate) dns_provider: Option<String>,
    #[serde(default)]
    pub(crate) dns_primary: Option<String>,
    #[serde(default)]
    pub(crate) dns_secondary: Option<String>,
}

pub(crate) async fn api_settings_dns(
    State(state): State<WebState>,
    Json(request): Json<ApiDnsRequest>,
) -> Response {
    let dns = match request.dns_provider.as_deref() {
        Some("custom") | None
            if request.dns_primary.is_some() || request.dns_secondary.is_some() =>
        {
            match manual_dns(
                request.dns_primary.as_deref(),
                request.dns_secondary.as_deref(),
            ) {
                Ok(dns) => (dns, "custom".to_owned()),
                Err(msg) => return (StatusCode::BAD_REQUEST, msg).into_response(),
            }
        }
        Some("custom") => {
            return (StatusCode::BAD_REQUEST, "primary DNS is required").into_response();
        }
        Some(provider_id) => {
            let Some(provider) = dns_provider_by_id(provider_id) else {
                return (StatusCode::BAD_REQUEST, "unknown DNS provider").into_response();
            };
            (provider_dns(provider), provider_id.to_owned())
        }
        None => return (StatusCode::BAD_REQUEST, "missing DNS parameters").into_response(),
    };
    let (revision, applied_dns) = {
        let mut db = state.app.db.write().await;
        let applied_dns = (db.dns != dns.0).then(|| dns.0.clone());
        db.dns = dns.0;
        db.dns_provider = dns.1;
        (state.app.db_persistence.submit(db.clone()), applied_dns)
    };
    if let Err(response) = wait_for_persistence(&state, revision).await {
        return response;
    }
    if let Some(new_dns) = applied_dns {
        *state.app.dns.write().await = new_dns;
        let _ = crate::protocol::broadcast_tunnel_configuration(&state.app).await;
    }
    Json(serde_json::json!({ "ok": true })).into_response()
}
