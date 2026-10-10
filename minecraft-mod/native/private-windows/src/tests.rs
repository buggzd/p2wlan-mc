//! These tests execute actual NT filesystem/ACL operations on Windows. A
//! cross-target `check --tests` verifies compilation, NOT their execution.
use crate::{handles, security::Security, Error, PrivateDirectory};
use std::fs;
use std::path::PathBuf;
use std::process::Command;

struct Fixture(PathBuf);
impl Fixture {
    fn new() -> Self {
        let mut nonce = [0; 16];
        getrandom::getrandom(&mut nonce).unwrap();
        let name = nonce
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect::<String>();
        let root = std::env::temp_dir().join(format!("p2wlan-private-test-{name}"));
        // The fixture is unique, contains no real account or game data, and is
        // created with the same atomic private descriptor as production.
        handles::create_directory(&root, &Security::current_user().unwrap()).unwrap();
        Self(root)
    }
    fn profile(&self) -> PathBuf {
        self.0.join("游戏配置").join("profile")
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        fs::remove_dir_all(&self.0).unwrap();
    }
}

#[test]
fn private_roundtrip_atomic_replace_lock_and_reopen() {
    let fixture = Fixture::new();
    let path = fixture.profile();
    let storage = PrivateDirectory::open(&path).unwrap();
    assert_eq!(storage.read("account.json").unwrap(), None);
    assert!(matches!(PrivateDirectory::open(&path), Err(Error::Busy)));
    storage.write("account.json", b"private-fixture").unwrap();
    assert_eq!(
        storage.read("account.json").unwrap().unwrap(),
        b"private-fixture"
    );
    storage.write("account.json", b"replacement").unwrap();
    assert_eq!(
        storage.read("account.json").unwrap().unwrap(),
        b"replacement"
    );
    assert_eq!(fs::read_dir(&path).unwrap().count(), 2);
    assert_eq!(storage.room_count().unwrap(), 0);
    drop(storage);
    let reopened = PrivateDirectory::open(&path).unwrap();
    assert_eq!(
        reopened.read("account.json").unwrap().unwrap(),
        b"replacement"
    );
}

#[test]
fn only_private_leaf_allows_atomic_rename_parent_write_access() {
    use std::os::windows::fs::OpenOptionsExt;
    use windows_sys::Win32::Storage::FileSystem::{
        FILE_ADD_FILE, FILE_FLAG_BACKUP_SEMANTICS, FILE_FLAG_OPEN_REPARSE_POINT,
        FILE_READ_ATTRIBUTES, FILE_SHARE_READ, FILE_SHARE_WRITE, READ_CONTROL,
    };

    let fixture = Fixture::new();
    let path = fixture.profile();
    let storage = PrivateDirectory::open(&path).unwrap();
    let parent_writer = |directory: &std::path::Path| {
        fs::OpenOptions::new()
            .access_mode(FILE_ADD_FILE | FILE_READ_ATTRIBUTES | READ_CONTROL)
            .share_mode(FILE_SHARE_READ | FILE_SHARE_WRITE)
            .custom_flags(FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT)
            .open(directory)
    };
    // Rename needs write access to exactly the destination parent. It must
    // not require relaxing ancestor sharing or closing any pinned handle.
    assert!(parent_writer(&path).is_ok());
    assert_eq!(
        parent_writer(path.parent().unwrap())
            .unwrap_err()
            .raw_os_error(),
        Some(32)
    );
    storage.write("account.json", b"first").unwrap();
    storage.write("account.json", b"replacement").unwrap();
    assert_eq!(
        storage.read("account.json").unwrap().unwrap(),
        b"replacement"
    );
    assert!(fs::rename(&path, path.with_file_name("moved")).is_err());
    assert!(fs::remove_file(path.join("profile.lock")).is_err());
    assert_eq!(fs::read_dir(&path).unwrap().count(), 2);
}
#[test]
fn drive_root_guard_accepts_existing_writer_and_still_pins_identity() {
    use handles::Kind;
    use std::os::windows::fs::OpenOptionsExt;
    use windows_sys::Win32::Storage::FileSystem::{
        FILE_ADD_FILE, FILE_FLAG_BACKUP_SEMANTICS, FILE_FLAG_OPEN_REPARSE_POINT,
        FILE_READ_ATTRIBUTES, FILE_SHARE_READ, FILE_SHARE_WRITE, OPEN_EXISTING,
    };
    let fixture = Fixture::new();
    // Exercise the exact drive-root handle policy on an owned disposable
    // directory. Do not request write access to or modify the system root.
    let writer = fs::OpenOptions::new()
        .access_mode(FILE_ADD_FILE | FILE_READ_ATTRIBUTES)
        .share_mode(FILE_SHARE_READ | FILE_SHARE_WRITE)
        .custom_flags(FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT)
        .open(&fixture.0)
        .unwrap();
    let old_policy = handles::open(&fixture.0, Kind::Directory, OPEN_EXISTING, None);
    assert!(matches!(old_policy, Err(Error::Io(detail)) if detail.os_code == Some(32)));
    let guard = handles::open(&fixture.0, Kind::DriveRoot, OPEN_EXISTING, None).unwrap();
    assert_eq!(
        handles::identity(&guard, true, 0).unwrap(),
        handles::identity(&writer, true, 0).unwrap()
    );
    let moved = fixture.0.with_extension("moved");
    drop(writer);
    assert!(fs::rename(&fixture.0, &moved).is_err());
    drop(guard);
    fs::rename(&fixture.0, &moved).unwrap();
    fs::rename(&moved, &fixture.0).unwrap();
}

