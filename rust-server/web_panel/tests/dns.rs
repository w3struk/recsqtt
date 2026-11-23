use crate::web_assets::PANEL_HTML;
use crate::web_panel::*;

#[test]
fn dns_provider_catalog_is_complete_and_has_exact_yandex_default() {
    assert_eq!(DNS_PROVIDERS.len(), 13);
    let yandex = dns_provider_by_id("yandex").unwrap();
    assert_eq!(provider_dns(yandex), "77.88.8.8,77.88.8.1");
    assert_eq!(dns_provider_id("1.1.1.1,1.0.0.1"), Some("cloudflare"));
    assert_eq!(
        dns_provider_id("45.90.28.181,45.90.30.181"),
        Some("nextdns")
    );
    assert_eq!(dns_provider_id("195.208.6.1,195.208.7.1"), Some("bizone"));
    assert_eq!(dns_provider_id("195.208.4.1,195.208.5.1"), Some("nsdi"));
    assert_eq!(dns_provider_id("10.0.0.1"), None);
}

#[test]
fn legacy_deploy_dns_migration_normalizes_known_partial_profiles_or_uses_yandex() {
    assert_eq!(
        deploy_dns_profile_or_yandex("111.88.96.55,203.0.113.7"),
        "111.88.96.54,111.88.96.55"
    );
    assert_eq!(
        deploy_dns_profile_or_yandex("111.88.96.51,203.0.113.7"),
        "111.88.96.54,111.88.96.55"
    );
    assert_eq!(
        deploy_dns_profile_or_yandex("95.182.120.241,203.0.113.7"),
        "193.233.112.67,193.233.112.88"
    );
    assert_eq!(
        deploy_dns_profile_or_yandex("8.8.8.8,1.1.1.1"),
        "8.8.8.8,8.8.4.4"
    );
    assert_eq!(
        deploy_dns_profile_or_yandex("203.0.113.7,198.51.100.9"),
        "77.88.8.8,77.88.8.1"
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
fn panel_uses_the_complete_dns_provider_list_without_network_checks() {
    assert!(PANEL_HTML.contains("DNS Адреса"));
    assert!(PANEL_HTML.contains("id=\"dns_provider\""));
    assert!(PANEL_HTML.contains("<option value=\"custom\">Ручной DNS</option>"));
    assert!(PANEL_HTML.contains("id=\"dns_manual\""));
    assert!(PANEL_HTML.contains("placeholder=\"ipv4,ipv4\""));
    assert!(PANEL_HTML.contains("Yandex DNS"));
    assert!(PANEL_HTML.contains("Rostelecom DNS"));
    assert!(PANEL_HTML.contains("NextDNS"));
    assert!(PANEL_HTML.contains("BI.ZONE DNS"));
    assert!(PANEL_HTML.contains("НСДИ DNS"));
    assert!(PANEL_HTML.contains("dns_provider: provider"));
    assert!(!PANEL_HTML.contains("/api/dns-providers"));
    assert!(!PANEL_HTML.contains("dns_provider_health"));
    assert!(!PANEL_HTML.contains("refreshDnsProviderPings"));
    assert!(PANEL_HTML.contains("dns_provider: 'custom'"));
    assert!(PANEL_HTML.contains("syncDnsControls"));
}
