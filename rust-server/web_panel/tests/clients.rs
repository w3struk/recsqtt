use crate::web_panel::*;

#[test]
fn client_info_serializes_presence_keys_in_snake_case() {
    let info = ClientInfo {
        password: "client1234567890".to_owned(),
        down: 0,
        up: 0,
        expires: 0,
        active: true,
        active_sessions: 1,
        last_online: 1_700_000_000,
        presence_window_secs: crate::protocol::PRESENCE_WINDOW_SECS,
        server_now: 1_700_000_010,
        vk_hash: String::new(),
        ports: String::new(),
        ip: String::new(),
        name: String::new(),
        vk_hashes: String::new(),
        vk_token: false,
        peer_port: 0,
        local_port: 0,
        group_id: String::new(),
        routing_rule_id: String::new(),
        link: String::new(),
        sub_uuid: String::new(),
        workers: None,
    };
    let value = serde_json::to_value(info).unwrap();
    assert_eq!(value["last_online"], 1_700_000_000);
    assert_eq!(
        value["presence_window_secs"],
        crate::protocol::PRESENCE_WINDOW_SECS
    );
    assert_eq!(value["server_now"], 1_700_000_010);
    for camel in ["lastOnline", "presenceWindowSecs", "serverNow"] {
        assert!(
            value.get(camel).is_none(),
            "unexpected camelCase key {camel}"
        );
    }
    assert!(
        value.get("online").is_none(),
        "presence flag must not be duplicated in the payload"
    );
}

#[test]
fn count_active_sessions_isolates_passwords() {
    use crate::protocol::Session;
    use dashmap::DashMap;
    use std::net::SocketAddr;
    use std::sync::Arc;
    use std::sync::atomic::AtomicBool;

    let sessions = DashMap::new();
    let debug_flag = Arc::new(AtomicBool::new(false));
    let addr: SocketAddr = "127.0.0.1:46001".parse().unwrap();

    let s1 = Session::new(
        1,
        addr,
        "pass_a",
        1,
        "salt1",
        false,
        debug_flag.clone(),
        100,
    );
    sessions.insert(1, s1);

    assert_eq!(count_active_sessions(&sessions, "pass_a"), 1);
    assert_eq!(count_active_sessions(&sessions, "pass_b"), 0);

    let s_owner = Session::new(2, addr, "owner_pass", 2, "salt2", false, debug_flag, 100);
    sessions.insert(2, s_owner);

    assert_eq!(count_active_sessions(&sessions, "owner_pass"), 1);
    assert_eq!(count_active_sessions(&sessions, "pass_a"), 1);
    assert_eq!(count_active_sessions(&sessions, "pass_b"), 0);
}
