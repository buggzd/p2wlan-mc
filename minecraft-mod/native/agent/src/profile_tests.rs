use crate::{
    account::{self, AccountApi},
    private::PrivateDirectory,
    profile::Profile,
    AgentError, Identity,
};
use serde_json::json;
use std::fs;
use std::os::unix::fs::{symlink, DirBuilderExt, PermissionsExt};
use std::path::PathBuf;

struct Directory(PathBuf);
impl Directory {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "p2mc-test-{}",
            Identity::generate().unwrap().as_str()
        ));
        fs::DirBuilder::new().mode(0o700).create(&path).unwrap();
        Self(path)
    }
    fn profile(&self) -> Profile {
        Profile::open(&self.0).unwrap()
    }
}
impl Drop for Directory {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}
fn login(profile: &mut Profile) {
    profile
        .login(
            "https://control.example.com".into(),
            "fixture-user".into(),
            "fixture-jwt".into(),
        )
        .unwrap();
}
#[test]
fn account_restore_logout_locking_and_redacted_status() {
    let directory = Directory::new();
    let mut profile = directory.profile();
    assert!(matches!(
        Profile::open(&directory.0),
        Err(AgentError::ProfileBusy)
    ));
    assert_eq!(profile.credentials().err(), Some(AgentError::AuthRequired));
    login(&mut profile);
    assert!(!profile.status().to_string().contains("fixture-jwt"));
    drop(profile);
    let mut profile = directory.profile();
    assert_eq!(
        profile.credentials().unwrap(),
        ("https://control.example.com", "fixture-jwt", "fixture-user")
    );
    assert_eq!(
        fs::metadata(directory.0.join("account.json"))
            .unwrap()
            .permissions()
            .mode()
            & 0o777,
        0o600
    );
    profile.logout().unwrap();
    drop(profile);
    let profile = directory.profile();
    assert_eq!(profile.credentials().err(), Some(AgentError::AuthRequired));
    assert_eq!(profile.status(), json!({"authenticated":false}));
}
#[test]
fn saved_public_http_account_and_room_config_remain_durable() {
    let directory = Directory::new();
    let mut profile = directory.profile();
    profile
        .login(
            "http://control.example.com:18080".into(),
            "fixture-user".into(),
            "fixture-token".into(),
        )
        .unwrap();
    let config = profile
        .room_config("room-http-fixture", "10.40.1.0/24")
        .unwrap();
    assert_eq!(
        config.control.server_url,
        "http://control.example.com:18080"
    );
    let key = config.node.public_key;
    drop(profile);
    let profile = directory.profile();
    assert_eq!(
        profile.credentials().unwrap(),
        (
            "http://control.example.com:18080",
            "fixture-token",
            "fixture-user"
        )
    );
    assert_eq!(
        profile
            .room_config("room-http-fixture", "10.40.1.0/24")
            .unwrap()
            .node
            .public_key,
        key
    );
}
#[test]
fn room_identity_is_durable_scoped_and_never_uses_host_name() {
    let directory = Directory::new();
    let mut profile = directory.profile();
    login(&mut profile);
    let first = profile.room_config("room-fixture", "10.40.1.0/24").unwrap();
    let another = profile.room_config("room-second", "10.40.2.0/24").unwrap();
    assert_ne!(first.node.public_key, another.node.public_key);
    assert_eq!(first.node.device_name, "Minecraft Mod");
    assert_eq!(first.network.udp_bind, "0.0.0.0:0");
    assert_eq!(first.network.mtu, 1280);
    assert!(!first.diagnostics.enabled);
    assert!(!first.dns.enabled);
    assert!(first.port_mappings.is_empty());
    drop(profile);
    let mut profile = directory.profile();
    let again = profile.room_config("room-fixture", "10.40.1.0/24").unwrap();
    assert_eq!(first.node.public_key, again.node.public_key);
    assert_eq!(first.config_path, again.config_path);
    profile
        .login(
            "https://control.example.com".into(),
            "other-user".into(),
            "other-jwt".into(),
        )
        .unwrap();
    let other_user = profile.room_config("room-fixture", "10.40.1.0/24").unwrap();
    assert_ne!(first.node.public_key, other_user.node.public_key);
    profile
        .login(
            "https://other.example.com".into(),
            "fixture-user".into(),
            "other-jwt".into(),
        )
        .unwrap();
    let other_server = profile.room_config("room-fixture", "10.40.1.0/24").unwrap();
    assert_ne!(first.node.public_key, other_server.node.public_key);
    assert_ne!(first.config_path, other_server.config_path);
    profile.logout().unwrap();
    assert!(matches!(
        profile.room_config("room-fixture", "10.40.1.0/24"),
        Err(AgentError::AuthRequired)
    ));
    assert!(first.config_path.unwrap().is_file());
}
#[test]
fn room_capacity_does_not_evict_or_count_incarnation_files() {
    let directory = Directory::new();
    let mut profile = directory.profile();
    login(&mut profile);
    fs::write(directory.0.join("incarnation.json"), b"{}").unwrap();
    fs::write(directory.0.join("room-incarnation.json"), b"{}").unwrap();
    // A hostile Unicode filename must never panic in the ASCII hash matcher.
    fs::write(
        directory
            .0
            .join(format!("room-{}x.json", "\u{00e9}".repeat(31))),
        b"{}",
    )
    .unwrap();
    for index in 0..32 {
        profile
            .room_config(&format!("room-{index}"), "10.40.1.0/24")
            .unwrap();
    }
    assert!(matches!(
        profile.room_config("room-overflow", "10.40.1.0/24"),
        Err(AgentError::ProfileCapacity)
    ));
    assert!(profile.room_config("room-0", "10.40.1.0/24").is_ok());
    assert_eq!(
        PrivateDirectory::open(&directory.0).err(),
        Some(AgentError::ProfileBusy)
    );
}
#[test]
fn invalid_or_foreign_room_config_is_not_regenerated() {
    let directory = Directory::new();
    let mut profile = directory.profile();
    login(&mut profile);
    for room in ["../room-fixture", "room-../other", "manual", "room-foo/bar"] {
        assert!(matches!(
            profile.room_config(room, "10.40.1.0/24"),
            Err(AgentError::InvalidArguments)
        ));
    }
    let config = profile.room_config("room-fixture", "10.40.1.0/24").unwrap();
    let path = config.config_path.clone().unwrap();
    let mut value = serde_json::to_value(config).unwrap();
    value["network"]["network_id"] = json!("room-foreign");
    let content = serde_json::to_vec(&value).unwrap();
    fs::write(&path, &content).unwrap();
    assert!(matches!(
        profile.room_config("room-fixture", "10.40.1.0/24"),
        Err(AgentError::ProfileInvalid)
    ));
    assert_eq!(fs::read(path).unwrap(), content);
}
#[test]
fn directory_replacement_and_lock_replacement_are_rejected_before_publication() {
    let directory = Directory::new();
    let old = directory.0.join("old");
    let current = directory.0.join("current");
    let storage = PrivateDirectory::open(&current).unwrap();
    storage.write("account.json", b"old").unwrap();
    fs::rename(&current, &old).unwrap();
    fs::DirBuilder::new().mode(0o700).create(&current).unwrap();
    assert_eq!(
        storage.write("account.json", b"must-not-publish"),
        Err(AgentError::ProfileInvalid)
    );
    assert!(!current.join("account.json").exists());
    assert_eq!(fs::read(old.join("account.json")).unwrap(), b"old");
    let replacement = PrivateDirectory::open(&current).unwrap();
    fs::remove_file(current.join("profile.lock")).unwrap();
    assert!(replacement
        .write("account.json", b"must-not-publish")
        .is_err());
    assert!(!current.join("account.json").exists());
}
#[test]
fn profile_rejects_insecure_directories_and_unsafe_existing_files() {
    let directory = Directory::new();
    let path = directory.0.join("profile");
    fs::create_dir(&path).unwrap();
    fs::set_permissions(&path, fs::Permissions::from_mode(0o755)).unwrap();
    assert!(matches!(
        Profile::open(&path),
        Err(AgentError::ProfileInvalid)
    ));
    fs::set_permissions(&path, fs::Permissions::from_mode(0o700)).unwrap();
    symlink(&path, directory.0.join("alias")).unwrap();
    assert!(matches!(
        Profile::open(&directory.0.join("alias")),
        Err(AgentError::ProfileInvalid)
    ));
    let storage = PrivateDirectory::open(&path).unwrap();
    let source = directory.0.join("source");
    fs::write(&source, b"private-original").unwrap();
    fs::set_permissions(&source, fs::Permissions::from_mode(0o600)).unwrap();
    let target = path.join("account.json");
    symlink(&source, &target).unwrap();
    assert!(storage.write("account.json", b"new").is_err());
    fs::remove_file(&target).unwrap();
    fs::hard_link(&source, &target).unwrap();
    assert_eq!(
        storage.write("account.json", b"new"),
        Err(AgentError::ProfileInvalid)
    );
    fs::remove_file(&target).unwrap();
    fs::write(&target, b"{}").unwrap();
    fs::set_permissions(&target, fs::Permissions::from_mode(0o644)).unwrap();
    assert!(matches!(
        storage.read("account.json"),
        Err(AgentError::ProfileInvalid)
    ));
    fs::set_permissions(&target, fs::Permissions::from_mode(0o600)).unwrap();
    fs::write(&target, vec![0; 65537]).unwrap();
    assert!(matches!(
        storage.read("account.json"),
        Err(AgentError::ProfileInvalid)
    ));
    assert_eq!(fs::read(source).unwrap(), b"private-original");
    for name in [".", "..", "../other", "other/name", ""] {
        assert!(matches!(
            storage.path(name),
            Err(AgentError::ProfileInvalid)
        ));
    }
}
#[test]
fn disk_state_limits_unknown_fields_versions_and_unsafe_server_urls() {
    let directory = Directory::new();
    let storage = PrivateDirectory::open(&directory.0).unwrap();
    for state in [
        json!({"version":2,"user_id":"fixture","server":"https://control.example.com","token":"jwt"}),
        json!({"version":1,"user_id":"fixture","server":"ftp://control.example.com","token":"jwt"}),
        json!({"version":1,"user_id":"fixture","server":"https://control.example.com","token":"jwt","unknown":1}),
    ] {
        storage
            .write("account.json", &serde_json::to_vec(&state).unwrap())
            .unwrap();
        // Release the exclusive lock while exercising the strict loader.
        let temporary = directory.0.join("copy");
        fs::DirBuilder::new()
            .mode(0o700)
            .create(&temporary)
            .unwrap();
        fs::copy(
            directory.0.join("account.json"),
            temporary.join("account.json"),
        )
        .unwrap();
        assert!(matches!(
            Profile::open(&temporary),
            Err(AgentError::ProfileInvalid)
        ));
        fs::remove_dir_all(temporary).unwrap();
    }
    assert_eq!(
        storage.write("oversized.json", &vec![0; 65537]),
        Err(AgentError::ProfileInvalid)
    );
    assert!(!directory.0.join("oversized.json").exists());
}
#[test]
fn control_origins_and_room_projection_are_bounded_and_secret_free() {
    for invalid in [
        "http://name:password@example.com",
        "https://name:password@example.com",
        "https://example.com/api",
        "https://example.com?token=x",
        "https://example.com#x",
        "ftp://example.com",
    ] {
        assert_eq!(
            AccountApi::server(invalid),
            Err(AgentError::InvalidArguments)
        );
    }
    for valid in [
        "https://control.example.com",
        "http://control.example.com",
        "http://203.0.113.1:18080",
        "http://127.0.0.1:12345",
        "http://[::1]:12345",
        "http://localhost:12345",
    ] {
        assert_eq!(AccountApi::server(valid).unwrap(), valid);
    }
    let source = json!({"id":"room-fixture","room_code":"12345678","name":"Fixture","cidr":"10.40.1.0/24","role":"owner","password":"private","members":[{"token":"secret"}]});
    let public = account::room(&source).unwrap();
    assert_eq!(public.as_object().unwrap().len(), 5);
    assert!(!public.to_string().contains("private"));
    assert!(!public.to_string().contains("secret"));
    for (field, invalid) in [
        ("role", json!({"token":"secret"})),
        ("cidr", json!("invalid")),
        ("room_code", json!("1234")),
        ("id", json!("room-../x")),
    ] {
        let mut bad = source.clone();
        bad[field] = invalid;
        assert_eq!(account::room(&bad), Err(AgentError::ControlResponse));
    }
}

