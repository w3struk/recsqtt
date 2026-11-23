use crate::web_panel::*;

#[test]
fn vk_hashes_accept_empty_or_one_to_six_values() {
    assert_eq!(normalize_vk_hashes("").unwrap(), "");
    for count in 1..=6 {
        let hashes = (1..=count)
            .map(|index| format!("abcdefghijklmnop{index}"))
            .collect::<Vec<_>>()
            .join(",");
        assert_eq!(normalize_vk_hashes(&hashes).unwrap(), hashes);
    }
}

#[test]
fn vk_hashes_reject_spaces_short_values_and_more_than_six() {
    assert!(normalize_vk_hashes("abcdefghijklmnop, abcdefghijklmnop").is_err());
    assert!(normalize_vk_hashes("short").is_err());
    let seven = (1..=7)
        .map(|index| format!("abcdefghijklmnop{index}"))
        .collect::<Vec<_>>()
        .join(",");
    assert!(normalize_vk_hashes(&seven).is_err());
}

#[test]
fn vk_token_accepts_one_compact_value_or_empty() {
    assert_eq!(
        normalize_vk_access_token("  vk1.token-_%2B  ").unwrap(),
        "vk1.token-_%2B"
    );
    assert_eq!(normalize_vk_access_token(" ").unwrap(), "");
    assert!(normalize_vk_access_token("token with space").is_err());
    assert!(normalize_vk_access_token(&"x".repeat(4097)).is_err());
}

#[test]
fn vk_user_id_accepts_digits_and_normalizes_empty_to_none() {
    assert_eq!(
        normalize_vk_user_id("  123456  ").unwrap().as_deref(),
        Some("123456")
    );
    assert_eq!(normalize_vk_user_id("").unwrap(), None);
    assert_eq!(normalize_vk_user_id("   ").unwrap(), None);
    let twenty = "9".repeat(20);
    assert_eq!(
        normalize_vk_user_id(&twenty).unwrap().as_deref(),
        Some(twenty.as_str())
    );
    assert!(normalize_vk_user_id("12a45").is_err());
    assert!(normalize_vk_user_id("-123").is_err());
    assert!(normalize_vk_user_id("12 345").is_err());
    assert!(normalize_vk_user_id(&"9".repeat(21)).is_err());
}

#[test]
fn effective_vk_credentials_pair_group_token_with_its_own_user_id() {
    let mut db = crate::model::Database {
        vk_access_token: "global-token".to_owned(),
        vk_access_token_user_id: "100".to_owned(),
        ..Default::default()
    };
    assert_eq!(effective_vk_credentials("", &db), ("global-token", "100"));
    assert_eq!(
        effective_vk_credentials("missing", &db),
        ("global-token", "100")
    );

    db.groups.insert(
        "g1".to_owned(),
        crate::model::ClientGroup {
            id: "g1".to_owned(),
            ..Default::default()
        },
    );
    assert_eq!(effective_vk_credentials("g1", &db), ("global-token", "100"));

    db.groups.insert(
        "g2".to_owned(),
        crate::model::ClientGroup {
            id: "g2".to_owned(),
            vk_token: Some("group-token".to_owned()),
            vk_token_user_id: Some("200".to_owned()),
            ..Default::default()
        },
    );
    assert_eq!(effective_vk_credentials("g2", &db), ("group-token", "200"));

    // A group token without its own id degrades to an empty id: inheriting the
    // global one would name the wrong owner of the calls.
    db.groups.insert(
        "g3".to_owned(),
        crate::model::ClientGroup {
            id: "g3".to_owned(),
            vk_token: Some("group-token-3".to_owned()),
            ..Default::default()
        },
    );
    assert_eq!(effective_vk_credentials("g3", &db), ("group-token-3", ""));
}
