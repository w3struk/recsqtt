use super::*;

#[tokio::test]
async fn export_and_import_preserve_vk_user_ids() {
    let mut db = Database {
        vk_access_token: "vk1.global".to_owned(),
        vk_access_token_user_id: "100200".to_owned(),
        ..Database::default()
    };
    db.groups.insert(
        "g1".to_owned(),
        model::ClientGroup {
            id: "g1".to_owned(),
            name: "Group".to_owned(),
            vk_token: Some("vk1.group".to_owned()),
            vk_token_user_id: Some("300400".to_owned()),
            ..Default::default()
        },
    );
    let source = Fixture::new(db).await;
    let export = source.export().await;

    let target = Fixture::new(Database::default()).await;
    let response = target.request("POST", "/api/import", export).await;
    assert_eq!(response.status(), StatusCode::OK);
    {
        let db = target.app.db.read().await;
        assert_eq!(db.vk_access_token, "vk1.global");
        assert_eq!(db.vk_access_token_user_id, "100200");
        assert_eq!(db.groups["g1"].vk_token_user_id.as_deref(), Some("300400"));
    }
    let persisted = model::load_database(&target.app.config_dir).unwrap();
    assert_eq!(persisted.vk_access_token_user_id, "100200");
    assert_eq!(
        persisted.groups["g1"].vk_token_user_id.as_deref(),
        Some("300400")
    );
}

fn large_rule() -> RoutingRule {
    RoutingRule {
        id: "rule_large".to_owned(),
        name: "Large exported rule".to_owned(),
        preset_apps: false,
        direct_domains: (0..512)
            .map(|i| format!("d{i:04}.{}.example.com", "a".repeat(24)))
            .collect(),
        ..Default::default()
    }
}

#[tokio::test]
async fn import_round_trips_one_exported_rule_larger_than_16_kib() {
    let rule = large_rule();
    crate::protocol::validate_routing_rule(&rule).unwrap();
    let rule_bytes = serde_json::to_vec(&rule).unwrap().len();
    assert!(rule_bytes > WEB_BODY_LIMIT_BYTES);
    let mut db = Database::default();
    db.routing_rules.insert(rule.id.clone(), rule.clone());
    let source = Fixture::new(db).await;
    let export_body = source.export().await;
    let export: Value = serde_json::from_slice(&export_body).unwrap();
    assert_eq!(export["routing_rules"].as_object().unwrap().len(), 1);
    let wrapped_body = serde_json::to_vec(&json!({
        "import_clients": true,
        "import_settings": false,
        "config": export,
    }))
    .unwrap();

    for (label, body) in [("direct", export_body), ("wrapped", wrapped_body)] {
        assert!(body.len() > WEB_BODY_LIMIT_BYTES);
        assert!(body.len() < ROUTING_RULE_BODY_LIMIT_BYTES);
        let target = Fixture::new(Database::default()).await;
        let body_len = body.len();
        let response = target.request("POST", "/api/import", body).await;
        assert_eq!(response.status(), StatusCode::OK, "{label} import");
        assert_eq!(target.app.db.read().await.routing_rules.len(), 1);
        assert_eq!(target.app.db.read().await.routing_rules[&rule.id], rule);
        let persisted = model::load_database(&target.app.config_dir).unwrap();
        assert_eq!(persisted.routing_rules.len(), 1);
        assert_eq!(persisted.routing_rules[&rule.id], rule);
        eprintln!(
            "P2.12: one valid rule={rule_bytes} bytes; {label} export/import body={body_len} bytes -> 200; memory and SQLite match"
        );
    }
}

