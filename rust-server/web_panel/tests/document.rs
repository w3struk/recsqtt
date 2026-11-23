use super::support::*;
use crate::web_assets::CLIENT_SUB_HTML;
use crate::web_assets::GROUP_SUB_HTML;
use crate::web_assets::LOGIN_HTML;
use crate::web_assets::PANEL_HTML;
use crate::web_assets::THEME_TOKENS;

#[test]
fn main_client_render_refreshes_presence_badges_on_every_branch() {
    let template = PANEL_HTML.as_str();
    let start = template
        .find("function renderMainClientsCards()")
        .expect("main client render function");
    let rest = &template[start..];
    let end = rest
        .find("\n        let clientsInFlight")
        .expect("end of main client render function");
    let body = &rest[..end];
    assert!(
        body.matches("refreshPresenceBadges();").count() >= 2,
        "both render branches must restore presence badges after rebuilding cards"
    );
}

#[test]
fn panel_template_uses_last_online_and_payload_window() {
    let template = PANEL_HTML.as_str();
    assert!(template.contains("last_online"));
    assert!(template.contains("presence_window_secs"));
    assert!(template.contains("server_now"));
    assert!(template.contains("data-last-online"));
    assert!(template.contains("[data-presence=\"badge\"]"));
    assert!(!template.contains(".online"));
}

#[test]
fn client_card_badges_are_compact_and_non_wrapping() {
    let template = PANEL_HTML.as_str();
    assert!(template.contains(".client-card-badges"));
    assert!(template.contains(".client-card-badges .badge"));
    assert!(template.contains("white-space: nowrap"));
    assert!(template.contains("min-height: 34px"));
    assert!(template.contains(".client-card-title > span"));
    assert!(template.contains("min-width: 0"));
}

#[test]
fn panel_html_has_no_master_password_and_guides_first_client() {
    assert!(!PANEL_HTML.contains("id=\"mainpass\""));
    assert!(!PANEL_HTML.contains("master-password"));
    assert!(!PANEL_HTML.contains("savedMainPassword"));
    assert!(!PANEL_HTML.contains("isMain"));
    assert!(!PANEL_HTML.contains("restart_required"));
    assert!(PANEL_HTML.contains("Клиентов ещё нет. Создайте первого клиента."));
}

#[test]
fn mobile_panel_keeps_compact_labels_and_consistent_memory_value() {
    assert!(PANEL_HTML.contains("Использование памяти"));
    assert!(PANEL_HTML.contains("#ram_val { white-space: nowrap;"));
    assert!(PANEL_HTML.contains("id=\"ram_detail\""));
    assert!(PANEL_HTML.contains("x.ram_used"));
    assert!(PANEL_HTML.contains("text-size-adjust: none"));
    assert!(PANEL_HTML.contains(".toggle-row label"));
    assert!(PANEL_HTML.contains("white-space: nowrap"));
}

#[test]
fn routing_rule_dialog_reads_mode_then_list_then_hint_then_template_in_both_blocks() {
    let html = PANEL_HTML.as_str();
    let expected_order = [
        "id=\"ruleDomainsMode\"",
        "id=\"ruleDomains\"",
        "id=\"ruleTemplateDomainsRow\"",
        "Вместе с готовым списком RU — до 1024 доменов;",
        "id=\"ruleIpMode\"",
        "id=\"ruleIps\"",
        "До 512 адресов.",
        "id=\"ruleTemplateIpsRow\"",
        "Применить правило к:",
    ];
    let mut previous = 0usize;
    for marker in expected_order {
        let at = html
            .find(marker)
            .unwrap_or_else(|| panic!("в разметке панели нет маркера {marker}"));
        assert!(
            at > previous,
            "маркер {marker} стоит раньше предыдущего блока разметки"
        );
        previous = at;
    }
    assert!(
        !html.contains("До 512 адресов;"),
        "единый хинт про лимиты должен быть разбит на доменный и адресный"
    );
}

#[test]
fn app_whitelist_hides_the_ru_preset_like_domains_and_ips() {
    let html = PANEL_HTML.as_str();
    assert!(
        html.contains("id=\"ruleMode\" onchange=\"onRuleAppsModeChange()\""),
        "смена режима приложений должна скрывать строку пресета"
    );
    assert!(
        html.contains("function onRuleAppsModeChange()"),
        "обработчик режима приложений отсутствует"
    );
    assert!(
        html.contains(
            "preset_apps: (mode === 'whitelist') ? false : document.getElementById('rulePresetApps').checked"
        ),
        "в белом режиме приложений preset_apps обязан уходить false, как template_domains и template_ips"
    );
}

