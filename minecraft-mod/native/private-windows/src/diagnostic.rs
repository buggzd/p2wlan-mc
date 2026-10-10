//! Fixed operation/target labels and numeric OS codes only. Never retain paths,
//! SID values, file contents or formatted io::Error messages.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Operation {
    OpenFile,
    CreateDirectory,
    FileIdentity,
    VolumeInformation,
    RenameFile,
    DiscardTemporary,
    OpenProcessToken,
    TokenInformation,
    SidString,
    SecurityDescriptor,
    SecurityInformation,
    FileMetadata,
    ReadFile,
    WriteFile,
    SyncFile,
    ReadDirectory,
    ReadDirectoryEntry,
}
impl Operation {
    pub fn code(self) -> &'static str {
        match self {
            Self::OpenFile => "OPEN_FILE",
            Self::CreateDirectory => "CREATE_DIRECTORY",
            Self::FileIdentity => "FILE_IDENTITY",
            Self::VolumeInformation => "VOLUME_INFORMATION",
            Self::RenameFile => "RENAME_FILE",
            Self::DiscardTemporary => "DISCARD_TEMPORARY",
            Self::OpenProcessToken => "OPEN_PROCESS_TOKEN",
            Self::TokenInformation => "TOKEN_INFORMATION",
            Self::SidString => "SID_STRING",
            Self::SecurityDescriptor => "SECURITY_DESCRIPTOR",
            Self::SecurityInformation => "SECURITY_INFORMATION",
            Self::FileMetadata => "FILE_METADATA",
            Self::ReadFile => "READ_FILE",
            Self::WriteFile => "WRITE_FILE",
            Self::SyncFile => "SYNC_FILE",
            Self::ReadDirectory => "READ_DIRECTORY",
            Self::ReadDirectoryEntry => "READ_DIRECTORY_ENTRY",
        }
    }
}
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Target {
    Unknown,
    ProcessToken,
    Ancestor,
    ProfileDirectory,
    ProfileLock,
    AccountFile,
    RoomFile,
    TemporaryFile,
}
impl Target {
    pub fn code(self) -> &'static str {
        match self {
            Self::Unknown => "UNKNOWN",
            Self::ProcessToken => "PROCESS_TOKEN",
            Self::Ancestor => "ANCESTOR",
            Self::ProfileDirectory => "PROFILE_DIRECTORY",
            Self::ProfileLock => "PROFILE_LOCK",
            Self::AccountFile => "ACCOUNT_FILE",
            Self::RoomFile => "ROOM_FILE",
            Self::TemporaryFile => "TEMPORARY_FILE",
        }
    }
    #[cfg(windows)]
    pub(crate) fn file(name: &str) -> Self {
        if name == "account.json" {
            Self::AccountFile
        } else if name == "profile.lock" {
            Self::ProfileLock
        } else if name.starts_with("room-") {
            Self::RoomFile
        } else if name.starts_with(".tmp-") {
            Self::TemporaryFile
        } else {
            Self::Unknown
        }
    }
}
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Failure {
    pub operation: Operation,
    pub target: Target,
    pub os_code: Option<i32>,
    pub path_depth: Option<u8>,
}
impl Failure {
    pub fn new(operation: Operation, os_code: Option<i32>) -> Self {
        Self {
            operation,
            target: Target::Unknown,
            os_code,
            path_depth: None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn numeric_failure_is_contextualized_without_preserving_io_text() {
        let error = std::io::Error::from_raw_os_error(32);
        let value = crate::Error::io(Operation::OpenFile, &error).at(Target::Ancestor, Some(3));
        assert_eq!(
            value,
            crate::Error::Io(Failure {
                operation: Operation::OpenFile,
                target: Target::Ancestor,
                os_code: Some(32),
                path_depth: Some(3),
            })
        );
        let value = crate::Error::io(
            Operation::ReadFile,
            &std::io::Error::other("fixture-secret"),
        );
        assert!(!format!("{value:?}").contains("fixture-secret"));
        assert_eq!(
            Operation::SecurityInformation.code(),
            "SECURITY_INFORMATION"
        );
        assert_eq!(
            crate::Error::Invalid.at(Target::AccountFile, None),
            crate::Error::Invalid
        );
    }
}
