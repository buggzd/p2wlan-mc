//! Token/descriptor ownership and ACL validation; raw pointers never escape.
use crate::{Error, Failure, Operation, Target};
use std::ffi::c_void;
use std::fs::File;
use std::mem::{size_of, zeroed};
use std::os::windows::io::{AsRawHandle, FromRawHandle};
use std::ptr::null_mut;
use windows_sys::Win32::Foundation::{LocalFree, ERROR_INSUFFICIENT_BUFFER, HANDLE};
use windows_sys::Win32::Security::Authorization::{
    ConvertSidToStringSidW, ConvertStringSecurityDescriptorToSecurityDescriptorW, GetSecurityInfo,
    SE_FILE_OBJECT,
};
use windows_sys::Win32::Security::*;
use windows_sys::Win32::Storage::FileSystem::FILE_ALL_ACCESS;
use windows_sys::Win32::System::Threading::{GetCurrentProcess, OpenProcessToken};

struct LocalMemory(*mut c_void);
impl Drop for LocalMemory {
    fn drop(&mut self) {
        // SAFETY: only successful LocalAlloc-backed Win32 outputs enter here.
        unsafe {
            LocalFree(self.0);
        }
    }
}

pub(crate) struct Security {
    // Vec<usize> provides at least pointer alignment for TOKEN_USER. SID and
    // TOKEN_USER pointers returned by the OS remain in this owned allocation.
    token: Vec<usize>,
    descriptor: LocalMemory,
}
// SAFETY: Security owns immutable token/descriptor buffers; no API mutates or
// transfers them. Views live only for synchronous calls borrowing &self.
unsafe impl Send for Security {}
unsafe impl Sync for Security {}

impl Security {
    pub(crate) fn current_user() -> Result<Self, Error> {
        // SAFETY: all output buffers are properly aligned/bounded, handles are
        // adopted once, and descriptor/SID views do not outlive their buffers.
        unsafe {
            let mut handle: HANDLE = null_mut();
            if OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut handle) == 0 {
                return Err(Error::last(Operation::OpenProcessToken).at(Target::ProcessToken, None));
            }
            let handle = File::from_raw_handle(handle);
            let mut needed = 0;
            let measured = GetTokenInformation(
                handle.as_raw_handle(),
                TokenUser,
                null_mut(),
                0,
                &mut needed,
            );
            if measured == 0 {
                let error = std::io::Error::last_os_error();
                if error.raw_os_error() != Some(ERROR_INSUFFICIENT_BUFFER as i32) {
                    return Err(Error::io(Operation::TokenInformation, &error)
                        .at(Target::ProcessToken, None));
                }
            }
            if needed < size_of::<TOKEN_USER>() as u32 || needed > 4096 {
                return Err(Error::Io(Failure::new(Operation::TokenInformation, None))
                    .at(Target::ProcessToken, None));
            }
            let mut token = vec![0usize; (needed as usize).div_ceil(size_of::<usize>())];
            let size = (token.len() * size_of::<usize>()) as u32;
            if GetTokenInformation(
                handle.as_raw_handle(),
                TokenUser,
                token.as_mut_ptr().cast(),
                size,
                &mut needed,
            ) == 0
            {
                return Err(Error::last(Operation::TokenInformation).at(Target::ProcessToken, None));
            }
            if needed < size_of::<TOKEN_USER>() as u32 || needed > size {
                return Err(Error::Io(Failure::new(Operation::TokenInformation, None))
                    .at(Target::ProcessToken, None));
            }
            let sid = (*(token.as_ptr().cast::<TOKEN_USER>())).User.Sid;
            let sid_text = sid_string(sid)?;
            // Protected DACL, current user owner, and one inheritable full-access
            // ACE. This is supplied at CREATE_NEW, not repaired after a write.
            let text: Vec<u16> = format!("O:{sid_text}D:P(A;OICI;FA;;;{sid_text})")
                .encode_utf16()
                .chain([0])
                .collect();
            let mut descriptor = null_mut();
            if ConvertStringSecurityDescriptorToSecurityDescriptorW(
                text.as_ptr(),
                1,
                &mut descriptor,
                null_mut(),
            ) == 0
            {
                return Err(
                    Error::last(Operation::SecurityDescriptor).at(Target::ProcessToken, None)
                );
            }
            Ok(Self {
                token,
                descriptor: LocalMemory(descriptor),
            })
        }
    }
    pub(crate) fn attributes(&self) -> SECURITY_ATTRIBUTES {
        SECURITY_ATTRIBUTES {
            nLength: size_of::<SECURITY_ATTRIBUTES>() as u32,
            lpSecurityDescriptor: self.descriptor.0,
            bInheritHandle: 0,
        }
    }
    pub(crate) fn validate(&self, file: &File, directory: bool) -> Result<(), Error> {
        // SAFETY: GetSecurityInfo returns a self-contained LocalAlloc allocation.
        // OS-validated ACE/SID pointers are used only while that allocation lives.
        unsafe {
            let mut descriptor = null_mut();
            let mut owner = null_mut();
            let mut dacl = null_mut();
            let result = GetSecurityInfo(
                file.as_raw_handle(),
                SE_FILE_OBJECT,
                OWNER_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION,
                &mut owner,
                null_mut(),
                &mut dacl,
                null_mut(),
                &mut descriptor,
            );
            // GetSecurityInfo returns the Win32 error directly, not GetLastError.
            if result != 0 {
                return Err(Error::Io(Failure::new(
                    Operation::SecurityInformation,
                    Some(result as i32),
                )));
            }
            let _memory = LocalMemory(descriptor);
            let sid = (*(self.token.as_ptr().cast::<TOKEN_USER>())).User.Sid;
            if owner.is_null()
                || IsValidSid(owner) == 0
                || EqualSid(owner, sid) == 0
                || dacl.is_null()
                || IsValidAcl(dacl) == 0
            {
                return Err(Error::Invalid);
            }
            let mut control = 0;
            let mut revision = 0;
            if GetSecurityDescriptorControl(descriptor, &mut control, &mut revision) == 0
                || directory && control & SE_DACL_PROTECTED == 0
            {
                return Err(Error::Invalid);
            }
            let mut info: ACL_SIZE_INFORMATION = zeroed();
            if GetAclInformation(
                dacl,
                (&mut info as *mut ACL_SIZE_INFORMATION).cast(),
                size_of::<ACL_SIZE_INFORMATION>() as u32,
                AclSizeInformation,
            ) == 0
                || info.AceCount != 1
            {
                return Err(Error::Invalid);
            }
            let mut ace = null_mut();
            if GetAce(dacl, 0, &mut ace) == 0 || ace.is_null() {
                return Err(Error::Invalid);
            }
            let header = &*ace.cast::<ACE_HEADER>();
            // Access-allowed ACE only. Reject callback/object/conditional ACEs,
            // inherit-only rights, or any broader identity, not just Everyone.
            if header.AceType != 0
                || header.AceSize < 16
                || header.AceFlags as u32 & INHERIT_ONLY_ACE != 0
            {
                return Err(Error::Invalid);
            }
            let allowed = &*ace.cast::<ACCESS_ALLOWED_ACE>();
            let ace_sid = (&allowed.SidStart as *const u32).cast_mut().cast();
            if GetLengthSid(ace_sid) > header.AceSize as u32 - 8
                || IsValidSid(ace_sid) == 0
                || EqualSid(sid, ace_sid) == 0
                || allowed.Mask != FILE_ALL_ACCESS
            {
                return Err(Error::Invalid);
            }
            Ok(())
        }
    }
}

