use crate::web_assets::LOGIN_HTML;
use crate::web_assets::PANEL_HTML;
use crate::web_panel::*;

#[test]
fn service_worker_payload_matches_its_pinned_bytes() {
    // Пейлоад уходит в браузер как есть, и подстрочные проверки ниже уже один раз
    // пропустили порчу (внутрь литерала попал Rust-префикс видимости: строка JS
    // остаётся валидным строковым литералом, компилятор молчит, а браузер получает
    // SyntaxError). Поэтому байты закреплены хешем. Версию в CACHE_NAME меняет
    // scripts/bump_version.sh, так что сама строка версии из хеша исключена.
    use sha2::{Digest, Sha256};
    let normalized: String = PWA_SERVICE_WORKER
        .lines()
        .map(|line| {
            if line.starts_with("const CACHE_NAME = ") {
                "const CACHE_NAME"
            } else {
                line
            }
        })
        .collect::<Vec<_>>()
        .join("\n");
    let digest: String = Sha256::digest(normalized.as_bytes())
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect();
    assert_eq!(
        digest, "cd2d241a50d8cfa5f6ca2258f4459f26399e4b0104e74b3c06d7593c7d3f1934",
        "service worker payload changed: правка должна быть осознанной"
    );
}

#[test]
fn pwa_payloads_carry_no_rust_syntax() {
    // PWA_* — это отдаваемые клиенту байты, а не Rust-код. Слепой рефакторинг
    // уже однажды вписал `pub(crate)` внутрь service worker'а: строка JS при
    // этом остаётся валидным Rust-строковым литералом, поэтому компилятор молчит,
    // а браузер получает синтаксическую ошибку и перестаёт обновлять панель.
    for (name, payload) in [
        ("PWA_MANIFEST", PWA_MANIFEST),
        ("PWA_ICON_SVG", PWA_ICON_SVG),
        ("PWA_SERVICE_WORKER", PWA_SERVICE_WORKER),
    ] {
        assert!(
            !payload.contains("pub(crate)"),
            "{name} содержит Rust-префикс видимости"
        );
    }
    assert!(PWA_SERVICE_WORKER.starts_with("const CACHE_NAME = 'csqtt-panel-shell-"));
    assert!(PWA_SERVICE_WORKER.contains("\nconst base = "));
    assert!(PWA_SERVICE_WORKER.contains("\nconst SHELL_ASSETS = "));
}

#[test]
fn pwa_uses_standalone_shell_without_caching_authenticated_panel_data() {
    assert!(PWA_MANIFEST.contains("\"display\": \"standalone\""));
    assert!(PWA_MANIFEST.contains("\"start_url\": \"/\""));
    assert!(PWA_MANIFEST.contains("\"sizes\": \"192x192\""));
    assert!(PWA_MANIFEST.contains("pwa-icon-192.png"));
    assert!(PWA_MANIFEST.contains("pwa-icon-512.png"));
    assert!(LOGIN_HTML.contains("manifest.webmanifest"));
    assert!(PANEL_HTML.contains("manifest.webmanifest"));
    assert!(LOGIN_HTML.contains("pwa-icon-192.png"));
    assert!(PANEL_HTML.contains("pwa-icon-192.png"));
    assert!(PWA_SERVICE_WORKER.contains("url.pathname.startsWith('/api/')"));
    assert!(PWA_SERVICE_WORKER.contains("SHELL_ASSETS"));
    assert!(!PWA_SERVICE_WORKER.contains("OFFLINE_PAGE"));
    assert!(!PWA_SERVICE_WORKER.contains("request.mode === 'navigate'"));
}

#[test]
fn pwa_raster_icon_fallbacks_are_valid_pngs() {
    const PNG_SIGNATURE: &[u8] = b"\x89PNG\r\n\x1a\n";
    assert!(PWA_ICON_192_PNG.starts_with(PNG_SIGNATURE));
    assert!(PWA_ICON_512_PNG.starts_with(PNG_SIGNATURE));
    assert!(PWA_ICON_512_PNG.len() > PWA_ICON_192_PNG.len());
}
