use crate::AgentError;
#[cfg(unix)]
use crate::ProfileIo;
use std::path::{Path, PathBuf};

#[cfg(unix)]
const FILE_LIMIT: usize = 64 * 1024;

#[cfg(unix)]
use crate::Identity;
#[cfg(unix)]
use rustix::fs::{open, openat, renameat, unlinkat, AtFlags, Mode, OFlags};
#[cfg(unix)]
use std::fs::{self, File};
#[cfg(unix)]
use std::io::{Read, Write};
#[cfg(unix)]
use std::os::unix::fs::{DirBuilderExt, MetadataExt};

/// One exclusively locked, pinned directory. Publication stays relative to its
/// descriptor even if the pathname is replaced. A successor directory never
/// receives old credentials. Windows profiles use the isolated Win32 filesystem capability.
#[cfg(unix)]
pub struct PrivateDirectory {
    root: PathBuf,
    directory: File,
    identity: fs::Metadata,
    lock: File,
    lock_identity: fs::Metadata,
}
#[cfg(unix)]
fn inspect(metadata: &fs::Metadata, directory: &fs::Metadata) -> Result<(), AgentError> {
    if !metadata.is_file()
        || metadata.len() > FILE_LIMIT as u64
        || metadata.nlink() != 1
        || metadata.uid() != directory.uid()
        || metadata.mode() & 0o077 != 0
    {
        return Err(AgentError::ProfileInvalid);
    }
    Ok(())
}
#[cfg(unix)]
fn read_flags() -> OFlags {
    OFlags::RDONLY | OFlags::CLOEXEC | OFlags::NOFOLLOW | OFlags::NONBLOCK
}
#[cfg(unix)]
impl PrivateDirectory {
    pub fn open(path: &Path) -> Result<Self, AgentError> {
        if !path.exists() {
            let mut builder = fs::DirBuilder::new();
            builder.recursive(true).mode(0o700);
            builder.create(path).map_err(|error| {
                ProfileIo::error("CREATE_DIRECTORY", "PROFILE_DIRECTORY", error)
            })?;
        }
        let metadata = fs::symlink_metadata(path)
            .map_err(|error| ProfileIo::error("FILE_METADATA", "PROFILE_DIRECTORY", error))?;
        if !metadata.is_dir()
            || metadata.is_symlink()
            || metadata.mode() & 0o077 != 0
            || metadata.uid() != rustix::process::geteuid().as_raw()
        {
            return Err(AgentError::ProfileInvalid);
        }
        let directory = File::from(
            open(path, read_flags() | OFlags::DIRECTORY, Mode::empty()).map_err(|error| {
                ProfileIo::error("OPEN_FILE", "PROFILE_DIRECTORY", error.into())
            })?,
        );
        let identity = directory
            .metadata()
            .map_err(|error| ProfileIo::error("FILE_METADATA", "PROFILE_DIRECTORY", error))?;
        if identity.dev() != metadata.dev() || identity.ino() != metadata.ino() {
            return Err(AgentError::ProfileInvalid);
        }
        let root = path
            .canonicalize()
            .map_err(|error| ProfileIo::error("RESOLVE_PATH", "PROFILE_DIRECTORY", error))?;
        let lock = File::from(
            openat(
                &directory,
                "profile.lock",
                OFlags::RDWR
                    | OFlags::CLOEXEC
                    | OFlags::NOFOLLOW
                    | OFlags::NONBLOCK
                    | OFlags::CREATE,
                Mode::from_raw_mode(0o600),
            )
            .map_err(|error| ProfileIo::error("OPEN_FILE", "PROFILE_LOCK", error.into()))?,
        );
        let lock_identity = lock
            .metadata()
            .map_err(|error| ProfileIo::error("FILE_METADATA", "PROFILE_LOCK", error))?;
        inspect(&lock_identity, &identity)?;
        lock.try_lock().map_err(|_| AgentError::ProfileBusy)?;
        let result = Self {
            root,
            directory,
            identity,
            lock,
            lock_identity,
        };
        result.current()?;
        Ok(result)
    }
    fn current(&self) -> Result<(), AgentError> {
        let metadata = fs::symlink_metadata(&self.root)
            .map_err(|error| ProfileIo::error("FILE_METADATA", "PROFILE_DIRECTORY", error))?;
        if !metadata.is_dir()
            || metadata.is_symlink()
            || metadata.dev() != self.identity.dev()
            || metadata.ino() != self.identity.ino()
            || metadata.uid() != self.identity.uid()
            || metadata.mode() & 0o077 != 0
        {
            return Err(AgentError::ProfileInvalid);
        }
        let current = File::from(
            openat(&self.directory, "profile.lock", read_flags(), Mode::empty())
                .map_err(|error| ProfileIo::error("OPEN_FILE", "PROFILE_LOCK", error.into()))?,
        );
        let metadata = current
            .metadata()
            .map_err(|error| ProfileIo::error("FILE_METADATA", "PROFILE_LOCK", error))?;
        inspect(&metadata, &self.identity)?;
        inspect(
            &self
                .lock
                .metadata()
                .map_err(|error| ProfileIo::error("FILE_METADATA", "PROFILE_LOCK", error))?,
            &self.identity,
        )?;
        if metadata.dev() != self.lock_identity.dev() || metadata.ino() != self.lock_identity.ino()
        {
            return Err(AgentError::ProfileInvalid);
        }
        Ok(())
    }
    pub fn path(&self, name: &str) -> Result<PathBuf, AgentError> {
        if name.is_empty()
            || name == "."
            || name == ".."
            || name.len() > 128
            || !name
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'-'))
        {
            return Err(AgentError::ProfileInvalid);
        }
        self.current()?;
        Ok(self.root.join(name))
    }
    pub fn read(&self, name: &str) -> Result<Option<Vec<u8>>, AgentError> {
        self.path(name)?;
        let file = match openat(&self.directory, name, read_flags(), Mode::empty()) {
            Ok(fd) => File::from(fd),
            Err(rustix::io::Errno::NOENT) => return Ok(None),
            Err(error) => {
                return Err(ProfileIo::error(
                    "OPEN_FILE",
                    ProfileIo::file(name),
                    error.into(),
                ))
            }
        };
        inspect(
            &file
                .metadata()
                .map_err(|error| ProfileIo::error("FILE_METADATA", ProfileIo::file(name), error))?,
            &self.identity,
        )?;
        let mut bytes = Vec::new();
        file.take(FILE_LIMIT as u64 + 1)
            .read_to_end(&mut bytes)
            .map_err(|error| ProfileIo::error("READ_FILE", ProfileIo::file(name), error))?;
        if bytes.len() > FILE_LIMIT {
            return Err(AgentError::ProfileInvalid);
        }
        self.current()?;
        Ok(Some(bytes))
    }
    pub fn write(&self, name: &str, bytes: &[u8]) -> Result<(), AgentError> {
        if bytes.len() > FILE_LIMIT {
            return Err(AgentError::ProfileInvalid);
        }
        self.read(name)?;
        let temporary = format!(".tmp-{}", Identity::generate()?.as_str());
        let file = File::from(
            openat(
                &self.directory,
                temporary.as_str(),
                OFlags::WRONLY | OFlags::CREATE | OFlags::EXCL | OFlags::CLOEXEC | OFlags::NOFOLLOW,
                Mode::from_raw_mode(0o600),
            )
            .map_err(|error| ProfileIo::error("OPEN_FILE", "TEMPORARY_FILE", error.into()))?,
        );
        let result = (|| {
            let mut file = file;
            file.write_all(bytes)
                .map_err(|error| ProfileIo::error("WRITE_FILE", "TEMPORARY_FILE", error))?;
            file.sync_all()
                .map_err(|error| ProfileIo::error("SYNC_FILE", "TEMPORARY_FILE", error))?;
            self.read(name)?;
            self.current()?;
            renameat(&self.directory, temporary.as_str(), &self.directory, name).map_err(
                |error| ProfileIo::error("RENAME_FILE", ProfileIo::file(name), error.into()),
            )?;
            self.directory
                .sync_all()
                .map_err(|error| ProfileIo::error("SYNC_FILE", "PROFILE_DIRECTORY", error))?;
            self.current()?;
            Ok(())
        })();
        if result.is_err() {
            let _ = unlinkat(&self.directory, temporary.as_str(), AtFlags::empty());
        }
        result
    }
    pub fn room_count(&self) -> Result<usize, AgentError> {
        self.current()?;
        let mut count = 0;
        for (index, entry) in fs::read_dir(&self.root)
            .map_err(|error| ProfileIo::error("READ_DIRECTORY", "PROFILE_DIRECTORY", error))?
            .enumerate()
        {
            if index >= 256 {
                return Err(AgentError::ProfileCapacity);
            }
            let name = entry
                .map_err(|error| {
                    ProfileIo::error("READ_DIRECTORY_ENTRY", "PROFILE_DIRECTORY", error)
                })?
                .file_name();
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
        Ok(count)
    }
}

