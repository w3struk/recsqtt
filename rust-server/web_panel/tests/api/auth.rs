use super::*;

#[tokio::test]
async fn client_password_never_authorizes_panel_api() {
    let mut db = Database::default();
    db.passwords.insert(
        "owner-main-password".to_owned(),
        PasswordEntry {
            ip: "10.66.67.2".to_owned(),
            name: "Владелец".to_owned(),
            ..Default::default()
        },
    );
    let target = Fixture::new(db).await;

    for password in ["owner-main-password", "rotated-main-password"] {
        for uri in ["/api/settings", "/api/clients", "/api/export"] {
            let response = target
                .request_with_header("GET", uri, "x-api-key", password)
                .await;
            assert_eq!(
                response.status(),
                StatusCode::UNAUTHORIZED,
                "x-api-key {uri}"
            );

            let bearer = format!("Bearer {password}");
            let response = target
                .request_with_header("GET", uri, "authorization", &bearer)
                .await;
            assert_eq!(response.status(), StatusCode::UNAUTHORIZED, "Bearer {uri}");
        }
    }

    let response = target
        .request_with_header("GET", "/api/settings", "x-api-key", &target.app.web_pass)
        .await;
    assert_eq!(response.status(), StatusCode::OK);

    eprintln!("main password rejected as x-api-key/Bearer; panel password still authorizes");
}
