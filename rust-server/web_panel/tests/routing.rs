use super::support::*;
use crate::web_panel::*;

#[test]
fn rule_affected_passwords_covers_direct_clients_and_group_members() {
    let affected = rule_affected_passwords(&affected_fixture(), "rule_1");
    assert_eq!(
        affected,
        vec!["direct-pass".to_owned(), "group-pass".to_owned()],
        "прямые владельцы правила и члены привязанной группы без собственного правила"
    );
    assert!(
        !affected.contains(&"overridden-pass".to_owned()),
        "клиент с собственным правилом не зависит от групповой привязки"
    );
}

#[test]
fn rule_affected_passwords_ignores_empty_rule_id() {
    // Отвязка «на ничего» никого не зовёт на GETCONF: пустой идентификатор
    // правила — не сигнал изменения.
    assert!(rule_affected_passwords(&affected_fixture(), "").is_empty());
}

#[test]
fn group_affected_passwords_covers_members_and_skips_clients_with_own_rule() {
    // Группа g1 привязана к rule_1: участников касается, клиента с
    // собственным rule_2 — нет (его групповое правило не пересекает).
    assert_eq!(
        group_affected_passwords(&affected_fixture(), "g1"),
        vec!["group-pass".to_owned()]
    );
    // Незнакомая группа — пустой набор, а не паника.
    assert!(group_affected_passwords(&affected_fixture(), "nope").is_empty());
}

#[test]
fn group_affected_passwords_excludes_direct_bindings() {
    let mut db = affected_fixture();
    // Клиент с собственным правилом не состоит в группе, поэтому
    // перепривязка группы его не затрагивает.
    db.passwords.insert(
        "owner-pass".to_owned(),
        crate::model::PasswordEntry {
            ip: "10.66.67.2".to_owned(),
            routing_rule_id: "rule_1".to_owned(),
            ..Default::default()
        },
    );
    assert_eq!(
        group_affected_passwords(&db, "g1"),
        vec!["group-pass".to_owned()]
    );
}

/// Union «до/после» сохраняет участника и после снятия правила с группы:
/// набор строится по членству, поэтому участник остаётся в обеих частях.
#[test]
fn group_rebinding_keeps_the_member_in_the_union() {
    let mut db = affected_fixture();
    let before = group_affected_passwords(&db, "g1");
    assert_eq!(before, vec!["group-pass".to_owned()]);

    db.groups
        .get_mut("g1")
        .expect("группа из фикстуры")
        .routing_rule_id
        .clear();
    let after = group_affected_passwords(&db, "g1");
    assert_eq!(after, vec!["group-pass".to_owned()]);

    let merged = merge_affected_passwords(&before, &after);
    assert_eq!(merged, vec!["group-pass".to_owned()]);
}

#[test]
fn merge_affected_passwords_is_deduped_and_ordered() {
    let before = vec!["b".to_owned(), "a".to_owned()];
    let after = vec!["a".to_owned(), "c".to_owned()];
    assert_eq!(
        merge_affected_passwords(&before, &after),
        vec!["a".to_owned(), "b".to_owned(), "c".to_owned()]
    );
    assert!(merge_affected_passwords(&[], &[]).is_empty());
    assert_eq!(
        merge_affected_passwords(&["a".to_owned()], &["a".to_owned()]),
        vec!["a".to_owned()]
    );
}

#[test]
fn batch_group_affected_passwords_notifies_only_moved_clients_without_own_rule() {
    let mut db = affected_fixture();
    db.passwords.insert(
        "b-pass".to_owned(),
        crate::model::PasswordEntry {
            group_id: "g1".to_owned(),
            ..Default::default()
        },
    );
    let moved = vec![
        "group-pass".to_owned(),
        "overridden-pass".to_owned(),
        "direct-pass".to_owned(),
        "b-pass".to_owned(),
    ];
    assert_eq!(
        batch_group_affected_passwords(&db, &moved, "g2"),
        vec!["b-pass".to_owned(), "group-pass".to_owned()],
        "клиенты с собственным правилом от группы не зависят; будим только тех, кто без него"
    );
}

