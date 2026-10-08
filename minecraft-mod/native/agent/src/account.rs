use crate::AgentError;
use reqwest::{Client, Method, Url};
use serde_json::{json, Value};
use std::time::Duration;

const BODY_LIMIT: usize = 1024 * 1024;
#[derive(Clone)]
pub struct AccountApi {
    client: Client,
}
impl AccountApi {
    pub fn new() -> Result<Self, AgentError> {
        let client = Client::builder()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .connect_timeout(Duration::from_secs(1))
            .timeout(Duration::from_millis(2500))
            .build()
            .map_err(|_| AgentError::ControlUnavailable)?;
        Ok(Self { client })
    }
    pub fn server(value: &str) -> Result<String, AgentError> {
        if value.len() > 2048 {
            return Err(AgentError::InvalidArguments);
        }
        let url = Url::parse(value).map_err(|_| AgentError::InvalidArguments)?;
        let local = matches!(url.host_str(), Some("127.0.0.1" | "[::1]" | "localhost"));
        if !(url.scheme() == "https" || url.scheme() == "http" && local)
            || !url.username().is_empty()
            || url.password().is_some()
            || url.query().is_some()
            || url.fragment().is_some()
            || url.host_str().is_none()
            || url.path() != "/"
        {
            return Err(AgentError::InvalidArguments);
        }
        Ok(url.as_str().trim_end_matches('/').to_string())
    }
    pub async fn request(
        &self,
        server: &str,
        token: Option<&str>,
        method: Method,
        suffix: &str,
        body: Option<Value>,
    ) -> Result<Value, AgentError> {
        let server = Self::server(server)?;
        let mut request = self
            .client
            .request(method, format!("{server}/api/v1/{suffix}"))
            .header(reqwest::header::ACCEPT, "application/json");
        if let Some(token) = token {
            request = request.bearer_auth(token);
        }
        if let Some(body) = body {
            request = request.json(&body);
        }
        let mut response = request.send().await.map_err(|error| {
            if error.is_timeout() {
                AgentError::ControlTimeout
            } else {
                AgentError::ControlUnavailable
            }
        })?;
        let status = response.status().as_u16();
        if !(200..300).contains(&status) {
            return Err(match status {
                401 => AgentError::AuthRequired,
                403 => AgentError::Forbidden,
                404 => AgentError::RoomUnknown,
                409 => AgentError::ControlConflict,
                429 => AgentError::ControlRateLimited,
                400..=499 => AgentError::InvalidArguments,
                _ => AgentError::ControlUnavailable,
            });
        }
        let mut bytes = Vec::new();
        while let Some(chunk) = response
            .chunk()
            .await
            .map_err(|_| AgentError::ControlResponse)?
        {
            if bytes.len() + chunk.len() > BODY_LIMIT {
                return Err(AgentError::ControlResponse);
            }
            bytes.extend_from_slice(&chunk);
        }
        serde_json::from_slice(&bytes).map_err(|_| AgentError::ControlResponse)
    }
    pub async fn authenticate(
        &self,
        server: &str,
        identifier: &str,
        password: &str,
        register: bool,
    ) -> Result<(String, String), AgentError> {
        let response = self
            .request(
                server,
                None,
                Method::POST,
                if register { "register" } else { "login" },
                Some(json!({"identifier":identifier,"email":identifier,"password":password})),
            )
            .await?;
        if response["success"] != true {
            return Err(AgentError::ControlResponse);
        }
        let user = response["user"]["id"]
            .as_str()
            .ok_or(AgentError::ControlResponse)?
            .to_string();
        let token = response["token"]
            .as_str()
            .ok_or(AgentError::ControlResponse)?
            .to_string();
        Ok((user, token))
    }
}
/// Explicit projection keeps server credentials/large membership arrays out of
/// public IPC. No Control roster is retained or independently evolved here.
pub fn room(value: &Value) -> Result<Value, AgentError> {
    let id = value["id"]
        .as_str()
        .filter(|id| {
            id.starts_with("room-")
                && id.len() <= 128
                && id
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
        })
        .ok_or(AgentError::ControlResponse)?;
    let code = value["room_code"]
        .as_str()
        .filter(|code| code.len() == 8 && code.bytes().all(|byte| byte.is_ascii_digit()))
        .ok_or(AgentError::ControlResponse)?;
    let name = value["name"]
        .as_str()
        .filter(|name| name.len() <= 256)
        .ok_or(AgentError::ControlResponse)?;
    let cidr = value["cidr"]
        .as_str()
        .filter(|cidr| cidr.len() <= 18)
        .ok_or(AgentError::ControlResponse)?;
    let (address, prefix) = cidr.split_once('/').ok_or(AgentError::ControlResponse)?;
    address
        .parse::<std::net::Ipv4Addr>()
        .map_err(|_| AgentError::ControlResponse)?;
    prefix
        .parse::<u8>()
        .ok()
        .filter(|value| *value <= 32)
        .ok_or(AgentError::ControlResponse)?;
    let role = match value.get("role") {
        None | Some(Value::Null) => Value::Null,
        Some(Value::String(value)) if value.len() <= 64 => Value::String(value.clone()),
        _ => return Err(AgentError::ControlResponse),
    };
    Ok(json!({"id":id,"code":code,"name":name,"cidr":cidr,"role":role}))
}
