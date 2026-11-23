use super::*;

#[tokio::test]
async fn clients_list_includes_owner_as_regular_client() {
    let mut db = Database::default();
    db.passwords.insert(
        "OwnerMainPass123".to_owned(),
        PasswordEntry {
            ip: "10.66.67.2".to_owned(),
            name: "Владелец".to_owned(),
            ..Default::default()
        },
    );
    let fixture = Fixture::new(db).await;
    let response = fixture.request("GET", "/api/clients", Vec::new()).await;
    assert_eq!(response.status(), StatusCode::OK);
    let body = to_bytes(response.into_body(), usize::MAX).await.unwrap();
    let list: Value = serde_json::from_slice(&body).unwrap();
    let clients = list.as_array().unwrap();
    assert_eq!(clients.len(), 1);
    assert_eq!(clients[0]["password"], "OwnerMainPass123");
    assert_eq!(clients[0]["name"], "Владелец");
    assert_eq!(clients[0]["ip"], "10.66.67.2");
    assert_eq!(clients[0]["active"], true);
}

#[tokio::test]
async fn clients_list_does_not_invent_dot3_for_empty_ip() {
    let mut db = Database::default();
    db.passwords.insert(
        "EmptyIpClient12".to_owned(),
        PasswordEntry {
            name: "Без адреса".to_owned(),
            ..Default::default()
        },
    );
    let fixture = Fixture::new(db).await;
    // Fixture repairs empty addresses on startup; clear it afterwards to
    // observe what the panel does with a genuinely empty entry.
    fixture
        .app
        .db
        .write()
        .await
        .passwords
        .get_mut("EmptyIpClient12")
        .unwrap()
        .ip
        .clear();
    let response = fixture.request("GET", "/api/clients", Vec::new()).await;
    let body = to_bytes(response.into_body(), usize::MAX).await.unwrap();
    let list: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(list[0]["ip"], "");
}

#[tokio::test]
async fn clients_get_with_empty_database_returns_empty_list() {
    let fixture = Fixture::new(Database::default()).await;
    let response = fixture.request("GET", "/api/clients", Vec::new()).await;
    assert_eq!(response.status(), StatusCode::OK);
    let body = to_bytes(response.into_body(), usize::MAX).await.unwrap();
    let list: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(list, json!([]));

    let response = fixture
        .request(
            "POST",
            "/api/clients",
            serde_json::to_vec(&json!({"name": "Первый", "days": 0})).unwrap(),
        )
        .await;
    assert_eq!(response.status(), StatusCode::OK);
    let response = fixture.request("GET", "/api/clients", Vec::new()).await;
    let body = to_bytes(response.into_body(), usize::MAX).await.unwrap();
    let list: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(list.as_array().unwrap().len(), 1);
    assert_eq!(list[0]["name"], "Первый");
}