#[cfg(windows)]
pub struct PrivateDirectory(p2wlan_mc_private_windows::PrivateDirectory);
#[cfg(windows)]
fn windows_error(error: p2wlan_mc_private_windows::Error) -> AgentError {
    use p2wlan_mc_private_windows::Error;
    match error {
        Error::Io(detail) | Error::Cleanup(detail) => AgentError::ProfileIo(crate::ProfileIo {
            operation: detail.operation.code(),
            target: detail.target.code(),
            os_code: detail.os_code,
            path_depth: detail.path_depth,
        }),
        Error::Invalid => AgentError::ProfileInvalid,
        Error::Busy => AgentError::ProfileBusy,
        Error::Capacity => AgentError::ProfileCapacity,
        Error::Entropy => AgentError::Entropy,
    }
}
#[cfg(windows)]
impl PrivateDirectory {
    pub fn open(path: &Path) -> Result<Self, AgentError> {
        p2wlan_mc_private_windows::PrivateDirectory::open(path)
            .map(Self)
            .map_err(windows_error)
    }
    pub fn path(&self, name: &str) -> Result<PathBuf, AgentError> {
        self.0.path(name).map_err(windows_error)
    }
    pub fn read(&self, name: &str) -> Result<Option<Vec<u8>>, AgentError> {
        self.0.read(name).map_err(windows_error)
    }
    pub fn write(&self, name: &str, bytes: &[u8]) -> Result<(), AgentError> {
        self.0.write(name, bytes).map_err(windows_error)
    }
    pub fn room_count(&self) -> Result<usize, AgentError> {
        self.0.room_count().map_err(windows_error)
    }
}

// Do not persist plaintext credentials on a platform whose private-directory
// permissions have not been implemented and verified.
#[cfg(not(any(unix, windows)))]
pub struct PrivateDirectory;
#[cfg(not(any(unix, windows)))]
impl PrivateDirectory {
    pub fn open(_: &Path) -> Result<Self, AgentError> {
        Err(AgentError::ProfileUnsupported)
    }
    pub fn path(&self, _: &str) -> Result<PathBuf, AgentError> {
        Err(AgentError::ProfileUnsupported)
    }
    pub fn read(&self, _: &str) -> Result<Option<Vec<u8>>, AgentError> {
        Err(AgentError::ProfileUnsupported)
    }
    pub fn write(&self, _: &str, _: &[u8]) -> Result<(), AgentError> {
        Err(AgentError::ProfileUnsupported)
    }
    pub fn room_count(&self) -> Result<usize, AgentError> {
        Err(AgentError::ProfileUnsupported)
    }
}
