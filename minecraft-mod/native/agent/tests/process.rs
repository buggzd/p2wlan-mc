use p2wlan_mc_protocol::{FrameReader, FrameWriter};
use serde_json::{json, Value};
use std::process::Stdio;
use std::time::Duration;
use tokio::process::{Child, ChildStdin, ChildStdout, Command};
use tokio::time::timeout;

struct Peer {
    child: Child,
    input: Option<FrameWriter<ChildStdin>>,
    output: FrameReader<ChildStdout>,
    owner: String,
    revision: i64,
}
impl Peer {
    async fn launch() -> Self {
        let mut child = Command::new(env!("CARGO_BIN_EXE_p2wlan-mc-agent"))
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .kill_on_drop(true)
            .spawn()
            .unwrap();
        let input = Some(FrameWriter::new(child.stdin.take().unwrap()));
        let mut output = FrameReader::new(child.stdout.take().unwrap());
        let hello = timeout(Duration::from_secs(3), output.next())
            .await
            .unwrap()
            .unwrap()
            .unwrap();
        assert_eq!(hello["event"], "hello");
        assert_eq!(hello["version"], 1);
        let owner = hello["owner"].as_str().unwrap().to_string();
        assert_eq!(owner.len(), 32);
        assert!(owner
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase()));
        Self {
            child,
            input,
            output,
            owner,
            revision: 0,
        }
    }
    async fn send(&mut self, id: i64, op: &str, args: Value) {
        self.input
            .as_mut()
            .unwrap()
            .write(&json!({"version":1,"owner":self.owner,"id":id,"op":op,"args":args}))
            .await
            .unwrap();
    }
    async fn reply(&mut self, id: i64) -> Value {
        timeout(Duration::from_secs(3), async {
            loop {
                let reply = self.output.next().await.unwrap().unwrap();
                assert_eq!(reply["owner"], self.owner);
                if reply["event"] == "status" {
                    let revision = reply["revision"].as_i64().unwrap();
                    assert!(revision > self.revision);
                    self.revision = revision;
                } else {
                    assert_eq!(reply["id"], id);
                    return reply;
                }
            }
        })
        .await
        .unwrap()
    }
    async fn exit(&mut self, expected: i32) {
        assert_eq!(
            timeout(Duration::from_secs(4), self.child.wait())
                .await
                .unwrap()
                .unwrap()
                .code(),
            Some(expected)
        );
    }
}
#[tokio::test]
async fn actual_agent_owners_unordered_ids_errors_and_shutdown_are_fenced() {
    let mut a = Peer::launch().await;
    let mut b = Peer::launch().await;
    assert_ne!(a.owner, b.owner);
    for id in [3, 1, 2] {
        a.send(id, "ping", json!({})).await;
        let reply = a.reply(id).await;
        assert_eq!(reply["ok"], true);
        assert_eq!(reply["result"]["alive"], true);
    }
    a.send(4, "status", json!({})).await;
    assert_eq!(a.reply(4).await["result"]["phase"], "idle");
    a.send(
        5,
        "start",
        json!({"config":{"token":"fixture-private-value"}}),
    )
    .await;
    let reply = a.reply(5).await;
    assert_eq!(reply["error"], "INVALID_CONFIG");
    assert!(!reply.to_string().contains("fixture-private-value"));
    a.send(6, "not_implemented", json!({})).await;
    assert_eq!(a.reply(6).await["error"], "UNKNOWN_OPERATION");
    a.send(7, "stop", json!({"runtime":"foreign-owner"})).await;
    assert_eq!(a.reply(7).await["error"], "RUNTIME_NOT_READY");
    a.send(8, "shutdown", json!({"unexpected":true})).await;
    assert_eq!(a.reply(8).await["error"], "INVALID_ARGUMENTS");
    a.send(9, "ping", json!({})).await;
    assert_eq!(a.reply(9).await["ok"], true);
    a.send(
        11,
        "peer_status",
        json!({"runtime":"foreign-owner", "peer":"fixture-peer"}),
    )
    .await;
    assert_eq!(a.reply(11).await["error"], "RUNTIME_NOT_READY");
    a.send(12, "peer_status", json!({"runtime":"foreign-owner"}))
        .await;
    assert_eq!(a.reply(12).await["error"], "INVALID_ARGUMENTS");
    a.send(
        13,
        "peer_status",
        json!({"runtime":"foreign-owner", "peer":"a".repeat(257)}),
    )
    .await;
    assert_eq!(a.reply(13).await["error"], "INVALID_ARGUMENTS");
    for (id, service) in [
        (14, json!("a".repeat(64))),
        (15, json!("A".repeat(64))),
        (16, json!("0".repeat(63))),
    ] {
        a.send(id, "proxy", json!({"runtime":"foreign-owner", "peer":"fixture-peer", "world":"0".repeat(32), "port":1024, "service":service})).await;
        assert_eq!(
            a.reply(id).await["error"],
            if id == 14 {
                "RUNTIME_NOT_READY"
            } else {
                "INVALID_ARGUMENTS"
            }
        );
    }
    a.send(17, "proxy", json!({"runtime":"foreign-owner", "peer":"fixture-peer", "world":"0".repeat(32), "port":1024})).await;
    assert_eq!(a.reply(17).await["error"], "INVALID_ARGUMENTS");
    a.send(10, "shutdown", json!({})).await;
    assert_eq!(a.reply(10).await["result"]["stopped"], true);
    a.exit(0).await;
    b.send(1, "shutdown", json!({})).await;
    b.reply(1).await;
    b.exit(0).await;
}
#[tokio::test]
async fn duplicate_or_foreign_owner_input_is_terminal_and_never_replied_twice() {
    let mut peer = Peer::launch().await;
    peer.send(1, "ping", json!({})).await;
    peer.reply(1).await;
    peer.send(1, "ping", json!({})).await;
    peer.exit(2).await;
    let mut peer = Peer::launch().await;
    peer.input
        .as_mut()
        .unwrap()
        .write(&json!({"version":1,"owner":"a".repeat(32),"id":1,"op":"ping","args":{}}))
        .await
        .unwrap();
    peer.exit(2).await;
}
#[tokio::test]
async fn stdin_eof_and_idle_watchdog_terminate_the_exact_child() {
    let mut peer = Peer::launch().await;
    peer.send(1, "ping", json!({})).await;
    peer.reply(1).await;
    peer.input = None;
    peer.exit(0).await;
    let mut peer = Peer::launch().await;
    assert_eq!(
        timeout(Duration::from_secs(9), peer.child.wait())
            .await
            .unwrap()
            .unwrap()
            .code(),
        Some(2)
    );
}
