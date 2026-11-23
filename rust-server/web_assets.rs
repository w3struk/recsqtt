//! Встроенные веб-ассеты панели.
//!
//! Разметка, стили и скрипты лежат отдельными файлами в `rust-server/web/**`,
//! а не одним литералом на шесть тысяч строк: так правка одной области экрана
//! не требует читать остальные. Всё скомпилировано в бинарник через
//! `include_str!` и `concat!` — ни один файл не читается с диска в рантайме,
//! сервер по-прежнему остаётся одним статическим бинарником.
//!
//! `panel/index.html` держит по маркеру на каждую часть (`/*__PANEL_CSS__*/`,
//! `//__PANEL_JS__` и `<!--__PANEL_*__-->`); в первый рендер `assemble_panel`
//! заменяет каждый маркер его блоком. Порядок перечисления в `PANEL_CSS`,
//! `PANEL_JS` и `PANEL_PARTS` — это порядок каскада для CSS и порядок
//! исполнения для JS, поэтому менять его нельзя: CSS и JS попадают внутрь
//! одного `<style>` и одного `<script>`, и все `const`/`let` остаются в общей
//! области видимости.

use std::sync::LazyLock;

/// Общие переменные темы: подставляются в каждый документ вместо
/// `/*__THEME_TOKENS__*/`, чтобы панель, логин и страницы подписки не
/// расходились по палитре.
pub const THEME_TOKENS: &str = include_str!("web/theme.css");

/// Минифицированный сторонний генератор QR-кода (qrcode-generator, MIT).
pub const QRCODE_JS: &str = include_str!("web/vendor/qrcode.min.js");

const PANEL_CSS: &str = concat!(
    include_str!("web/panel/css/00-base.css"),
    include_str!("web/panel/css/10-header.css"),
    include_str!("web/panel/css/15-api-docs.css"),
    include_str!("web/panel/css/20-layout-stats-tabs.css"),
    include_str!("web/panel/css/25-clients-table.css"),
    include_str!("web/panel/css/30-settings.css"),
    include_str!("web/panel/css/32-proxy.css"),
    include_str!("web/panel/css/35-forms.css"),
    include_str!("web/panel/css/40-dialog-base.css"),
    include_str!("web/panel/css/42-client-cards.css"),
    include_str!("web/panel/css/43-rule-card.css"),
    include_str!("web/panel/css/44-qr.css"),
    include_str!("web/panel/css/45-hub.css"),
    include_str!("web/panel/css/48-client-card-details.css"),
    include_str!("web/panel/css/55-rule-targets.css"),
    include_str!("web/panel/css/57-dialog-header.css"),
    include_str!("web/panel/css/60-toast.css"),
    include_str!("web/panel/css/70-responsive.css"),
    include_str!("web/panel/css/80-logs.css"),
    include_str!("web/panel/css/85-form-controls.css"),
    include_str!("web/panel/css/88-night-switch.css"),
);

const PANEL_JS: &str = concat!(
    include_str!("web/panel/js/00-boot.js"),
    include_str!("web/panel/js/10-utils.js"),
    include_str!("web/panel/js/20-toast-confirm.js"),
    include_str!("web/panel/js/30-stats.js"),
    include_str!("web/panel/js/40-settings.js"),
    include_str!("web/panel/js/45-proxy.js"),
    include_str!("web/panel/js/50-presence.js"),
    include_str!("web/panel/js/52-cards-render.js"),
    include_str!("web/panel/js/54-clients-list.js"),
    include_str!("web/panel/js/56-groups.js"),
    include_str!("web/panel/js/60-routing.js"),
    include_str!("web/panel/js/62-api-docs.js"),
    include_str!("web/panel/js/64-import-export.js"),
    include_str!("web/panel/js/66-templates.js"),
    include_str!("web/panel/js/68-rule-delete.js"),
    include_str!("web/panel/js/70-client-dialogs.js"),
    include_str!("web/panel/js/72-hub.js"),
    include_str!("web/panel/js/74-logs.js"),
    include_str!("web/panel/js/76-actions.js"),
    include_str!("web/panel/js/90-init.js"),
);

const PANEL_HTML_TEMPLATE: &str = include_str!("web/panel/index.html");
const LOGIN_HTML_TEMPLATE: &str = include_str!("web/login.html");
const CLIENT_SUB_HTML_TEMPLATE: &str = include_str!("web/client_sub.html");
const GROUP_SUB_HTML_TEMPLATE: &str = include_str!("web/group_sub.html");

/// Маркер в шаблоне панели и блок, который его замещает.
struct PanelPart {
    marker: &'static str,
    block: &'static str,
}