#[test]
fn revalidation_keeps_private_parent_write_sharing() {
    use std::os::windows::fs::OpenOptionsExt;
    use windows_sys::Win32::Storage::FileSystem::{
        FILE_ADD_FILE, FILE_FLAG_BACKUP_SEMANTICS, FILE_FLAG_OPEN_REPARSE_POINT,
        FILE_READ_ATTRIBUTES, FILE_SHARE_READ, FILE_SHARE_WRITE,
    };
    let fixture = Fixture::new();
    let path = fixture.profile();
    let storage = PrivateDirectory::open(&path).unwrap();
    let writer = fs::OpenOptions::new()
        .access_mode(FILE_ADD_FILE | FILE_READ_ATTRIBUTES)
        .share_mode(FILE_SHARE_READ | FILE_SHARE_WRITE)
        .custom_flags(FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT)
        .open(&path)
        .unwrap();
    // Acquisition and every later identity check must use the same role.
    // Reopening the private parent as a strict ancestor would fail here.
    storage.write("account.json", b"first").unwrap();
    storage.write("account.json", b"replacement").unwrap();
    assert_eq!(
        storage.read("account.json").unwrap().unwrap(),
        b"replacement"
    );
    assert!(fs::rename(&path, path.with_file_name("moved")).is_err());
    drop(writer);
}

#[test]
fn pins_directory_ancestors_and_lock_until_owner_release() {
    let fixture = Fixture::new();
    let path = fixture.profile();
    let storage = PrivateDirectory::open(&path).unwrap();
    assert!(fs::rename(&path, path.with_file_name("moved")).is_err());
    assert!(fs::rename(path.parent().unwrap(), fixture.0.join("moved-ancestor")).is_err());
    assert!(fs::remove_file(path.join("profile.lock")).is_err());
    storage.write("account.json", b"still-current").unwrap();
    drop(storage);
    fs::rename(&path, path.with_file_name("moved")).unwrap();
}

#[test]
fn hardlinks_directories_widened_acls_and_size_are_not_credentials() {
    let fixture = Fixture::new();
    let path = fixture.profile();
    let storage = PrivateDirectory::open(&path).unwrap();
    let target = path.join("account.json");
    storage.write("account.json", b"private-original").unwrap();
    let alias = fixture.0.join("alias");
    fs::hard_link(&target, &alias).unwrap();
    assert_eq!(storage.read("account.json"), Err(Error::Invalid));
    assert_eq!(
        storage.write("account.json", b"must-not-write"),
        Err(Error::Invalid)
    );
    assert_eq!(fs::read(&alias).unwrap(), b"private-original");
    fs::remove_file(&alias).unwrap();
    storage.write("account.json", b"private-original").unwrap();
    crate::security::widen_for_test(&target);
    assert_eq!(storage.read("account.json"), Err(Error::Invalid));
    assert_eq!(
        storage.write("account.json", b"must-not-write"),
        Err(Error::Invalid)
    );
    fs::remove_file(&target).unwrap();
    fs::create_dir(&target).unwrap();
    assert_eq!(storage.read("account.json"), Err(Error::Invalid));
    fs::remove_dir(&target).unwrap();
    assert_eq!(
        storage.write("account.json", &[0; 65537]),
        Err(Error::Invalid)
    );
    storage.write("account.json", b"small").unwrap();
    fs::write(&target, vec![0; 65537]).unwrap();
    assert_eq!(storage.read("account.json"), Err(Error::Invalid));
}

