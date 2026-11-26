use crate::web_assets::PANEL_HTML;
use crate::web_panel::*;

#[test]
fn dns_provider_catalog_has_six_recursive_presets_and_cloudflare_default() {
    assert_eq!(DNS_PROVIDERS.len(), 6);
    let default = DNS_PROVIDERS[0];
    assert_eq!(default.id, "cloudflare");
    assert_eq!(provider_dns(default), "1.1.1.1,1.0.0.1");
    for (id, pair) in [
        ("cloudflare", "1.1.1.1,1.0.0.1"),
        ("google", "8.8.8.8,8.8.4.4"),
        ("adguard", "94.140.14.14,94.140.15.14"),
        ("quad9", "9.9.9.9,149.112.112.112"),
        ("opendns", "208.67.222.222,208.67.220.220"),
        ("nextdns", "45.90.28.181,45.90.30.181"),
    ] {
        assert_eq!(dns_provider_id(pair), Some(id));
    }
    for removed in [
        "77.88.8.8,77.88.8.1",
        "195.208.4.1,195.208.5.1",
        "195.208.6.1,195.208.7.1",
        "95.189.32.74,195.208.5.1",
        "111.88.96.54,111.88.96.55",
        "83.220.169.155,212.109.195.93",
        "193.233.112.67,193.233.112.88",
    ] {
        assert_eq!(dns_provider_id(removed), None);
    }
    assert_eq!(dns_provider_id("10.0.0.1"), None);
}

#[test]
fn deploy_dns_profile_or_default_uses_cloudflare_for_removed_and_garbage() {
    for removed in [
        "77.88.8.8,77.88.8.1",
        "195.208.4.1,195.208.5.1",
        "111.88.96.50,111.88.96.51",
        "111.88.96.55,203.0.113.7",
        "95.182.120.241,37.230.192.51",
        "203.0.113.7,198.51.100.9",
        "",
    ] {
        assert_eq!(
            deploy_dns_profile_or_default(removed),
            "1.1.1.1,1.0.0.1",
            "{removed}"
        );
    }
    assert_eq!(
        deploy_dns_profile_or_default("8.8.8.8,1.1.1.1"),
        "8.8.8.8,8.8.4.4"
    );
}

#[test]
fn normalize_dns_selection_replaces_removed_providers_and_keeps_custom() {
    assert_eq!(
        normalize_dns_selection("77.88.8.8,77.88.8.1", "yandex"),
        ("1.1.1.1,1.0.0.1".to_owned(), "cloudflare".to_owned())
    );
    assert_eq!(
        normalize_dns_selection("9.9.9.9,1.1.1.1", "custom"),
        ("9.9.9.9,1.1.1.1".to_owned(), "custom".to_owned())
    );
    assert_eq!(
        normalize_dns_selection("8.8.8.8,8.8.4.4", "google"),
        ("8.8.8.8,8.8.4.4".to_owned(), "google".to_owned())
    );
    assert_eq!(
        normalize_dns_selection("208.67.222.222,208.67.220.220", "removed-id"),
        ("208.67.222.222,208.67.220.220".to_owned(), "opendns".to_owned())
    );
}

#[test]
fn manual_dns_accepts_one_or_two_ipv4_addresses() {
    assert_eq!(manual_dns(Some("1.1.1.1"), None).unwrap(), "1.1.1.1");
    assert_eq!(
        manual_dns(Some(" 1.1.1.1 "), Some(" 1.0.0.1 ")).unwrap(),
        "1.1.1.1,1.0.0.1"
    );
}

#[test]
fn manual_dns_rejects_empty_primary_and_invalid_ipv4() {
    assert!(manual_dns(None, None).is_err());
    assert!(manual_dns(Some(""), None).is_err());
    assert!(manual_dns(Some("1.1.1.256"), None).is_err());
    assert!(manual_dns(Some("1.1.1.1"), Some("not-an-ip")).is_err());
}

#[test]
fn panel_uses_six_recursive_dns_presets_without_network_checks() {
    assert!(PANEL_HTML.contains("DNS Адреса"));
    assert!(PANEL_HTML.contains("id=\"dns_provider\""));
    assert!(PANEL_HTML.contains("<option value=\"custom\">Ручной DNS</option>"));
    assert!(PANEL_HTML.contains("<option value=\"cloudflare\" selected>Cloudflare DNS</option>"));
    assert!(PANEL_HTML.contains("id=\"dns_manual\""));
    assert!(PANEL_HTML.contains("placeholder=\"ipv4,ipv4\""));
    assert!(PANEL_HTML.contains(
        "Только рекурсивные резолверы. DNS применяется к приложениям в туннеле; \
         запросы выходят из сети VPS. Если VPS в РФ и нейтральные недоступны — задайте ручной DNS."
    ));
    assert!(PANEL_HTML.contains("NextDNS"));
    for removed in [
        "Yandex DNS",
        "Rostelecom DNS",
        "BI.ZONE DNS",
        "НСДИ DNS",
        "Xbox DNS",
        "Comms DNS",
        "Geohide DNS",
    ] {
        assert!(!PANEL_HTML.contains(removed), "{removed}");
    }
    assert!(PANEL_HTML.contains("dns_provider: provider"));
    assert!(!PANEL_HTML.contains("/api/dns-providers"));
    assert!(!PANEL_HTML.contains("dns_provider_health"));
    assert!(!PANEL_HTML.contains("refreshDnsProviderPings"));
    assert!(PANEL_HTML.contains("dns_provider: 'custom'"));
    assert!(PANEL_HTML.contains("syncDnsControls"));
}
