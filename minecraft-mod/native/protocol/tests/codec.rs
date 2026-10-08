use std::time::Duration;

use p2wlan_mc_protocol::{
    encode_frame, parse_object, FrameReader, FrameWriter, ProtocolError, MAX_FRAME_BYTES,
};
use serde_json::{json, Value};
use tokio::io::{duplex, AsyncWriteExt};
use tokio::time::timeout;

fn frame(text: &str) -> Vec<u8> {
    let mut bytes = (text.len() as u32).to_be_bytes().to_vec();
    bytes.extend_from_slice(text.as_bytes());
    bytes
}

#[test]
fn strict_json_matches_java_limits_and_never_rounds_an_integer_id() {
    let value = parse_object(
        br#"{"id":18446744073709551615,"decimal":1.234567890123456789,"world":"\ud83c\udf32"}"#,
    )
    .unwrap();
    assert_eq!(value["id"].as_u64(), Some(u64::MAX));
    assert_eq!(value["decimal"].to_string(), "1.234567890123456789");
    assert_eq!(value["world"], "🌲");
    assert_eq!(parse_object(br#"{"s":"\\ud800"}"#).unwrap()["s"], "\\ud800");
    assert!(parse_object(br#"{"x":1e128,"y":1e-128}"#).is_ok());
    for (text, error) in [
        ("[]", ProtocolError::InvalidJson),
        ("{unquoted:1}", ProtocolError::InvalidJson),
        ("{/*comment*/}", ProtocolError::InvalidJson),
        ("{} {}", ProtocolError::InvalidJson),
        ("{\"id\":1,\"id\":2}", ProtocolError::InvalidJson),
        ("{\"id\":1,\"\\u0069d\":2}", ProtocolError::InvalidJson),
        ("{\"s\":\"\\ud800\"}", ProtocolError::InvalidUtf8),
        ("{\"\\ud800\":0}", ProtocolError::InvalidUtf8),
        ("{\"s\":\"\\udc00\"}", ProtocolError::InvalidUtf8),
        ("{\"x\":1e9999}", ProtocolError::Capacity),
        ("{\"x\":1e-129}", ProtocolError::Capacity),
        ("{\"x\":NaN}", ProtocolError::InvalidJson),
        ("{\"x\":01}", ProtocolError::InvalidJson),
    ] {
        assert_eq!(parse_object(text.as_bytes()), Err(error), "{text}");
    }
    assert_eq!(
        parse_object(&[b'{', 0xff, b'}']),
        Err(ProtocolError::InvalidUtf8)
    );
}

#[test]
fn depth_container_node_string_and_output_budgets_are_enforced() {
    let deep = format!("{{\"x\":{}0{}}}", "[".repeat(20), "]".repeat(20));
    assert_eq!(parse_object(deep.as_bytes()), Err(ProtocolError::Depth));
    let array = (0..129).map(|_| "0").collect::<Vec<_>>().join(",");
    assert_eq!(
        parse_object(format!("{{\"x\":[{array}]}}").as_bytes()),
        Err(ProtocolError::Capacity)
    );
    let many = Value::Object((0..128).map(|i| (format!("{i}"), json!([]))).collect());
    let mut nodes = many.clone();
    for value in nodes.as_object_mut().unwrap().values_mut() {
        *value = Value::Array(vec![json!(0); 128]);
    }
    assert_eq!(encode_frame(&nodes), Err(ProtocolError::Capacity));
    assert_eq!(
        parse_object(serde_json::to_string(&nodes).unwrap().as_bytes()),
        Err(ProtocolError::Capacity)
    );
    assert_eq!(
        encode_frame(&json!({"s":"a".repeat(32769)})),
        Err(ProtocolError::Capacity)
    );
    assert_eq!(
        encode_frame(&Value::Array(vec![])),
        Err(ProtocolError::InvalidJson)
    );
    assert_eq!(
        encode_frame(&json!({"a":"a".repeat(32000), "b":"b".repeat(32000), "c":"c".repeat(2000)})),
        Err(ProtocolError::Capacity)
    );
    assert_eq!(
        encode_frame(&json!({"s":"\n".repeat(32768)})),
        Err(ProtocolError::FrameSize)
    );
}

#[tokio::test]
async fn fragmented_utf8_frames_and_clean_eof_round_trip() {
    timeout(Duration::from_secs(5), async {
        let (mut source, input) = duplex(8);
        let object = json!({"id":9,"world":"你好\n🌲","null":null});
        let bytes = encode_frame(&object).unwrap();
        let task = tokio::spawn(async move {
            for byte in bytes {
                source.write_all(&[byte]).await.unwrap();
            }
            source.shutdown().await.unwrap();
        });
        let mut reader = FrameReader::new(input);
        assert_eq!(reader.next().await.unwrap(), Some(object));
        assert_eq!(reader.next().await.unwrap(), None);
        assert_eq!(reader.next().await, Err(ProtocolError::Poisoned));
        task.await.unwrap();
    })
    .await
    .unwrap();
}

#[tokio::test]
async fn cancelled_header_and_body_reads_preserve_exact_progress() {
    for prefix in [1, 2, 6, 15] {
        let (mut source, input) = duplex(1024);
        let object = json!({"id":123,"world":"forest"});
        let bytes = encode_frame(&object).unwrap();
        source.write_all(&bytes[..prefix]).await.unwrap();
        let mut reader = FrameReader::new(input);
        assert!(timeout(Duration::from_millis(10), reader.next())
            .await
            .is_err());
        source.write_all(&bytes[prefix..]).await.unwrap();
        assert_eq!(reader.next().await.unwrap(), Some(object));
    }
}

#[tokio::test(start_paused = true)]
async fn partial_frame_timeout_is_total_not_reset_by_more_bytes() {
    let (mut source, input) = duplex(1024);
    source.write_all(&[0]).await.unwrap();
    let mut reader = FrameReader::new(input);
    assert!(timeout(Duration::from_secs(1), reader.next())
        .await
        .is_err());
    source.write_all(&[0]).await.unwrap();
    assert_eq!(reader.next().await, Err(ProtocolError::Timeout));
    assert_eq!(reader.next().await, Err(ProtocolError::Poisoned));
}

#[tokio::test]
async fn invalid_sizes_partial_eof_and_invalid_json_poison_the_stream() {
    for (bytes, error) in [
        (vec![0, 0], ProtocolError::TruncatedFrame),
        (vec![0, 0, 0, 0], ProtocolError::FrameSize),
        (
            ((MAX_FRAME_BYTES + 1) as u32).to_be_bytes().to_vec(),
            ProtocolError::FrameSize,
        ),
        (vec![255; 4], ProtocolError::FrameSize),
        (vec![0, 0, 0, 3, b'{'], ProtocolError::TruncatedFrame),
        (vec![0, 0, 0, 1, 255], ProtocolError::InvalidUtf8),
        (frame("{\"id\":1,\"id\":2}"), ProtocolError::InvalidJson),
    ] {
        let mut reader = FrameReader::new(&bytes[..]);
        assert_eq!(reader.next().await, Err(error));
        assert_eq!(reader.next().await, Err(ProtocolError::Poisoned));
    }
}

#[tokio::test]
async fn completed_writes_allow_next_frame_but_cancelled_writes_are_terminal() {
    let (output, input) = duplex(1024);
    let mut writer = FrameWriter::new(output);
    let mut reader = FrameReader::new(input);
    for id in [1, 2] {
        let object = json!({"id":id});
        writer.write(&object).await.unwrap();
        assert_eq!(reader.next().await.unwrap(), Some(object));
    }
    let (output, _never_reads) = duplex(1);
    let mut writer = FrameWriter::new(output);
    assert!(
        timeout(Duration::from_millis(10), writer.write(&json!({"id":3})))
            .await
            .is_err()
    );
    assert_eq!(
        writer.write(&json!({"id":4})).await,
        Err(ProtocolError::Poisoned)
    );
}

#[tokio::test(start_paused = true)]
async fn blocked_writer_has_deadline_and_never_retries_partial_delivery() {
    let (output, _never_reads) = duplex(1);
    let mut writer = FrameWriter::new(output);
    assert_eq!(
        writer.write(&json!({"id":3})).await,
        Err(ProtocolError::Timeout)
    );
    assert_eq!(
        writer.write(&json!({"id":4})).await,
        Err(ProtocolError::Poisoned)
    );
}
