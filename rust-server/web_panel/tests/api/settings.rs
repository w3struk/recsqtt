use super::*;

#[tokio::test]
async fn settings_has_no_main_password_field() {
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
    let response = fixture.request("GET", "/api/settings", Vec::new()).await;
    assert_eq!(response.status(), StatusCode::OK);
    let body = to_bytes(response.into_body(), usize::MAX).await.unwrap();
    let json: Value = serde_json::from_slice(&body).unwrap();
    assert!(json.get("main_password").is_none());
    assert!(json.get("restart_required").is_none());
}

#[tokio::test]
async fn master_password_route_is_absent() {
    let mut db = Database::default();
    db.passwords.insert(
        "OwnerMainPass123".to_owned(),
        PasswordEntry {
            ip: "10.66.67.2".to_owned(),
            name: "Владелец".to_owned(),
            ..Default::default()
        },
    );
    let target = Fixture::new(db).await;
    let before = target.snapshots().await;
    let response = target
        .request(
            "POST",
            "/api/settings/master-password",
            br#"{"password":"InjectedOwnerPass1"}"#.to_vec(),
        )
        .await;
    // The production router answers unknown paths with the stealth abort,
    // never with a route that could mutate the owner.
    assert_eq!(response.status(), StatusCode::OK);
    assert!(response.headers().contains_key("x-csqtt-stealth-rst"));
    assert_eq!(target.snapshots().await, before);
}

#[tokio::test]
async fn settings_post_ignores_legacy_main_password() {
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
    let response = fixture
        .request(
            "POST",
            "/api/settings",
            br#"{"main_password":"InjectedOwnerPass1","dns_provider":"cloudflare"}"#.to_vec(),
        )
        .await;
    assert_eq!(response.status(), StatusCode::OK);
    let db = fixture.app.db.read().await;
    assert_eq!(db.passwords.len(), 1);
    assert!(!db.passwords.contains_key("InjectedOwnerPass1"));
    assert_eq!(db.dns, "1.1.1.1,1.0.0.1");
}

#[tokio::test]
async fn settings_post_saves_and_clears_vk_token_user_id() {
    let fixture = Fixture::new(Database::default()).await;
    let response = fixture
        .request(
            "POST",
            "/api/settings",
            br#"{"vk_token":"vk1.token","vk_token_user_id":"123456"}"#.to_vec(),
        )
        .await;
    assert_eq!(response.status(), StatusCode::OK);
    {
        let db = fixture.app.db.read().await;
        assert_eq!(db.vk_access_token, "vk1.token");
        assert_eq!(db.vk_access_token_user_id, "123456");
    }

    let response = fixture.request("GET", "/api/settings", Vec::new()).await;
    assert_eq!(response.status(), StatusCode::OK);
    let body = to_bytes(response.into_body(), usize::MAX).await.unwrap();
    let settings: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(settings["vk_token"], "vk1.token");
    assert_eq!(settings["vk_token_user_id"], "123456");

    // an explicitly empty id clears the stored value but keeps the token
    let response = fixture
        .request(
            "POST",
            "/api/settings",
            br#"{"vk_token_user_id":""}"#.to_vec(),
        )
        .await;
    assert_eq!(response.status(), StatusCode::OK);
    {
        let db = fixture.app.db.read().await;
        assert_eq!(db.vk_access_token, "vk1.token");
        assert_eq!(db.vk_access_token_user_id, "");
    }
}

#[tokio::test]
async fn invalid_vk_user_id_is_rejected_without_partial_write() {
    let fixture = Fixture::new(Database::default()).await;
    let response = fixture
        .request(
            "POST",
            "/api/settings",
            br#"{"vk_token":"vk1.old","vk_token_user_id":"777"}"#.to_vec(),
        )
        .await;
    assert_eq!(response.status(), StatusCode::OK);
    let before = fixture.snapshots().await;

    let response = fixture
        .request(
            "POST",
            "/api/settings",
            br#"{"vk_token":"vk1.new","vk_token_user_id":"12a45"}"#.to_vec(),
        )
        .await;
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    assert_eq!(fixture.snapshots().await, before);
    let db = fixture.app.db.read().await;
    assert_eq!(db.vk_access_token, "vk1.old");
    assert_eq!(db.vk_access_token_user_id, "777");
}
