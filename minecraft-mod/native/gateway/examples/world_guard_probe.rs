//! Java world-guard wire fixture; never a packaged networking Agent.
use p2wlan_mc_gateway::LocalKey;
use p2wlan_mc_protocol::{FrameReader, FrameWriter};
use std::error::Error;
use std::net::{Ipv4Addr, SocketAddrV4};
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::time::timeout;

async fn run() -> Result<(), Box<dyn Error>> {
    let mut reader = FrameReader::new(tokio::io::stdin());
    let request = reader.next().await?.ok_or("fixture_input_missing")?;
    let object = request.as_object().ok_or("fixture_input_object_required")?;
    if object.len() != 2 {
        return Err("fixture_input_fields".into());
    }
    let port = request["port"]
        .as_u64()
        .filter(|port| (1..=65535).contains(port))
        .ok_or("fixture_input_port")? as u16;
    let encoded = request["key"].as_str().ok_or("fixture_input_key")?;
    let key: [u8; 32] = hex::decode(encoded)?
        .try_into()
        .map_err(|_| "fixture_key_size")?;
    let mut socket = TcpStream::connect(SocketAddrV4::new(Ipv4Addr::LOCALHOST, port)).await?;
    let prelude = LocalKey::from_bytes(key).prelude();
    socket.write_all(&prelude[..7]).await?;
    socket.write_all(&prelude[7..]).await?;
    let mut ready = [0; 1];
    socket.read_exact(&mut ready).await?;
    if ready != [1] {
        return Err("fixture_guard_not_ready".into());
    }
    let bytes: Vec<_> = (0..128_000).map(|n| (n % 251) as u8).collect();
    socket.write_all(&bytes).await?;
    socket.shutdown().await?;
    let mut echoed = Vec::new();
    socket.take(128_001).read_to_end(&mut echoed).await?;
    if bytes != echoed {
        return Err("fixture_echo_mismatch".into());
    }
    let mut writer = FrameWriter::new(tokio::io::stdout());
    writer
        .write(&serde_json::json!({"ok": true, "bytes": bytes.len()}))
        .await?;
    Ok(())
}
#[tokio::main(flavor = "current_thread")]
async fn main() {
    if !matches!(timeout(Duration::from_secs(8), run()).await, Ok(Ok(()))) {
        std::process::exit(2);
    }
}