unsafe fn sid_string(sid: PSID) -> Result<String, Error> {
    // SAFETY: caller supplies an OS-owned valid SID in a live token buffer;
    // ConvertSidToStringSidW yields a bounded, NUL-terminated LocalAlloc string.
    unsafe {
        if sid.is_null() || IsValidSid(sid) == 0 {
            return Err(Error::Invalid);
        }
        let mut text = null_mut();
        if ConvertSidToStringSidW(sid, &mut text) == 0 {
            return Err(Error::last(Operation::SidString).at(Target::ProcessToken, None));
        }
        let _memory = LocalMemory(text.cast());
        let mut result = String::new();
        // SID strings have a documented maximum well below this fixed bound.
        for index in 0..256 {
            let value = *text.add(index);
            if value == 0 {
                return Ok(result);
            }
            if value > 127 {
                return Err(Error::Invalid);
            }
            result.push(value as u8 as char);
        }
        Err(Error::Invalid)
    }
}

#[cfg(test)]
pub(crate) fn widen_for_test(path: &std::path::Path) {
    use windows_sys::Win32::Security::Authorization::SetNamedSecurityInfoW;
    let text: Vec<u16> = "D:P(A;;FA;;;WD)".encode_utf16().chain([0]).collect();
    // SAFETY: test-only mutation of an explicitly owned fixture, with all OS
    // outputs freed and descriptor views kept within the allocation lifetime.
    unsafe {
        let mut descriptor = null_mut();
        assert_ne!(
            ConvertStringSecurityDescriptorToSecurityDescriptorW(
                text.as_ptr(),
                1,
                &mut descriptor,
                null_mut()
            ),
            0
        );
        let _memory = LocalMemory(descriptor);
        let mut dacl = null_mut();
        let mut present = 0;
        let mut defaulted = 0;
        assert_ne!(
            GetSecurityDescriptorDacl(descriptor, &mut present, &mut dacl, &mut defaulted),
            0
        );
        let mut name = crate::handles::wide(path).unwrap();
        assert_eq!(
            SetNamedSecurityInfoW(
                name.as_mut_ptr(),
                SE_FILE_OBJECT,
                DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
                null_mut(),
                null_mut(),
                dacl,
                null_mut()
            ),
            0
        );
    }
}
