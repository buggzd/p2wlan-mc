//! A failure value, not a log parser or another lifecycle owner. Labels are
//! compile-time operations/file categories; OS error text and paths never enter IPC.
use serde::Serialize;
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
pub struct ProfileIo {
    pub operation: &'static str,
    pub target: &'static str,
    pub os_code: Option<i32>,
    pub path_depth: Option<u8>,
}
impl ProfileIo {
    #[cfg(unix)]
    pub(crate) fn error(
        operation: &'static str,
        target: &'static str,
        error: std::io::Error,
    ) -> crate::AgentError {
        crate::AgentError::ProfileIo(Self {
            operation,
            target,
            os_code: error.raw_os_error(),
            path_depth: None,
        })
    }
    #[cfg(unix)]
    pub(crate) fn file(name: &str) -> &'static str {
        if name == "account.json" {
            "ACCOUNT_FILE"
        } else if name == "profile.lock" {
            "PROFILE_LOCK"
        } else if name.starts_with("room-") {
            "ROOM_FILE"
        } else if name.starts_with(".tmp-") {
            "TEMPORARY_FILE"
        } else {
            "UNKNOWN"
        }
    }
}
pub(crate) fn startup(stage: &'static str, error: crate::AgentError) -> serde_json::Value {
    let detail = match error {
        crate::AgentError::ProfileIo(detail) => detail,
        _ => ProfileIo {
            operation: "NONE",
            target: "UNKNOWN",
            os_code: None,
            path_depth: None,
        },
    };
    serde_json::json!({"stage": stage, "operation": detail.operation, "target": detail.target,
        "os_code": detail.os_code, "path_depth": detail.path_depth})
}
