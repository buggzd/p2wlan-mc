//! Private Windows storage. Network state is not owned or cached here.
//! The safe agent uses this capability without importing any Win32 FFI.
#![deny(unsafe_op_in_unsafe_fn)]

mod diagnostic;
pub use diagnostic::{Failure, Operation, Target};

#[cfg(windows)]
mod handles;
#[cfg(windows)]
mod security;
#[cfg(windows)]
mod storage;
#[cfg(windows)]
pub use storage::PrivateDirectory;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Error {
    Io(Failure),
    Invalid,
    Busy,
    Capacity,
    Entropy,
    Cleanup(Failure),
}

// Do not allow Windows device names, ADS, ambiguous trailing dots, traversal,
// or a path component whose interpretation differs between Win32 and NT.
#[cfg(any(windows, test))]
fn valid_name(name: &str) -> bool {
    let device = name.split('.').next().unwrap_or("").to_ascii_uppercase();
    !name.is_empty()
        && name.len() <= 128
        && name != "."
        && name != ".."
        && !name.ends_with('.')
        && name
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'-'))
        && !matches!(device.as_str(), "CON" | "PRN" | "AUX" | "NUL")
        && !(device.len() == 4
            && (device.starts_with("COM") || device.starts_with("LPT"))
            && device.as_bytes()[3].is_ascii_digit())
}

#[cfg(all(test, windows))]
mod tests;

#[cfg(all(test, not(windows)))]
mod name_tests {
    #[test]
    fn names_have_one_unambiguous_windows_interpretation() {
        for name in [
            "account.json",
            "profile.lock",
            ".tmp-012345",
            "room-a1.json",
        ] {
            assert!(super::valid_name(name));
        }
        for name in [
            "",
            ".",
            "..",
            "../other",
            "other/name",
            "other\\name",
            "account:stream",
            "account.",
            "CON",
            "con.json",
            "LPT1.json",
            "COM0",
            "COM9.lock",
            "NUL",
            "a b",
            "é",
        ] {
            assert!(!super::valid_name(name), "{name}");
        }
        assert!(!super::valid_name(&"a".repeat(129)));
    }
}

impl Error {
    pub fn io(operation: Operation, error: &std::io::Error) -> Self {
        Self::Io(Failure::new(operation, error.raw_os_error()))
    }
    #[cfg(windows)]
    pub(crate) fn last(operation: Operation) -> Self {
        Self::io(operation, &std::io::Error::last_os_error())
    }
    pub fn at(self, target: Target, path_depth: Option<u8>) -> Self {
        match self {
            Self::Io(mut failure) => {
                failure.target = target;
                failure.path_depth = path_depth;
                Self::Io(failure)
            }
            Self::Cleanup(mut failure) => {
                failure.target = target;
                failure.path_depth = path_depth;
                Self::Cleanup(failure)
            }
            other => other,
        }
    }
}
