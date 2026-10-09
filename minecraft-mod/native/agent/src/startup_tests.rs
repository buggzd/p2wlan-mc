use crate::{run_with_profile, AgentError, Identity};
use p2wlan_mc_protocol::FrameReader;
use std::path::PathBuf;
use std::time::Duration;

struct InvalidProfile(PathBuf);
impl InvalidProfile {
    fn create() -> Self {
        let path = std::env::temp_dir().join(format!(
            "p2wlan-startup-{}",
            Identity::generate().unwrap().as_str()
        ));
        let mut file = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&path)
            .unwrap();
        std::io::Write::write_all(&mut file, b"fixture-private-value").unwrap();
        Self(path)
    }
}
impl Drop for InvalidProfile {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

#[tokio::test]
async fn initialization_failure_emits_only_closed_reason_without_hello_or_owner() {
    let profile = InvalidProfile::create();
    let (output, input) = tokio::io::duplex(1024);
    let result = run_with_profile(tokio::io::empty(), output, Some(&profile.0)).await;
    assert_eq!(result, Err(AgentError::ProfileInvalid));
    let mut frames = FrameReader::new(input);
    let message = frames.next().await.unwrap().unwrap();
    assert_eq!(
        message,
        serde_json::json!({"version":1, "event":"startup_error", "error":"PROFILE_INVALID"})
    );
    assert!(!message.to_string().contains("fixture-private-value"));
    assert!(!message.to_string().contains(profile.0.to_str().unwrap()));
    assert!(frames.next().await.unwrap().is_none());
}

#[tokio::test]
async fn startup_failure_write_is_bounded_and_never_retried_or_reported_ready() {
    let profile = InvalidProfile::create();
    let (output, _unread) = tokio::io::duplex(1);
    let result = tokio::time::timeout(
        Duration::from_secs(3),
        run_with_profile(tokio::io::empty(), output, Some(&profile.0)),
    )
    .await
    .unwrap();
    assert_eq!(result, Err(AgentError::ProfileInvalid));
}