#[tokio::test]
async fn import_validates_all_rules_before_mutating_memory_or_storage() {
    let target = Fixture::new(Database {
        dns: "77.88.8.8,77.88.8.1".to_owned(),
        ..Default::default()
    })
    .await;
    let before = target.snapshots().await;
    let mut export: Value = serde_json::from_slice(&target.export().await).unwrap();
    export["vk_hashes"] = json!("changed-vk-hashes");
    export["dns"] = json!("1.1.1.1,1.0.0.1");
    export["dns_provider"] = json!("cloudflare");
    export["auto_restart_interval_hours"] = json!(3);
    export["vk_access_token"] = json!("changed-token");
    export["groups"] = json!({"new_group": {"id": "new_group", "name": "New"}});
    export["clients"] = json!([{
        "password": "new-client-password", "name": "New client",
        "expires_at": 0, "down_bytes": 0, "up_bytes": 0,
        "group_id": "new_group", "routing_rule_id": "rule_large",
    }]);

    let too_many_ips = RoutingRule {
        ips: (0..513)
            .map(|i| format!("10.0.{}.{}/32", i / 256, i % 256))
            .collect(),
        ..Default::default()
    };
    let too_many_domains = RoutingRule {
        direct_domains: (0..1025).map(|i| format!("d{i}.example.com")).collect(),
        ..Default::default()
    };
    let undeliverable = RoutingRule {
        name: "x".repeat(65_536),
        ..Default::default()
    };
    let whitelist_template = RoutingRule {
        template_ips: true,
        ip_is_whitelist: true,
        ..Default::default()
    };

    for (label, mut invalid) in [
        ("513 IPs", too_many_ips),
        ("1025 domains", too_many_domains),
        ("oversized routing event", undeliverable),
        ("IP whitelist template", whitelist_template),
    ] {
        invalid.id = "z_invalid".to_owned();
        export["routing_rules"] = json!({"rule_large": large_rule(), "z_invalid": invalid});
        let body = serde_json::to_vec(&json!({"config": export})).unwrap();
        let body_len = body.len();
        assert!(body_len > WEB_BODY_LIMIT_BYTES);
        assert!(body_len < ROUTING_RULE_BODY_LIMIT_BYTES);
        let response = target.request("POST", "/api/import", body).await;
        assert_eq!(response.status(), StatusCode::BAD_REQUEST, "{label}");
        let error = to_bytes(response.into_body(), ROUTING_RULE_BODY_LIMIT_BYTES)
            .await
            .unwrap();
        assert!(String::from_utf8_lossy(&error).contains("z_invalid"));
        assert_eq!(target.snapshots().await, before, "{label}");
        assert_eq!(*target.app.dns.read().await, "77.88.8.8,77.88.8.1");
        assert_eq!(*target.app.auto_restart_interval_tx.borrow(), 0);
        eprintln!(
            "P2.12: {label}, import body={body_len} bytes -> 400; memory, SQLite and runtime settings unchanged"
        );
    }
}

#[tokio::test]
async fn import_normalizes_removed_dns_provider_to_cloudflare() {
    let target = Fixture::new(Database::default()).await;
    let mut export: Value = serde_json::from_slice(&target.export().await).unwrap();
    export["dns"] = json!("77.88.8.8,77.88.8.1");
    export["dns_provider"] = json!("yandex");
    let body = serde_json::to_vec(&json!({"config": export})).unwrap();
    let response = target.request("POST", "/api/import", body).await;
    assert_eq!(response.status(), StatusCode::OK);
    {
        let db = target.app.db.read().await;
        assert_eq!(db.dns, "1.1.1.1,1.0.0.1");
        assert_eq!(db.dns_provider, "cloudflare");
    }
    assert_eq!(*target.app.dns.read().await, "1.1.1.1,1.0.0.1");
    let persisted = model::load_database(&target.app.config_dir).unwrap();
    assert_eq!(persisted.dns, "1.1.1.1,1.0.0.1");
    assert_eq!(persisted.dns_provider, "cloudflare");
}

#[tokio::test]
async fn import_limit_is_256_kib_without_expanding_other_endpoints() {
    assert_eq!(WEB_BODY_LIMIT_BYTES, 16 * 1024);
    assert_eq!(ROUTING_RULE_BODY_LIMIT_BYTES, 256 * 1024);
    let target = Fixture::new(Database::default()).await;
    let mut body = target.export().await;
    // JSON whitespace tests the byte boundary without an invalid-rule rejection.
    body.resize(ROUTING_RULE_BODY_LIMIT_BYTES, b' ');
    let response = target.request("POST", "/api/import", body.clone()).await;
    assert_eq!(response.status(), StatusCode::OK);
    let before = target.snapshots().await;
    body.push(b' ');
    let response = target.request("POST", "/api/import", body).await;
    assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);
    assert_eq!(target.snapshots().await, before);

    for uri in ["/api/settings", "/api/clients", "/api/groups"] {
        let mut body = b"{}".to_vec();
        body.resize(WEB_BODY_LIMIT_BYTES + 1, b' ');
        let response = target.request("POST", uri, body).await;
        assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE, "{uri}");
        assert_eq!(target.snapshots().await, before, "{uri}");
    }
    eprintln!(
        "P2.12: import 262144 bytes -> 200, 262145 -> 413; settings/clients/groups 16385 bytes -> 413"
    );
}

