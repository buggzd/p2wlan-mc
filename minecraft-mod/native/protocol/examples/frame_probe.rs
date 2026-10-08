use p2wlan_mc_protocol::{FrameReader, FrameWriter, ProtocolError};

async fn run() -> Result<(), ProtocolError> {
    let mut reader = FrameReader::new(tokio::io::stdin());
    let mut writer = FrameWriter::new(tokio::io::stdout());
    while let Some(frame) = reader.next().await? {
        writer.write(&frame).await?;
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