#[test]
fn traversal_device_paths_ads_junctions_and_unprotected_roots_are_rejected() {
    let fixture = Fixture::new();
    let storage = PrivateDirectory::open(&fixture.profile()).unwrap();
    for name in [
        "",
        ".",
        "..",
        "../other",
        "other\\name",
        "a:secret",
        "CON",
        "con.json",
        "LPT1",
        "COM9.lock",
        "trailing.",
    ] {
        assert_eq!(storage.path(name), Err(Error::Invalid));
    }
    for path in [
        r"C:relative",
        r"\relative",
        r"\\server\share\profile",
        r"\\.\C:\profile",
        r"C:\data:stream\profile",
    ] {
        assert!(matches!(
            PrivateDirectory::open(&PathBuf::from(path)),
            Err(Error::Invalid)
        ));
    }
    let unprotected = fixture.0.join("unprotected");
    fs::create_dir(&unprotected).unwrap();
    assert!(matches!(
        PrivateDirectory::open(&unprotected),
        Err(Error::Invalid)
    ));
    let alias = fixture.0.join("junction");
    // NTFS junction creation requires no symlink/developer-mode privilege.
    let result = Command::new("cmd.exe")
        .args(["/D", "/C", "mklink", "/J"])
        .arg(&alias)
        .arg(storage.path("account.json").unwrap().parent().unwrap())
        .output()
        .unwrap();
    assert!(
        result.status.success(),
        "junction fixture could not be created"
    );
    assert!(matches!(
        PrivateDirectory::open(&alias),
        Err(Error::Invalid)
    ));
    // Non-recursive remove_dir deletes only the fixture junction, not its target.
    fs::remove_dir(alias).unwrap();
}

#[test]
fn bounded_enumeration_has_explicit_capacity_failure() {
    let fixture = Fixture::new();
    let path = fixture.profile();
    let storage = PrivateDirectory::open(&path).unwrap();
    for index in 0..255 {
        fs::write(path.join(format!("fixture-{index}")), b"").unwrap();
    }
    assert_eq!(storage.room_count().unwrap(), 0); // 255 entries + lock = 256.
    assert_eq!(
        storage.write("account.json", b"must-not-create"),
        Err(Error::Capacity)
    );
    assert!(!path.join("account.json").exists());
    assert_eq!(fs::read_dir(&path).unwrap().count(), 256);
    fs::write(path.join("one-extra"), b"").unwrap();
    assert_eq!(storage.room_count(), Err(Error::Capacity));
}

#[test]
fn sharing_failure_retains_win32_code_and_safe_account_category() {
    use std::os::windows::fs::OpenOptionsExt;
    let fixture = Fixture::new();
    let path = fixture.profile();
    let storage = PrivateDirectory::open(&path).unwrap();
    storage
        .write("account.json", b"fixture-secret-not-log-data")
        .unwrap();
    let exclusive = fs::OpenOptions::new()
        .read(true)
        .share_mode(0)
        .open(path.join("account.json"))
        .unwrap();
    let error = storage.read("account.json").unwrap_err();
    let crate::Error::Io(detail) = error else {
        panic!("expected syscall failure, got {error:?}")
    };
    assert_eq!(detail.os_code, Some(32));
    assert_eq!(detail.target, crate::Target::AccountFile);
    assert!(matches!(
        detail.operation,
        crate::Operation::OpenFile | crate::Operation::FileMetadata
    ));
    assert!(!format!("{error:?}").contains("fixture-secret"));
    drop(exclusive);
    assert_eq!(
        storage.read("account.json").unwrap().unwrap(),
        b"fixture-secret-not-log-data"
    );
}