#[tokio::test]
async fn owner_is_mutable_through_regular_handlers() {
    const OWNER: &str = "OwnerMainPass123";
    let mut db = Database::default();
    db.passwords.insert(
        "OwnerMainPass123".to_owned(),
        PasswordEntry {
            ip: "10.66.67.2".to_owned(),
            name: "Владелец".to_owned(),
            ..Default::default()
        },
    );
    let fixture = Fixture::new(db).await;
    let original_sub = fixture.app.db.read().await.passwords[OWNER]
        .sub_uuid
        .clone();

    let response = fixture
        .request(
            "POST",
            &format!("/api/clients/{OWNER}"),
            serde_json::to_vec(&json!({
                "name": "Владелец",
                "days": 30,
                "workers": 0,
                "hash": "",
                "vk_token": false,
                "group_id": "",
            }))
            .unwrap(),
        )
        .await;
    assert_eq!(response.status(), StatusCode::OK);

    for path in [
        format!("/api/clients/{OWNER}/toggle"),
        format!("/api/clients/{OWNER}/reset-traffic"),
        format!("/api/clients/{OWNER}/unbind"),
    ] {
        let response = fixture.request("POST", &path, Vec::new()).await;
        assert_eq!(response.status(), StatusCode::OK, "{path}");
    }
    let response = fixture
        .request(
            "POST",
            &format!("/api/clients/{OWNER}/extend"),
            br#"{"days":30}"#.to_vec(),
        )
        .await;
    assert_eq!(response.status(), StatusCode::OK);
    let response = fixture
        .request(
            "PUT",
            &format!("/api/clients/{OWNER}/vk"),
            br#"{"vk_hashes":"abcdef1234567890","vk_token":false}"#.to_vec(),
        )
        .await;
    assert_eq!(response.status(), StatusCode::OK);
    let response = fixture
        .request(
            "POST",
            &format!("/api/clients/{OWNER}/sub-link/regenerate"),
            Vec::new(),
        )
        .await;
    assert_eq!(response.status(), StatusCode::OK);

    let response = fixture
        .request("POST", "/api/groups", br#"{"name":"Owner group"}"#.to_vec())
        .await;
    assert_eq!(response.status(), StatusCode::OK);
    let body = to_bytes(response.into_body(), usize::MAX).await.unwrap();
    let group: Value = serde_json::from_slice(&body).unwrap();
    let group_id = group["id"].as_str().unwrap().to_owned();
    let response = fixture
        .request(
            "POST",
            "/api/clients/batch-group",
            serde_json::to_vec(&json!({
                "passwords": [OWNER],
                "group_id": group_id,
            }))
            .unwrap(),
        )
        .await;
    assert_eq!(response.status(), StatusCode::OK);

    let response = fixture
        .request(
            "POST",
            "/api/routing-rules",
            serde_json::to_vec(&json!({
                "name": "Owner rule",
                "assigned_clients": [OWNER],
            }))
            .unwrap(),
        )
        .await;
    assert_eq!(response.status(), StatusCode::OK);
    let body = to_bytes(response.into_body(), usize::MAX).await.unwrap();
    let rule: Value = serde_json::from_slice(&body).unwrap();
    let rule_id = rule["id"].as_str().unwrap().to_owned();

    for (path, body) in [
        (
            "/api/clients/batch-toggle",
            serde_json::to_vec(&json!({"passwords": [OWNER], "active": true})).unwrap(),
        ),
        (
            "/api/clients/batch-extend",
            serde_json::to_vec(&json!({"passwords": [OWNER], "days": 1})).unwrap(),
        ),
        (
            "/api/clients/batch-reset-traffic",
            serde_json::to_vec(&json!({"passwords": [OWNER]})).unwrap(),
        ),
    ] {
        let response = fixture.request("POST", path, body).await;
        assert_eq!(response.status(), StatusCode::OK, "{path}");
    }

    let db = fixture.app.db.read().await;
    let entry = &db.passwords[OWNER];
    assert_eq!(entry.name, "Владелец");
    assert_eq!(entry.group_id, group_id);
    assert_eq!(entry.routing_rule_id, rule_id);
    assert!(entry.expires_at > model::now());
    assert_eq!(entry.vk_hashes, "abcdef1234567890");
    assert_ne!(entry.sub_uuid, original_sub);
    assert!(!entry.is_deactivated);
}

#[tokio::test]
async fn deactivated_owner_is_inactive_in_panel_and_denied_in_tunnel() {
    const OWNER: &str = "OwnerMainPass123";
    let mut db = Database::default();
    db.passwords.insert(
        "OwnerMainPass123".to_owned(),
        PasswordEntry {
            ip: "10.66.67.2".to_owned(),
            name: "Владелец".to_owned(),
            ..Default::default()
        },
    );
    let fixture = Fixture::new(db).await;
    let session = crate::protocol::Session::new(
        7,
        "127.0.0.1:40000".parse().unwrap(),
        OWNER,
        1,
        "owner-session-salt",
        false,
        fixture.app.stream_debug_active.clone(),
        model::now() as u64,
    );
    fixture.app.sessions.insert(session.id, session);

    let response = fixture
        .request("POST", &format!("/api/clients/{OWNER}/toggle"), Vec::new())
        .await;
    assert_eq!(response.status(), StatusCode::OK);

    // Deactivation resolves through the ordinary `passwords` entry, so the
    // tunnel teardown drops the owner's live session just like a client's.
    assert!(fixture.app.sessions.is_empty());

    let response = fixture.request("GET", "/api/clients", Vec::new()).await;
    let body = to_bytes(response.into_body(), usize::MAX).await.unwrap();
    let list: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(list[0]["password"], OWNER);
    assert_eq!(list[0]["active"], false);

    let response = fixture
        .request("GET", &format!("/api/clients/{OWNER}"), Vec::new())
        .await;
    assert_eq!(response.status(), StatusCode::OK);
    let body = to_bytes(response.into_body(), usize::MAX).await.unwrap();
    let info: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(info["active"], false);
}
