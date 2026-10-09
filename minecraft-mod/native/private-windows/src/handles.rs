//! Win32 file handles, identity and bounded handle-relative mutation.
use crate::{security::Security, Error};
use std::fs::File;
use std::io;
use std::mem::{offset_of, size_of, zeroed};
use std::os::windows::ffi::OsStrExt;
use std::os::windows::io::{AsRawHandle, FromRawHandle};
use std::path::Path;
use std::ptr::{null, null_mut};
use windows_sys::Win32::Foundation::{ERROR_ALREADY_EXISTS, INVALID_HANDLE_VALUE};
use windows_sys::Win32::Storage::FileSystem::*;
use windows_sys::Win32::System::{
    SystemServices::FILE_PERSISTENT_ACLS, WindowsProgramming::DRIVE_FIXED,
};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct FileId(pub u32, pub u32, pub u32);

pub(crate) fn wide(path: &Path) -> Result<Vec<u16>, Error> {
    let value: Vec<u16> = path.as_os_str().encode_wide().collect();
    if value.contains(&0) || value.len() > 32700 {
        return Err(Error::Invalid);
    }
    Ok(value.into_iter().chain([0]).collect())
}

#[derive(Clone, Copy)]
pub(crate) enum Kind {
    Directory,
    PrivateDirectory,
    Read,
    Temporary,
    Lock,
}

pub(crate) fn open(
    path: &Path,
    kind: Kind,
    disposition: u32,
    security: Option<&Security>,
) -> Result<File, Error> {
    let name = wide(path)?;
    let attributes = security.map(Security::attributes);
    let access = FILE_READ_ATTRIBUTES
        | READ_CONTROL
        | FILE_READ_DATA
        | match kind {
            Kind::Temporary => FILE_WRITE_DATA | DELETE,
            Kind::Lock => FILE_WRITE_DATA,
            _ => 0,
        };
    // Ancestors refuse data-write/delete sharing. The private leaf alone
    // permits write sharing: Win32 atomic rename opens its destination parent
    // for FILE_ADD_FILE, even when renaming an already-open temporary file.
    // No directory permits delete sharing, so the exact tree stays pinned.
    // Attribute-only access is NOT covered by sharing flags. Every ancestor
    // is rechecked before credential I/O and has a pinned non-deletable child;
    // the private leaf contains the non-deletable profile lock. Identity,
    // reparse and private-ACL validation remain mandatory for every mutation.
    // File readers allow the daemon's own atomic save; the private-directory
    // lock/ACL remains the sole writer capability, not another state owner.
    let share = match kind {
        Kind::Directory | Kind::Temporary => FILE_SHARE_READ,
        Kind::PrivateDirectory | Kind::Lock => FILE_SHARE_READ | FILE_SHARE_WRITE,
        Kind::Read => FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
    };
    // SAFETY: NUL-terminated name and descriptor live through the synchronous
    // call. Returned non-inheritable handle is adopted once by std::fs::File.
    let raw = unsafe {
        CreateFileW(
            name.as_ptr(),
            access,
            share,
            attributes.as_ref().map_or(null(), |attributes| attributes),
            disposition,
            FILE_FLAG_OPEN_REPARSE_POINT
                | if matches!(kind, Kind::Directory | Kind::PrivateDirectory) {
                    FILE_FLAG_BACKUP_SEMANTICS
                } else {
                    0
                },
            null_mut(),
        )
    };
    if raw == INVALID_HANDLE_VALUE {
        return Err(Error::Io);
    }
    Ok(unsafe { File::from_raw_handle(raw) })
}

pub(crate) fn create_directory(path: &Path, security: &Security) -> Result<(), Error> {
    let name = wide(path)?;
    let attributes = security.attributes();
    // SAFETY: input buffers live through this synchronous call. Existing
    // directories are not chmod'ed, taken over or interpreted as success yet.
    if unsafe { CreateDirectoryW(name.as_ptr(), &attributes) } == 0
        && io::Error::last_os_error().raw_os_error() != Some(ERROR_ALREADY_EXISTS as i32)
    {
        return Err(Error::Io);
    }
    Ok(())
}

