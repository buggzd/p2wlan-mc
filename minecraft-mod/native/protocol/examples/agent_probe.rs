//! Process-lifecycle fault fixture. Not a network Agent and never packaged.
use std::time::Duration;

use p2wlan_mc_protocol::{FrameReader, FrameWriter, ProtocolError};
use serde_json::{json, Value};
use tokio::io::AsyncWriteExt;

async fn stall() {
    tokio::time::sleep(Duration::from_secs(30)).await;
}

async fn run() -> Result<(), ProtocolError> {
    // Distinct fixture processes have distinct owners; this is not a secret
    // or a production owner generator (the real Agent requires randomness).
    let owner = format!("{:032x}", std::process::id());
    let mode = std::env::args().nth(1).unwrap_or_else(|| "echo".into());
    if mode == "no_hello" {
        stall().await;
        return Ok(());
    }
    let mut reader = FrameReader::new(tokio::io::stdin());
    let mut writer = FrameWriter::new(tokio::io::stdout());
    writer.write(&json!({"version":if mode == "bad_version" {2} else {1}, "event":"hello", "owner":owner})).await?;
    if mode == "stderr" {
        let bytes = [b'x'; 8192];
        for _ in 0..32 {
            tokio::io::stderr()
                .write_all(&bytes)
                .await
                .map_err(|_| ProtocolError::Io)?;
        }
    }
    if mode == "no_read" {
        stall().await;
        return Ok(());
    }
    while let Some(request) = reader.next().await? {
        if request.as_object().is_none_or(|object| object.len() != 5)
            || request["version"].as_u64() != Some(1)
            || request["owner"] != owner
            || request["id"].as_u64().is_none_or(|id| id == 0)
            || request["op"].as_str().is_none()
            || !request["args"].is_object()
        {
            return Err(ProtocolError::InvalidJson);
        }
        if mode == "hold" {
            continue;
        }
        if mode == "exit" {
            return Ok(());
        }
        if mode == "partial" {
            tokio::io::stdout()
                .write_all(&[0, 0])
                .await
                .map_err(|_| ProtocolError::Io)?;
            stall().await;
            return Ok(());
        }
        if mode == "status" || mode == "event_owner" {
            for revision in [2, 1] {
                writer.write(&json!({"version":1,"owner":if mode == "event_owner" {"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"} else {owner.as_str()},
                    "event":"status","revision":revision,"data":{"value":revision}})).await?;
            }
        }
        let mut response = json!({"version":1,"owner":owner,"id":request["id"],"ok":true,"result":request["args"]});
        if mode == "bad_owner" {
            response["owner"] = json!("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        }
        if mode == "bad_id" {
            response["id"] = json!(request["id"]
                .as_u64()
                .and_then(|id| id.checked_add(1))
                .ok_or(ProtocolError::Capacity)?);
        }
        if mode == "fractional_id" {
            response["id"] = json!(1.5);
        }
        if mode == "remote" && request["op"] != "ping" {
            response.as_object_mut().map(|value| value.remove("result"));
            response["ok"] = Value::Bool(false);
            response["error"] = json!("AUTH_REQUIRED");
        }
        writer.write(&response).await?;
        if mode == "duplicate" {
            writer.write(&response).await?;
        }
    }
    Ok(())
}

#[tokio::main(flavor = "current_thread")]
async fn main() {
    if let Err(reason) = run().await {
        eprintln!("{reason}");
        std::process::exit(2);
    }
}
