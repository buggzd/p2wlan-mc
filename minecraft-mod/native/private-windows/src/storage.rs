use crate::{handles, security::Security, valid_name, Error};
use handles::{FileId, Kind};
use std::fs::{self, File};
use std::io::{Read, Write};
use std::os::windows::fs::MetadataExt;
use std::path::{Component, Path, PathBuf, Prefix};
use windows_sys::Win32::Storage::FileSystem::{
    CREATE_NEW, FILE_ATTRIBUTE_REPARSE_POINT, OPEN_ALWAYS, OPEN_EXISTING,
};

const FILE_LIMIT: usize = 64 * 1024;
const DIRECTORY_LIMIT: usize = 64;

/// One OS-user-private profile. No process-global cache or permission repair.
/// Ancestor handles and the profile lock stay live for this capability's entire
/// lifetime. All mutations revalidate exact directory/lock/file identities.
pub struct PrivateDirectory {
    root: PathBuf,
    directories: Vec<(PathBuf, File, FileId)>,
    security: Security,
    lock: File,
    lock_id: FileId,
}
impl PrivateDirectory {
    pub fn open(path: &Path) -> Result<Self, Error> {
        // Only fully-qualified local drive paths. Device namespaces, UNC,
        // drive-relative paths and normal-component ADS are not local profiles.
        let mut components = path.components();
        let drive = match components.next() {
            Some(Component::Prefix(prefix)) => match prefix.kind() {
                Prefix::Disk(drive) | Prefix::VerbatimDisk(drive) => drive,
                _ => return Err(Error::Invalid),
            },
            _ => return Err(Error::Invalid),
        };
        if components.next() != Some(Component::RootDir) {
            return Err(Error::Invalid);
        }
        let mut paths = vec![PathBuf::from(format!("{}:\\", drive as char))];
        for component in components {
            let Component::Normal(name) = component else {
                return Err(Error::Invalid);
            };
            let text = name.to_str().ok_or(Error::Invalid)?;
            if text.contains([':', '/', '\\']) || text.ends_with(['.', ' ']) {
                return Err(Error::Invalid);
            }
            let next = paths.last().ok_or(Error::Invalid)?.join(name);
            paths.push(next);
            if paths.len() > DIRECTORY_LIMIT {
                return Err(Error::Capacity);
            }
        }
        if paths.len() < 2 {
            return Err(Error::Invalid);
        }
        handles::local_drive(&paths[0])?;
        let root = paths.last().ok_or(Error::Invalid)?.clone();
        let security = Security::current_user()?;
        let mut directories = Vec::with_capacity(paths.len());
        for (index, path) in paths.into_iter().enumerate() {
            if index != 0 {
                handles::create_directory(&path, &security)?;
            }
            let kind = if path == root {
                Kind::PrivateDirectory
            } else {
                Kind::Directory
            };
            let directory = handles::open(&path, kind, OPEN_EXISTING, None)?;
            let identity = handles::identity(&directory, true, 0)?;
            handles::require_acls(&directory)?;
            directories.push((path, directory, identity));
        }
        let (_, directory, _) = directories.last().ok_or(Error::Invalid)?;
        security.validate(directory, true)?;
        let lock = handles::open(
            &root.join("profile.lock"),
            Kind::Lock,
            OPEN_ALWAYS,
            Some(&security),
        )?;
        let lock_id = handles::identity(&lock, false, FILE_LIMIT as u64)?;
        security.validate(&lock, false)?;
        lock.try_lock().map_err(|_| Error::Busy)?;
        let result = Self {
            root,
            directories,
            security,
            lock,
            lock_id,
        };
        result.current()?;
        Ok(result)
    }
    fn current(&self) -> Result<(), Error> {
        for (path, file, expected) in &self.directories {
            let current = handles::open(path, Kind::Directory, OPEN_EXISTING, None)?;
            if handles::identity(file, true, 0)? != *expected
                || handles::identity(&current, true, 0)? != *expected
            {
                return Err(Error::Invalid);
            }
        }
        let (_, directory, _) = self.directories.last().ok_or(Error::Invalid)?;
        self.security.validate(directory, true)?;
        let lock = handles::open(
            &self.root.join("profile.lock"),
            Kind::Read,
            OPEN_EXISTING,
            None,
        )?;
        if handles::identity(&lock, false, FILE_LIMIT as u64)? != self.lock_id
            || handles::identity(&self.lock, false, FILE_LIMIT as u64)? != self.lock_id
        {
            return Err(Error::Invalid);
        }
        self.security.validate(&lock, false)?;
        self.security.validate(&self.lock, false)
    }
    pub fn path(&self, name: &str) -> Result<PathBuf, Error> {
        if !valid_name(name) {
            return Err(Error::Invalid);
        }
        self.current()?;
        Ok(self.root.join(name))
    }
    pub fn read(&self, name: &str) -> Result<Option<Vec<u8>>, Error> {
        let path = self.path(name)?;
        // symlink_metadata distinguishes absent from inaccessible, and avoids
        // classifying a dangling reparse target as an absent ordinary file.
        match fs::symlink_metadata(&path) {
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                self.current()?;
                return Ok(None);
            }
            Err(_) => return Err(Error::Io),
            Ok(metadata)
                if !metadata.is_file()
                    || metadata.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT != 0 =>
            {
                return Err(Error::Invalid);
            }
            Ok(_) => (),
        }
        let file = handles::open(&path, Kind::Read, OPEN_EXISTING, None)?;
        let id = handles::identity(&file, false, FILE_LIMIT as u64)?;
        self.security.validate(&file, false)?;
        let mut bytes = Vec::new();
        (&file)
            .take(FILE_LIMIT as u64 + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| Error::Io)?;
        if bytes.len() > FILE_LIMIT || handles::identity(&file, false, FILE_LIMIT as u64)? != id {
            return Err(Error::Invalid);
        }
        self.security.validate(&file, false)?;
        self.current()?;
        Ok(Some(bytes))
    }
    pub fn write(&self, name: &str, bytes: &[u8]) -> Result<(), Error> {
        if bytes.len() > FILE_LIMIT {
            return Err(Error::Invalid);
        }
        let target = self.path(name)?;
        self.read(name)?;
        if self.entries()?.0 >= 256 {
            return Err(Error::Capacity);
        }
        let mut nonce = [0u8; 16];
        getrandom::getrandom(&mut nonce).map_err(|_| Error::Entropy)?;
        let temporary = format!(
            ".tmp-{}",
            nonce
                .iter()
                .map(|byte| format!("{byte:02x}"))
                .collect::<String>()
        );
        let mut file = handles::open(
            &self.path(&temporary)?,
            Kind::Temporary,
            CREATE_NEW,
            Some(&self.security),
        )?;
        let mut committed = false;
        let result = (|| {
            let id = handles::identity(&file, false, FILE_LIMIT as u64)?;
            self.security.validate(&file, false)?;
            file.write_all(bytes).map_err(|_| Error::Io)?;
            file.sync_all().map_err(|_| Error::Io)?;
            self.read(name)?;
            self.current()?;
            if handles::identity(&file, false, FILE_LIMIT as u64)? != id {
                return Err(Error::Invalid);
            }
            self.security.validate(&file, false)?;
            handles::rename(&file, &target)?;
            committed = true;
            file.sync_all().map_err(|_| Error::Io)?;
            if handles::identity(&file, false, FILE_LIMIT as u64)? != id {
                return Err(Error::Invalid);
            }
            self.security.validate(&file, false)?;
            self.current()?;
            Ok(())
        })();
        // Never delete the destination after a successful rename, even when
        // post-publication validation fails. Only an uncommitted temporary is
        // cleanup-owned. A separate flag tracks that exact mutation boundary.
        if result.is_err() && !committed {
            handles::discard(&file)?;
        }
        result
    }
    pub fn room_count(&self) -> Result<usize, Error> {
        self.entries().map(|(_, rooms)| rooms)
    }
    fn entries(&self) -> Result<(usize, usize), Error> {
        self.current()?;
        let mut count = 0;
        let mut entries = 0;
        for (index, entry) in fs::read_dir(&self.root).map_err(|_| Error::Io)?.enumerate() {
            if index >= 256 {
                return Err(Error::Capacity);
            }
            entries = index + 1;
            let name = entry.map_err(|_| Error::Io)?.file_name();
            if name.to_str().is_some_and(|name| {
                name.len() == 74
                    && name.starts_with("room-")
                    && name.ends_with(".json")
                    && name.as_bytes()[5..69]
                        .iter()
                        .all(|byte| byte.is_ascii_hexdigit())
            }) {
                count += 1;
            }
        }
        self.current()?;
        Ok((entries, count))
    }
}