#[test]
fn panel_displays_compact_udp_traffic_counters() {
    assert!(PANEL_HTML.contains("id=\"traffic_ingress\""));
    assert!(PANEL_HTML.contains("x.udp_rx_packets"));
    assert!(PANEL_HTML.contains("x.udp_tx_packets"));
    assert!(PANEL_HTML.contains("const compactCounter = input =>"));
    assert!(PANEL_HTML.contains("const units = [\"\", \"K\", \"M\", \"G\", \"T\", \"P\", \"E\"]"));
    assert!(PANEL_HTML.contains("#traffic_ingress { white-space: nowrap;"));
    assert!(PANEL_HTML.contains(
        "RX ${compactCounter(x.udp_rx_packets)} · TX ${compactCounter(x.udp_tx_packets)}"
    ));
    assert!(!PANEL_HTML.contains("UDP RX ${Number(x.udp_rx_packets) || 0}"));
}

#[test]
fn participant_cards_contain_ip_info() {
    assert!(PANEL_HTML.contains("IP:"));
}

#[test]
fn theme_tokens_are_identical_across_all_documents() {
    let documents = [
        ("LOGIN_HTML", LOGIN_HTML.as_str()),
        ("CLIENT_SUB_HTML", CLIENT_SUB_HTML.as_str()),
        ("GROUP_SUB_HTML", GROUP_SUB_HTML.as_str()),
        ("PANEL_HTML", PANEL_HTML.as_str()),
    ];
    for (name, document) in documents {
        assert_eq!(
            document.matches(THEME_TOKENS).count(),
            1,
            "{name} must embed exactly one canonical token block"
        );
        let light = theme_vars(theme_block(document, ":root"));
        let dark = theme_vars(theme_block(document, "[data-theme=\"dark\"]"));
        assert_eq!(light.len(), THEME_LIGHT.len(), "{name}: light token count");
        assert_eq!(dark.len(), THEME_DARK.len(), "{name}: dark token count");
        for (key, value) in THEME_LIGHT {
            assert_eq!(
                light.get(key).map(String::as_str),
                Some(value),
                "{name}: light --{key}"
            );
        }
        for (key, value) in THEME_DARK {
            assert_eq!(
                dark.get(key).map(String::as_str),
                Some(value),
                "{name}: dark --{key}"
            );
        }
        let without_tokens = document.replace(THEME_TOKENS, "");
        for hardcoded in [
            "#ef4444", "#10b981", "#3b82f6", "#2563eb", "#0077ff", "#0060cc",
        ] {
            assert!(
                !without_tokens.contains(hardcoded),
                "{name} still contains hardcoded {hardcoded}"
            );
        }
    }
}

/// Карточка правила обязана показывать включённый пресет приложений:
/// раньше «Шаблоны:» строились только из доменов и адресов.
#[test]
fn routing_rule_card_shows_the_app_preset() {
    assert!(PANEL_HTML.contains("if (rule.preset_apps) templateParts.push('Приложения');"));
    assert!(PANEL_HTML.contains("Шаблоны: ${templateLabel}"));
}

/// Справочник API обязан документировать все три готовых списка, а не
/// только адреса: домены и пресет приложений — такие же части правила.
#[test]
fn api_reference_mentions_domains_ips_and_apps_templates() {
    for path in [
        "/api/template-domains",
        "/api/template-ips",
        "/api/preset-apps",
    ] {
        assert!(PANEL_HTML.contains(path), "API reference misses {path}");
    }
    for field in ["preset_apps", "template_domains", "template_ips"] {
        assert!(
            PANEL_HTML.contains(field),
            "API reference misses the {field} flag"
        );
    }
}

const THEME_LIGHT: [(&str, &str); 13] = [
    ("bg-color", "#f8fafc"),
    ("surface", "#ffffff"),
    ("surface-hover", "#f1f5f9"),
    ("primary", "#0077ff"),
    ("primary-hover", "#0060cc"),
    ("text-main", "#0f172a"),
    ("text-muted", "#64748b"),
    ("border", "#e2e8f0"),
    ("danger", "#ef4444"),
    ("success", "#10b981"),
    ("icon-bg", "rgba(0, 119, 255, 0.1)"),
    ("glass", "rgba(255, 255, 255, 0.9)"),
    ("header-glass", "rgba(255, 255, 255, 0.8)"),
];

const THEME_DARK: [(&str, &str); 13] = [
    ("bg-color", "#000000"),
    ("surface", "#0a0a0c"),
    ("surface-hover", "#161619"),
    ("primary", "#0077ff"),
    ("primary-hover", "#0060cc"),
    ("text-main", "#f4f4f5"),
    ("text-muted", "#9ca3af"),
    ("border", "#1f1f23"),
    ("danger", "#ef4444"),
    ("success", "#10b981"),
    ("icon-bg", "rgba(0, 119, 255, 0.15)"),
    ("glass", "rgba(10, 10, 12, 0.85)"),
    ("header-glass", "rgba(0, 0, 0, 0.8)"),
];