#[tokio::test]
async fn invalid_room_passwords_are_rejected_before_any_http_mutation() {
    let directory = Directory::new();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let mut profile = directory.profile();
    profile
        .login(
            format!("http://{}", listener.local_addr().unwrap()),
            "fixture-user".into(),
            "fixture-jwt".into(),
        )
        .unwrap();
    let owner = p2wlan_mc_runtime_port::ScopeOwner::new();
    let mut state = crate::State {
        parent: owner.token(),
        profile: Some(profile),
        api: AccountApi::new().unwrap(),
        runtime: None,
        bindings: std::collections::HashMap::new(),
        last_failure: None,
    };
    for operation in ["create_room", "join_room"] {
        for password in [
            String::new(),
            "short".to_string(),
            "x".repeat(73),
            "界".repeat(25),
            "😀".repeat(19),
        ] {
            let args = if operation == "create_room" {
                json!({"name":"Fixture", "password":password})
            } else {
                json!({"code":"12345678", "password":password})
            };
            let request = crate::Request {
                id: 1,
                operation: operation.into(),
                arguments: args.as_object().unwrap().clone(),
            };
            let result = tokio::time::timeout(
                std::time::Duration::from_secs(1),
                state.account_command(&request),
            )
            .await
            .unwrap();
            assert_eq!(result, Err(AgentError::InvalidArguments));
        }
    }
    assert!(
        tokio::time::timeout(std::time::Duration::from_millis(50), listener.accept())
            .await
            .is_err()
    );
}
