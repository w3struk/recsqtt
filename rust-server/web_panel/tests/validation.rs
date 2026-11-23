use crate::web_panel::*;

#[test]
fn persisted_text_fields_have_byte_and_control_character_limits() {
    assert!(validate_client_name("normal client").is_ok());
    assert!(validate_client_name(&"x".repeat(MAX_CLIENT_NAME_BYTES)).is_ok());
    assert!(validate_client_name(&"x".repeat(MAX_CLIENT_NAME_BYTES + 1)).is_err());
    assert!(validate_client_name("bad\nname").is_err());
    assert!(validate_proxy_profile_name(&"x".repeat(64)).is_ok());
    assert!(validate_proxy_profile_name(&"x".repeat(65)).is_err());
}

#[test]
fn workers_validation() {
    assert_eq!(validate_workers(None), Ok(None));
    assert_eq!(validate_workers(Some(0)), Ok(None));
    assert_eq!(validate_workers(Some(3)), Ok(Some(3)));
    assert_eq!(validate_workers(Some(36)), Ok(Some(36)));
    assert_eq!(validate_workers(Some(99)), Ok(Some(99)));
    assert_eq!(validate_workers(Some(100)), Ok(Some(100)));
    assert!(validate_workers(Some(1)).is_err());
    assert!(validate_workers(Some(2)).is_err());
    assert!(validate_workers(Some(101)).is_err());
}
