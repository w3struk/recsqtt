use crate::web_panel::*;

#[test]
fn csqtt_link_encryption_and_decryption() {
    let payload = "connect?v=2&host=1.2.3.4&peer=46000&password=testpass";
    let encrypted1 = encrypt_csqtt_link(payload);
    let encrypted2 = encrypt_csqtt_link(payload);
    assert!(encrypted1.starts_with("csqtt://"));
    assert_eq!(
        encrypted1, encrypted2,
        "link encryption must be deterministic for identical payload"
    );
    let decrypted = decrypt_csqtt_link(&encrypted1).expect("decryption should succeed");
    assert_eq!(decrypted, payload);
}

#[test]
fn csqtt_link_legacy_plaintext_rejected() {
    use base64::Engine;
    let payload = "connect?v=2&host=1.2.3.4&peer=46000&password=legacy";
    let legacy_b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(payload.as_bytes());
    let legacy_link = format!("csqtt://{legacy_b64}");
    assert!(decrypt_csqtt_link(&legacy_link).is_none());
}

#[test]
fn make_client_link_contains_password_and_aes_roundtrip() {
    let link = make_client_link("1.2.3.4", 46000, "abc1234567890123", "", false, "", "");
    assert!(link.starts_with("csqtt://"));
    let decrypted = decrypt_csqtt_link(&link).expect("must decrypt");
    assert!(decrypted.contains("password=abc1234567890123"));
    assert!(!decrypted.contains("client="));
    assert!(!decrypted.contains("&workers="));
}

#[test]
fn owner_link_contains_owner_password_without_workers() {
    let link = make_client_link("1.2.3.4", 46000, "ownerpass1234567", "", false, "", "");
    let decrypted = decrypt_csqtt_link(&link).expect("must decrypt");
    assert!(decrypted.contains("password=ownerpass1234567"));
    assert!(!decrypted.contains("client="));
    assert!(!decrypted.contains("&workers="));
}

#[test]
fn make_client_link_carries_user_id_only_with_token() {
    let with_id = make_client_link("1.2.3.4", 46000, "pass1", "", true, "vk1.token", "123456");
    let decoded = decrypt_csqtt_link(&with_id).expect("must decrypt");
    assert!(decoded.contains("&token=vk1.token"));
    assert!(decoded.contains("&user_id=123456"));
    assert!(
        decoded.find("&token=").unwrap() < decoded.find("&user_id=").unwrap(),
        "user_id must follow token"
    );

    let without_id = make_client_link("1.2.3.4", 46000, "pass1", "", true, "vk1.token", "");
    let decoded = decrypt_csqtt_link(&without_id).expect("must decrypt");
    assert!(decoded.contains("&token=vk1.token"));
    assert!(!decoded.contains("user_id="));

    // A user id alone never reaches the link: the token is its precondition.
    let without_token = make_client_link("1.2.3.4", 46000, "pass1", "", true, "", "123456");
    let decoded = decrypt_csqtt_link(&without_token).expect("must decrypt");
    assert!(!decoded.contains("token="));
    assert!(!decoded.contains("user_id="));

    // hashes and token are mutually exclusive, so the paired id stays out too.
    let with_hashes = make_client_link(
        "1.2.3.4",
        46000,
        "pass1",
        "abcdef1234567890",
        true,
        "vk1.token",
        "123456",
    );
    let decoded = decrypt_csqtt_link(&with_hashes).expect("must decrypt");
    assert!(decoded.contains("&hashes=abcdef1234567890"));
    assert!(!decoded.contains("token="));
    assert!(!decoded.contains("user_id="));
}
