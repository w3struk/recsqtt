/// Все продакшн-исходники панели: фасад и каждый модуль, объявленный в нём.
///
/// Список выводится из `mod X;` в фасаде, а не переписывается руками: новый
/// модуль попадает в проверки сам, а пропавший файл даёт панику вместо тихого
/// сужения покрытия.
pub(super) fn panel_sources() -> Vec<(String, String)> {
    let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"));
    let facade = std::fs::read_to_string(root.join("web_panel.rs")).expect("фасад панели");
    let mut sources = vec![("web_panel.rs".to_owned(), facade.clone())];
    let mut declared = Vec::new();
    for line in facade.lines() {
        let Some(name) = module_declaration(line) else {
            continue;
        };
        if name == "tests" {
            continue;
        }
        declared.push(name.to_owned());
        let path = root.join("web_panel").join(format!("{name}.rs"));
        sources.push((
            format!("web_panel/{name}.rs"),
            std::fs::read_to_string(&path).expect("модуль панели"),
        ));
    }
    // Файл модуля, которого нет в фасаде, выпал бы из всех проверок молча.
    let mut listed: Vec<String> = std::fs::read_dir(root.join("web_panel"))
        .expect("каталог модулей")
        .filter_map(|entry| {
            let path = entry.ok()?.path();
            if !path.is_file() || path.extension()? != "rs" {
                return None;
            }
            Some(path.file_stem()?.to_string_lossy().into_owned())
        })
        .collect();
    listed.sort();
    declared.sort();
    assert_eq!(
        listed, declared,
        "файлы web_panel/*.rs и объявления mod в фасаде разошлись:          объявите модуль в web_panel.rs или уберите файл"
    );
    assert!(
        sources.len() > 10,
        "модули панели не найдены: {} источников",
        sources.len()
    );
    sources
}

/// Имя модуля из строки объявления в любой форме: `mod x;`, `pub mod x;`,
/// `pub(crate) mod x;`, `pub(in …) mod x;`.
fn module_declaration(line: &str) -> Option<&str> {
    let line = line.trim_start();
    let line = match line.strip_prefix("pub(") {
        Some(rest) => rest.get(rest.find(')')? + 1..)?.trim_start(),
        None => line.strip_prefix("pub ").unwrap_or(line),
    };
    line.strip_prefix("mod ")?.strip_suffix(';')
}

/// Строки кода без содержимого сырых строк.
///
/// Внутри `r##"…"##` лежит JS (service worker, манифест), и строки вида
/// `const base = …` оттуда — не элементы модуля; сканировать их как код уже
/// приводило к порче пейлоада. Строка, открывающая литерал, возвращается
/// обрезанной по кавычку, чтобы объявление (`const PWA_…`) осталось видно.
pub(super) fn code_lines(source: &str) -> Vec<(usize, &str)> {
    let mut out = Vec::new();
    let mut in_raw = false;
    for (index, line) in source.lines().enumerate() {
        if in_raw {
            if line.contains("\"##;") {
                in_raw = false;
            }
            continue;
        }
        match line.find("r##\"") {
            Some(pos) => {
                out.push((index + 1, &line[..pos]));
                if !line[pos..].contains("\"##;") {
                    in_raw = true;
                }
            }
            None => out.push((index + 1, line)),
        }
    }
    out
}

pub(super) fn item_name(line: &str) -> Option<&str> {
    let line = line
        .strip_prefix("pub(crate) ")
        .or_else(|| line.strip_prefix("pub "))
        .unwrap_or(line);
    let rest = [
        "async fn ",
        "fn ",
        "struct ",
        "enum ",
        "const ",
        "static ",
        "type ",
    ]
    .iter()
    .find_map(|keyword| line.strip_prefix(keyword))?;
    let name = rest
        .split(|c: char| !(c.is_alphanumeric() || c == '_'))
        .next()?;
    (!name.is_empty() && name != "_").then_some(name)
}

/// База с одним правилом `rule_1`, прямым клиентом и группой.
pub(super) fn affected_fixture() -> crate::model::Database {
    let mut db = crate::model::Database::default();
    db.passwords.insert(
        "direct-pass".to_owned(),
        crate::model::PasswordEntry {
            routing_rule_id: "rule_1".to_owned(),
            ..Default::default()
        },
    );
    db.passwords.insert(
        "group-pass".to_owned(),
        crate::model::PasswordEntry {
            group_id: "g1".to_owned(),
            ..Default::default()
        },
    );
    db.passwords.insert(
        "overridden-pass".to_owned(),
        crate::model::PasswordEntry {
            group_id: "g1".to_owned(),
            // Собственное правило важнее группового: этого клиента
            // `rule_1` не касается, даже если его группу привязали к нему.
            routing_rule_id: "rule_2".to_owned(),
            ..Default::default()
        },
    );
    db.groups.insert(
        "g1".to_owned(),
        crate::model::ClientGroup {
            id: "g1".to_owned(),
            routing_rule_id: "rule_1".to_owned(),
            ..Default::default()
        },
    );
    db
}

pub(super) fn theme_block<'a>(document: &'a str, selector: &str) -> &'a str {
    let start = document
        .find(selector)
        .unwrap_or_else(|| panic!("`{selector}` block is missing"));
    let body = &document[start + selector.len()..];
    let end = body
        .find('}')
        .unwrap_or_else(|| panic!("`{selector}` block is not closed"));
    &body[..end]
}

pub(super) fn theme_vars(block: &str) -> std::collections::BTreeMap<String, String> {
    block
        .lines()
        .filter_map(|line| {
            let declaration = line.trim().strip_prefix("--")?;
            let (name, value) = declaration.split_once(':')?;
            Some((
                name.to_string(),
                value.trim().trim_end_matches(';').to_string(),
            ))
        })
        .collect()
}
