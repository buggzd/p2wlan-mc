use super::AccountApi;
use crate::AgentError;
use reqwest::Method;
use serde_json::json;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

#[test]
fn public_http_and_https_origins_are_supported_and_canonical() {
    for (input, expected) in [
        (
            "http://control.example.com:18080/",
            "http://control.example.com:18080",
        ),
        (
            "HTTP://CONTROL.EXAMPLE.COM:80",
            "http://control.example.com",
        ),
        (
            "h\tt\nt\rp://control.example.com",
            "http://control.example.com",
        ),
        (
            " \tHTTP://control.example.com\r\n",
            "http://control.example.com",
        ),
        ("http://203.0.113.1:18080", "http://203.0.113.1:18080"),
        (
            "https://control.example.com:443/",
            "https://control.example.com",
        ),
    ] {
        assert_eq!(AccountApi::server(input).as_deref(), Ok(expected));
    }
}
#[test]
fn unsafe_origin_components_remain_rejected_for_both_transports() {
    for scheme in ["http", "https"] {
        for rest in [
            "user:password@example.com",
            "example.com/api",
            "example.com?token=x",
            "example.com#x",
            "example.com:99999",
        ] {
            assert_eq!(
                AccountApi::server(&format!("{scheme}://{rest}")),
                Err(AgentError::InvalidArguments)
            );
        }
    }
    for input in [
        "ftp://example.com",
        "ws://example.com",
        "file:///account.json",
        "http:",
        "",
    ] {
        assert_eq!(AccountApi::server(input), Err(AgentError::InvalidArguments));
    }
    assert_eq!(
        AccountApi::server(&format!("http://{}.example.com", "a".repeat(2048))),
        Err(AgentError::InvalidArguments)
    );
}
async fn headers(socket: &mut TcpStream) -> Vec<u8> {
    let mut result = Vec::new();
    tokio::time::timeout(Duration::from_secs(2), async {
        while !result.ends_with(b"\r\n\r\n") {
            assert!(result.len() < 4096);
            result.push(socket.read_u8().await.unwrap());
        }
    })
    .await
    .unwrap();
    result
}
#[tokio::test]
async fn http_rest_request_preserves_bounded_json_contract() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let origin = format!("http://{}", listener.local_addr().unwrap());
    let server = tokio::spawn(async move {
        let (mut socket, _) = tokio::time::timeout(Duration::from_secs(2), listener.accept())
            .await
            .unwrap()
            .unwrap();
        let request = headers(&mut socket).await;
        assert!(request.starts_with(b"GET /api/v1/profile HTTP/1.1\r\n"));
        let body = br#"{"user_id":"fixture-user"}"#;
        let response = format!("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", body.len());
        tokio::time::timeout(Duration::from_secs(2), async {
            socket.write_all(response.as_bytes()).await.unwrap();
            socket.write_all(body).await.unwrap();
        })
        .await
        .unwrap();
    });
    let response = AccountApi::new()
        .unwrap()
        .request(&origin, None, Method::GET, "profile", None)
        .await
        .unwrap();
    assert_eq!(response, json!({"user_id":"fixture-user"}));
    tokio::time::timeout(Duration::from_secs(3), server)
        .await
        .unwrap()
        .unwrap();
}
#[tokio::test]
async fn http_does_not_redirect_credentials_to_another_request() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let origin = format!("http://{}", listener.local_addr().unwrap());
    let location = format!("{origin}/api/v1/profile");
    let server = tokio::spawn(async move {
        let (mut socket, _) = tokio::time::timeout(Duration::from_secs(2), listener.accept())
            .await
            .unwrap()
            .unwrap();
        let request = headers(&mut socket).await;
        assert!(request
            .windows(b"fixture-token".len())
            .any(|value| value == b"fixture-token"));
        let response = format!("HTTP/1.1 302 Found\r\nLocation: {location}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        tokio::time::timeout(
            Duration::from_secs(2),
            socket.write_all(response.as_bytes()),
        )
        .await
        .unwrap()
        .unwrap();
        drop(socket);
        assert!(
            tokio::time::timeout(Duration::from_millis(200), listener.accept())
                .await
                .is_err()
        );
    });
    assert_eq!(
        AccountApi::new()
            .unwrap()
            .request(&origin, Some("fixture-token"), Method::GET, "profile", None)
            .await,
        Err(AgentError::ControlUnavailable)
    );
    tokio::time::timeout(Duration::from_secs(3), server)
        .await
        .unwrap()
        .unwrap();
}