const PANEL_PARTS: &[PanelPart] = &[
    PanelPart {
        marker: "/*__PANEL_CSS__*/",
        block: PANEL_CSS,
    },
    PanelPart {
        marker: "//__PANEL_JS__",
        block: PANEL_JS,
    },
    PanelPart {
        marker: "<!--__PANEL_HEADER__-->",
        block: include_str!("web/panel/html/10-header.html"),
    },
    PanelPart {
        marker: "<!--__PANEL_MONITORING__-->",
        block: include_str!("web/panel/html/20-monitoring.html"),
    },
    PanelPart {
        marker: "<!--__PANEL_CLIENTS__-->",
        block: include_str!("web/panel/html/30-clients.html"),
    },
    PanelPart {
        marker: "<!--__PANEL_LOGS__-->",
        block: include_str!("web/panel/html/40-logs.html"),
    },
    PanelPart {
        marker: "<!--__PANEL_SETTINGS__-->",
        block: include_str!("web/panel/html/50-settings.html"),
    },
    PanelPart {
        marker: "<!--__PANEL_DIALOGS_MISC__-->",
        block: include_str!("web/panel/html/60-dialogs-misc.html"),
    },
    PanelPart {
        marker: "<!--__PANEL_DIALOGS_CLIENT__-->",
        block: include_str!("web/panel/html/62-dialogs-client.html"),
    },
    PanelPart {
        marker: "<!--__PANEL_DIALOGS_GROUP__-->",
        block: include_str!("web/panel/html/64-dialogs-group.html"),
    },
    PanelPart {
        marker: "<!--__PANEL_DIALOGS_ROUTING__-->",
        block: include_str!("web/panel/html/66-dialogs-routing.html"),
    },
    PanelPart {
        marker: "<!--__PANEL_DIALOGS_TEMPLATES__-->",
        block: include_str!("web/panel/html/68-dialogs-templates.html"),
    },
    PanelPart {
        marker: "<!--__PANEL_DIALOGS_HUB__-->",
        block: include_str!("web/panel/html/70-dialogs-hub.html"),
    },
    PanelPart {
        marker: "<!--__PANEL_DIALOGS_QR__-->",
        block: include_str!("web/panel/html/72-dialogs-qr-batch.html"),
    },
    PanelPart {
        marker: "<!--__PANEL_DIALOGS_IMPORT_EXPORT__-->",
        block: include_str!("web/panel/html/74-dialogs-import-export.html"),
    },
    PanelPart {
        marker: "<!--__PANEL_DIALOGS_API_DOCS__-->",
        block: include_str!("web/panel/html/76-dialogs-api-docs.html"),
    },
    PanelPart {
        marker: "<!--__PANEL_DIALOGS_REBOOT__-->",
        block: include_str!("web/panel/html/78-dialogs-reboot.html"),
    },
];

/// Заменяет строку-маркер (целиком, с её отступом) на готовый блок.
///
/// Маркер сравнивается после `trim`, поэтому лишние или срезанные пробелы в
/// строке-маркере склейку не ломают. При этом байты всего остального документа
/// сохраняются как есть — это позволяет сверять собранный документ побайтово.
fn splice_block(document: &str, marker: &str, block: &str) -> String {
    let mut out = String::with_capacity(document.len() + block.len());
    for line in document.split_inclusive('\n') {
        if line.trim() == marker {
            out.push_str(block);
        } else {
            out.push_str(line);
        }
    }
    out
}

fn assemble_panel() -> String {
    let mut document = PANEL_HTML_TEMPLATE.to_owned();
    for part in PANEL_PARTS {
        document = splice_block(&document, part.marker, part.block);
    }
    expand_theme_tokens(&document)
}

pub fn expand_theme_tokens(template: &str) -> String {
    template.replace("/*__THEME_TOKENS__*/", THEME_TOKENS)
}

pub static LOGIN_HTML: LazyLock<String> =
    LazyLock::new(|| expand_theme_tokens(LOGIN_HTML_TEMPLATE));
pub static CLIENT_SUB_HTML: LazyLock<String> =
    LazyLock::new(|| expand_theme_tokens(CLIENT_SUB_HTML_TEMPLATE));
pub static GROUP_SUB_HTML: LazyLock<String> =
    LazyLock::new(|| expand_theme_tokens(GROUP_SUB_HTML_TEMPLATE));
pub static PANEL_HTML: LazyLock<String> = LazyLock::new(assemble_panel);

#[cfg(test)]
mod tests {
    use super::{PANEL_CSS, PANEL_HTML, PANEL_HTML_TEMPLATE, PANEL_JS, PANEL_PARTS};

    #[test]
    fn panel_template_carries_exactly_one_marker_per_part() {
        for part in PANEL_PARTS {
            assert_eq!(
                PANEL_HTML_TEMPLATE.matches(part.marker).count(),
                1,
                "маркер {} должен встречаться в шаблоне ровно один раз",
                part.marker
            );
        }
    }

    #[test]
    fn assembled_panel_consumes_every_marker() {
        // Если маркер срезали или переписали, склейка молча оставит его в
        // документе — этот тест ловит такую правку шаблона.
        for part in PANEL_PARTS {
            assert!(
                !PANEL_HTML.contains(part.marker),
                "маркер {} остался в собранном документе",
                part.marker
            );
        }
        assert!(!PANEL_HTML.contains("/*__THEME_TOKENS__*/"));
    }

