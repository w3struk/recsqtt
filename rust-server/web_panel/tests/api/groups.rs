use super::*;

#[tokio::test]
async fn group_create_and_update_persist_vk_token_user_id() {
    let fixture = Fixture::new(Database::default()).await;
    let response = fixture
        .request(
            "POST",
            "/api/groups",
            br#"{"name":"Group","vk_token_user_id":"111222"}"#.to_vec(),
        )
        .await;
    assert_eq!(response.status(), StatusCode::OK);
    let body = to_bytes(response.into_body(), usize::MAX).await.unwrap();
    let group: Value = serde_json::from_slice(&body).unwrap();
    let group_id = group["id"].as_str().unwrap().to_owned();
    assert_eq!(group["vk_token_user_id"], "111222");

    let response = fixture.request("GET", "/api/groups", Vec::new()).await;
    assert_eq!(response.status(), StatusCode::OK);
    let body = to_bytes(response.into_body(), usize::MAX).await.unwrap();
    let list: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(list[0]["vk_token_user_id"], "111222");

    let response = fixture
        .request(
            "PUT",
            &format!("/api/groups/{group_id}"),
            br#"{"vk_token_user_id":"333444"}"#.to_vec(),
        )
        .await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        fixture.app.db.read().await.groups[&group_id]
            .vk_token_user_id
            .as_deref(),
        Some("333444")
    );

    let response = fixture
        .request(
            "PUT",
            &format!("/api/groups/{group_id}"),
            br#"{"vk_token_user_id":"nope"}"#.to_vec(),
        )
        .await;
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    assert_eq!(
        fixture.app.db.read().await.groups[&group_id]
            .vk_token_user_id
            .as_deref(),
        Some("333444")
    );
}