pub(crate) fn identity(file: &File, directory: bool, maximum: u64) -> Result<FileId, Error> {
    // SAFETY: valid file handle and correctly-sized output structure.
    let mut info: BY_HANDLE_FILE_INFORMATION = unsafe { zeroed() };
    if unsafe { GetFileInformationByHandle(file.as_raw_handle(), &mut info) } == 0 {
        return Err(Error::Io);
    }
    let is_directory = info.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY != 0;
    let size = ((info.nFileSizeHigh as u64) << 32) | info.nFileSizeLow as u64;
    if info.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT != 0
        || is_directory != directory
        || !directory && (info.nNumberOfLinks != 1 || size > maximum)
        || info.nFileIndexHigh == 0 && info.nFileIndexLow == 0
    {
        return Err(Error::Invalid);
    }
    Ok(FileId(
        info.dwVolumeSerialNumber,
        info.nFileIndexHigh,
        info.nFileIndexLow,
    ))
}

pub(crate) fn require_acls(file: &File) -> Result<(), Error> {
    let mut flags = 0;
    // SAFETY: the directory handle is live; all unused output buffers are NULL.
    if unsafe {
        GetVolumeInformationByHandleW(
            file.as_raw_handle(),
            null_mut(),
            0,
            null_mut(),
            null_mut(),
            &mut flags,
            null_mut(),
            0,
        )
    } == 0
        || flags & FILE_PERSISTENT_ACLS == 0
    {
        // FILE_PERSISTENT_ACLS; FAT/exFAT are intentionally not credentials stores.
        return Err(Error::Invalid);
    }
    Ok(())
}

pub(crate) fn rename(file: &File, target: &Path) -> Result<(), Error> {
    let name = wide(target)?;
    let name = &name[..name.len() - 1];
    let offset = offset_of!(FILE_RENAME_INFO, FileName);
    let size = offset + std::mem::size_of_val(name);
    let mut buffer = vec![0usize; size.div_ceil(size_of::<usize>())];
    // SAFETY: pointer-aligned allocation contains the complete variable-length
    // FILE_RENAME_INFO and UTF-16 name. Only the held file is renamed. All path
    // ancestors are pinned by the storage owner, not an earlier cached check.
    unsafe {
        let info = buffer.as_mut_ptr().cast::<FILE_RENAME_INFO>();
        (*info).Anonymous.ReplaceIfExists = 1;
        (*info).RootDirectory = null_mut();
        (*info).FileNameLength = std::mem::size_of_val(name) as u32;
        std::ptr::copy_nonoverlapping(
            name.as_ptr(),
            buffer.as_mut_ptr().cast::<u8>().add(offset).cast(),
            name.len(),
        );
        if SetFileInformationByHandle(
            file.as_raw_handle(),
            FileRenameInfo,
            buffer.as_ptr().cast(),
            size as u32,
        ) == 0
        {
            return Err(Error::Io);
        }
    }
    Ok(())
}

pub(crate) fn discard(file: &File) -> Result<(), Error> {
    let info = FILE_DISPOSITION_INFO { DeleteFile: 1 };
    // SAFETY: cleanup addresses only the still-owned temporary handle; no
    // pathname guessed from an error can delete a replacement/foreign file.
    if unsafe {
        SetFileInformationByHandle(
            file.as_raw_handle(),
            FileDispositionInfo,
            (&info as *const FILE_DISPOSITION_INFO).cast(),
            size_of::<FILE_DISPOSITION_INFO>() as u32,
        )
    } == 0
    {
        return Err(Error::Cleanup);
    }
    Ok(())
}

pub(crate) fn local_drive(path: &Path) -> Result<(), Error> {
    let path = wide(path)?;
    // SAFETY: valid NUL-terminated drive root; no output allocation is retained.
    if unsafe { GetDriveTypeW(path.as_ptr()) } != DRIVE_FIXED {
        return Err(Error::Invalid);
    }
    Ok(())
}