/// Owner as it lives in `passwords` after Task 1's migration.
fn owner_password_entry() -> PasswordEntry {
    PasswordEntry {
        ip: "10.66.67.2".to_owned(),
        expires_at: 1_700_000_000,
        down_bytes: 11,
        up_bytes: 22,
        vk_hash: "owner-hash".to_owned(),
        ports: "46000-46010".to_owned(),
        name: "Владелец".to_owned(),
        vk_hashes: "hash_a,hash_b".to_owned(),
        vk_token_enabled: true,
        dtls_port: 46000,
        local_port: 46001,
        group_id: "owner-group".to_owned(),
        routing_rule_id: "owner-rule".to_owned(),
        sub_uuid: "owner-sub-uuid".to_owned(),
        workers: Some(5),
        ..Default::default()
    }
}

#[tokio::test]
async fn export_v2_has_owner_in_clients_and_no_main_password() {
    const OWNER: &str = "OwnerMainPass123";
    let mut db = Database::default();
    db.passwords
        .insert(OWNER.to_owned(), owner_password_entry());
    let fixture = Fixture::new(db).await;

    let export: Value = serde_json::from_slice(&fixture.export().await).unwrap();
    assert_eq!(export["version"], 2);
    assert!(
        export.get("main_password").is_none(),
        "v2 export must not carry a separate owner password"
    );
    let clients = export["clients"].as_array().unwrap();
    assert_eq!(clients.len(), 1);
    let owner = &clients[0];
    assert_eq!(owner["password"], OWNER);
    assert_eq!(owner["name"], "Владелец");
    assert_eq!(owner["ip"], "10.66.67.2");
    assert_eq!(owner["expires_at"], 1_700_000_000);
    assert_eq!(owner["down_bytes"], 11);
    assert_eq!(owner["up_bytes"], 22);
    assert_eq!(owner["vk_hash"], "owner-hash");
    assert_eq!(owner["ports"], "46000-46010");
    assert_eq!(owner["vk_hashes"], "hash_a,hash_b");
    assert_eq!(owner["vk_token_enabled"], true);
    assert_eq!(owner["dtls_port"], 46000);
    assert_eq!(owner["local_port"], 46001);
    assert_eq!(owner["group_id"], "owner-group");
    assert_eq!(owner["routing_rule_id"], "owner-rule");
    assert_eq!(owner["sub_uuid"], "owner-sub-uuid");
    assert_eq!(owner["workers"], 5);
}

#[tokio::test]
async fn import_v2_round_trips_owner() {
    const OWNER: &str = "OwnerMainPass123";
    let mut db = Database::default();
    db.passwords
        .insert(OWNER.to_owned(), owner_password_entry());
    let source = Fixture::new(db).await;
    let source_entry = source.app.db.read().await.passwords[OWNER].clone();

    let mut export: Value = serde_json::from_slice(&source.export().await).unwrap();
    // A v2 payload may still contain the retired field; it is ignored.
    export["main_password"] = json!("InjectedOwnerPass1");
    let body = serde_json::to_vec(&json!({"import_clients": true, "config": export})).unwrap();

    let target = Fixture::new(Database::default()).await;
    let response = target.request("POST", "/api/import", body).await;
    assert_eq!(response.status(), StatusCode::OK);
    let answer: Value =
        serde_json::from_slice(&to_bytes(response.into_body(), usize::MAX).await.unwrap()).unwrap();
    assert_eq!(answer["added_clients"], 1);

    let db = target.app.db.read().await;
    assert_eq!(db.passwords.len(), 1);
    assert!(!db.passwords.contains_key("InjectedOwnerPass1"));
    assert_eq!(
        serde_json::to_value(&db.passwords[OWNER]).unwrap(),
        serde_json::to_value(&source_entry).unwrap(),
        "every exported owner field round-trips through clients"
    );
}