    #[test]
    fn asset_blocks_do_not_carry_another_marker() {
        // splice_block заменяет любую строку, равную маркеру после trim. Маркер,
        // попавший в чужой блок, вклеил бы туда целую часть документа.
        for part in PANEL_PARTS {
            for other in PANEL_PARTS {
                assert!(
                    !part.block.contains(other.marker),
                    "блок {} содержит маркер {}",
                    part.marker,
                    other.marker
                );
            }
        }
    }

    #[test]
    fn asset_blocks_end_with_a_newline() {
        // Склейка чанков идёт без разделителя: блок без завершающего перевода
        // строки слипся бы с первым правилом/объявлением следующего чанка.
        assert!(PANEL_CSS.ends_with('\n'));
        assert!(PANEL_JS.ends_with('\n'));
        for part in PANEL_PARTS {
            assert!(part.block.ends_with('\n'), "блок {}", part.marker);
        }
    }

    #[test]
    fn part_markers_are_unique() {
        // Дубликат маркера прошёл бы проверку «ровно одно вхождение в шаблоне»
        // и молча выкинул бы вторую часть: первый splice съел бы маркер.
        let mut markers: Vec<&str> = PANEL_PARTS.iter().map(|part| part.marker).collect();
        markers.sort_unstable();
        markers.dedup();
        assert_eq!(
            markers.len(),
            PANEL_PARTS.len(),
            "в PANEL_PARTS есть повторяющийся маркер"
        );
    }

    /// Проверяет, что каждый файл каталога встроен в блок ровно один раз.
    ///
    /// Файл, забытый в списке `PANEL_CSS`/`PANEL_JS`, компилируется и проходит
    /// все остальные тесты, но его правила просто не доезжают до страницы;
    /// дубликат строки в списке, наоборот, встраивает правила дважды и меняет
    /// каскад. Оба случая видны здесь.
    fn assert_dir_embedded(dir: &str, block: &str) {
        let base = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join(dir);
        let mut files = 0usize;
        for entry in std::fs::read_dir(&base).expect("каталог ассетов") {
            let path = entry.expect("элемент каталога").path();
            if !path.is_file() {
                continue;
            }
            let name = path
                .file_name()
                .expect("имя ассета")
                .to_string_lossy()
                .into_owned();
            let text = std::fs::read_to_string(&path).expect("ассет");
            assert_eq!(
                block.matches(text.as_str()).count(),
                1,
                "{dir}/{name} должен быть встроен ровно один раз: проверьте список в web_assets.rs"
            );
            files += 1;
        }
        assert!(files > 0, "каталог {dir} пуст");
    }

    #[test]
    fn every_css_file_is_embedded_exactly_once() {
        assert_dir_embedded("web/panel/css", PANEL_CSS);
    }

    #[test]
    fn every_js_file_is_embedded_exactly_once() {
        assert_dir_embedded("web/panel/js", PANEL_JS);
    }

    #[test]
    fn every_markup_part_is_embedded_exactly_once() {
        assert_dir_embedded("web/panel/html", PANEL_HTML.as_str());
    }

    #[test]
    fn theme_marker_lives_in_css_only() {
        // Токены темы подставляются подстрокой по всему документу, поэтому
        // маркер обязан быть ровно один — в стилях. Второй экземпляр в скриптах
        // или разметке раскрылся бы в посторонний CSS.
        const MARKER: &str = "/*__THEME_TOKENS__*/";
        for part in PANEL_PARTS {
            let expected = usize::from(part.marker == "/*__PANEL_CSS__*/");
            assert_eq!(
                part.block.matches(MARKER).count(),
                expected,
                "блок {} должен содержать маркер темы {expected} раз(а)",
                part.marker
            );
        }
    }

    #[test]
    fn assembled_panel_is_a_complete_document() {
        // Шаблон начинается с перевода строки после открывающей кавычки литерала.
        assert!(PANEL_HTML.trim_start().starts_with("<!DOCTYPE html>"));
        assert!(PANEL_HTML.trim_end().ends_with("</html>"));
    }

    #[test]
    fn asset_order_is_pinned() {
        // Порядок чанков — это порядок каскада CSS и порядок исполнения JS.
        // Пары опорных фрагментов ловят случайную перестановку в списках.
        let card = PANEL_CSS.find(".client-cards-grid").expect("client-cards");
        let rule_targets = PANEL_CSS
            .find(".rule-targets-container")
            .expect("rule-targets");
        assert!(card < rule_targets, "карточки должны идти до целей правил");

        let boot = PANEL_JS
            .find("navigator.serviceWorker.register")
            .expect("регистрация service worker");
        let intervals = PANEL_JS
            .find("startUpdateInterval(savedInterval)")
            .expect("запуск интервалов обновления");
        assert!(boot < intervals, "boot-чанк должен идти первым");

        let monitoring = PANEL_HTML
            .find("id=\"monitoring-section\"")
            .expect("мониторинг");
        let api_docs = PANEL_HTML.find("id=\"apiDocsDlg\"").expect("API-доки");
        assert!(monitoring < api_docs, "мониторинг должен идти до диалогов");
    }
}