#[test]
fn batch_group_affected_passwords_skips_same_group_but_unbind_notifies() {
    let db = affected_fixture();
    // Перевод в ту же группу ничего не меняет.
    assert!(batch_group_affected_passwords(&db, &["group-pass".to_owned()], "g1").is_empty());
    // Снятие с группы (пустая цель) меняет разрешённое правило участника.
    assert_eq!(
        batch_group_affected_passwords(&db, &["group-pass".to_owned()], ""),
        vec!["group-pass".to_owned()]
    );
}

#[test]
fn batch_group_affected_passwords_dedups_and_skips_unknown_or_empty() {
    let db = affected_fixture();
    let moved = vec![
        "group-pass".to_owned(),
        "group-pass".to_owned(),
        "nonexistent".to_owned(),
        String::new(),
    ];
    assert_eq!(
        batch_group_affected_passwords(&db, &moved, "g2"),
        vec!["group-pass".to_owned()]
    );
    assert!(batch_group_affected_passwords(&db, &[], "g2").is_empty());
}

#[test]
fn undeliverable_rule_is_rejected_with_400() {
    let mut rule = crate::model::RoutingRule {
        id: "r1".to_owned(),
        ..Default::default()
    };
    rule.ips = (0..513)
        .map(|i| format!("10.0.{}.{}/32", (i / 256) % 256, i % 256))
        .collect();
    let response = routing_rule_validation_response(&rule).expect("rule must be rejected");
    assert_eq!(response.status(), axum::http::StatusCode::BAD_REQUEST);
}

#[test]
fn rule_request_without_preset_flag_keeps_the_preset_enabled() {
    // a cached panel JS predating the flag must not silently opt the rule out
    let request: CreateRoutingRuleRequest = serde_json::from_str(r#"{"name":"r"}"#).unwrap();
    assert!(request.preset_apps);
    let disabled: CreateRoutingRuleRequest =
        serde_json::from_str(r#"{"name":"r","preset_apps":false}"#).unwrap();
    assert!(!disabled.preset_apps);
}

#[test]
fn preset_apps_payload_matches_the_shipped_file_shape() {
    // The set of entries is owned by whoever edits ru_apps_preset.json, so
    // this test pins only the serialized shape and package-name validity,
    // never the concrete applications or the list length.
    // Source of truth is `preset_apps::valid_package_name`; this is a copy,
    // because the canonical validator is private to that module and widening
    // its visibility just for a test was not worth it.
    fn is_valid_package(name: &str) -> bool {
        if name.is_empty() || name.len() > 255 || name.contains(char::is_whitespace) {
            return false;
        }
        name.split('.').all(|segment| {
            let mut chars = segment.chars();
            matches!(chars.next(), Some(first) if first.is_ascii_alphabetic() || first == '_')
                && chars.all(|ch| ch.is_ascii_alphanumeric() || ch == '_')
        })
    }

    let value = serde_json::to_value(crate::preset_apps::preset_apps()).unwrap();
    for level in ["red", "orange"] {
        let entries = value[level]
            .as_array()
            .unwrap_or_else(|| panic!("preset `{level}` is not an array"));
        assert!(!entries.is_empty(), "preset `{level}` is empty");
        for entry in entries {
            assert!(
                entry.is_object(),
                "preset `{level}` entry is not an object: {entry}"
            );
            let package = entry["package"]
                .as_str()
                .unwrap_or_else(|| panic!("preset `{level}` entry has no string package: {entry}"));
            // A bare string entry in the file parses with an empty name, and
            // the panel falls back to `e.name || e.package`, so only the type
            // matters here: never pin the name's content.
            let _name = entry["name"]
                .as_str()
                .unwrap_or_else(|| panic!("preset `{level}` entry has no string name: {entry}"));
            assert!(
                !package.is_empty(),
                "preset `{level}` entry has an empty package: {entry}"
            );
            assert!(
                is_valid_package(package),
                "preset `{level}` has an invalid package: {package}"
            );
        }
    }
}
