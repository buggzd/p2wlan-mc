//! Native networking Agent. No game/loader dependency or alternate path owner.
#![forbid(unsafe_code)]
mod account;
mod private;
mod profile;
mod request;
mod runtime;
use account::AccountApi;
use p2wlan_mc_gateway::{GatewayError, LocalKey, LocalTarget, RegistrationId};
use p2wlan_mc_protocol::{FrameReader, FrameWriter};
use p2wlan_mc_runtime_port::{Config, ScopeOwner, ScopeToken};
use profile::Profile;
use request::{Request, RequestLedger};
use reqwest::Method;
use runtime::ManagedRuntime;
use serde_json::{json, Map, Value};
use std::collections::HashMap;
use std::net::{Ipv4Addr, SocketAddrV4};
use std::path::Path;
use std::time::{Duration, Instant};
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::sync::mpsc;

#[derive(Clone)]
pub struct Identity(String);
impl Identity {
    pub fn generate() -> Result<Self, AgentError> {
        let mut bytes = [0; 16];
        getrandom::getrandom(&mut bytes).map_err(|_| AgentError::Entropy)?;
        Ok(Self(hex::encode(bytes)))
    }
    pub fn as_str(&self) -> &str {
        &self.0
    }
}
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum AgentError {
    Entropy,
    Protocol,
    InvalidRequest,
    DuplicateRequest,
    RequestHistoryCapacity,
    QueueFull,
    InvalidArguments,
    InvalidConfig,
    UnknownOperation,
    RuntimeBusy,
    RuntimeNotReady,
    RuntimeChanged,
    RuntimeExited,
    RuntimeFailed,
    StartupTimeout,
    StopTimeout,
    HeartbeatTimeout,
    Admission(p2wlan_mc_runtime_port::AdmissionError),
    IdentityExhausted,
    RegistrationUnknown,
    WorldChanged,
    GatewayFailed,
    ProfileRequired,
    ProfileUnsupported,
    ProfileIo,
    ProfileInvalid,
    ProfileBusy,
    ProfileCapacity,
    AuthRequired,
    Forbidden,
    RoomUnknown,
    ControlConflict,
    ControlRateLimited,
    ControlTimeout,
    ControlResponse,
    ControlUnavailable,
}
impl AgentError {
    pub fn code(self) -> &'static str {
        match self {
            Self::Entropy => "ENTROPY_UNAVAILABLE",
            Self::Protocol => "IPC_PROTOCOL",
            Self::InvalidRequest => "INVALID_REQUEST",
            Self::DuplicateRequest => "DUPLICATE_REQUEST",
            Self::RequestHistoryCapacity => "REQUEST_HISTORY_CAPACITY",
            Self::QueueFull => "IPC_QUEUE_FULL",
            Self::InvalidArguments => "INVALID_ARGUMENTS",
            Self::InvalidConfig => "INVALID_CONFIG",
            Self::UnknownOperation => "UNKNOWN_OPERATION",
            Self::RuntimeBusy => "RUNTIME_BUSY",
            Self::RuntimeNotReady => "RUNTIME_NOT_READY",
            Self::RuntimeChanged => "RUNTIME_CHANGED",
            Self::RuntimeExited => "RUNTIME_EXITED",
            Self::RuntimeFailed => "RUNTIME_FAILED",
            Self::StartupTimeout => "STARTUP_TIMEOUT",
            Self::StopTimeout => "STOP_TIMEOUT",
            Self::HeartbeatTimeout => "HEARTBEAT_TIMEOUT",
            Self::Admission(reason) => reason.code(),
            Self::IdentityExhausted => "IDENTITY_EXHAUSTED",
            Self::RegistrationUnknown => "REGISTRATION_UNKNOWN",
            Self::WorldChanged => "WORLD_CHANGED",
            Self::GatewayFailed => "GATEWAY_FAILED",
            Self::ProfileRequired => "PROFILE_REQUIRED",
            Self::ProfileUnsupported => "PROFILE_UNSUPPORTED_PLATFORM",
            Self::ProfileIo => "PROFILE_IO",
            Self::ProfileInvalid => "PROFILE_INVALID",
            Self::ProfileBusy => "PROFILE_BUSY",
            Self::ProfileCapacity => "PROFILE_CAPACITY",
            Self::AuthRequired => "AUTH_REQUIRED",
            Self::Forbidden => "FORBIDDEN",
            Self::RoomUnknown => "ROOM_UNKNOWN",
            Self::ControlConflict => "CONTROL_CONFLICT",
            Self::ControlRateLimited => "CONTROL_RATE_LIMITED",
            Self::ControlTimeout => "CONTROL_TIMEOUT",
            Self::ControlResponse => "CONTROL_RESPONSE",
            Self::ControlUnavailable => "CONTROL_UNAVAILABLE",
        }
    }
}
struct Binding {
    id: RegistrationId,
    world: String,
}
struct State {
    parent: ScopeToken,
    profile: Option<Profile>,
    api: AccountApi,
    runtime: Option<ManagedRuntime>,
    bindings: HashMap<String, Binding>,
    last_failure: Option<AgentError>,
}
fn fields(args: &Map<String, Value>, expected: &[&str]) -> Result<(), AgentError> {
    if args.len() == expected.len() && expected.iter().all(|key| args.contains_key(*key)) {
        Ok(())
    } else {
        Err(AgentError::InvalidArguments)
    }
}
fn text<'a>(args: &'a Map<String, Value>, key: &str) -> Result<&'a str, AgentError> {
    args.get(key)
        .and_then(Value::as_str)
        .filter(|text| !text.is_empty() && text.len() <= 256)
        .ok_or(AgentError::InvalidArguments)
}
fn optional_text<'a>(args: &'a Map<String, Value>, key: &str) -> Result<&'a str, AgentError> {
    args.get(key)
        .and_then(Value::as_str)
        .filter(|text| text.len() <= 256)
        .ok_or(AgentError::InvalidArguments)
}
fn port(args: &Map<String, Value>) -> Result<u16, AgentError> {
    args.get("port")
        .and_then(Value::as_u64)
        .filter(|value| (1..=65535).contains(value))
        .map(|value| value as u16)
        .ok_or(AgentError::InvalidArguments)
}
impl State {
    fn gateway_error(error: GatewayError) -> AgentError {
        match error {
            GatewayError::Admission(reason) => AgentError::Admission(reason),
            _ => AgentError::GatewayFailed,
        }
    }
    fn current(&mut self, args: &Map<String, Value>) -> Result<&mut ManagedRuntime, AgentError> {
        if !self.parent.is_live() {
            return Err(AgentError::Protocol);
        }
        let expected = text(args, "runtime")?;
        let runtime = self.runtime.as_mut().ok_or(AgentError::RuntimeNotReady)?;
        if runtime.id.as_str() != expected {
            return Err(AgentError::RuntimeChanged);
        }
        Ok(runtime)
    }
    fn snapshot(&self) -> Value {
        let mut snapshot = match &self.runtime {
            None => json!({"phase":"idle", "failure": self.last_failure.map(AgentError::code)}),
            Some(runtime) => {
                let status = runtime.gateway.as_ref().map(|gateway| gateway.status());
                json!({"phase": if status.is_some() {"ready"} else {"starting"}, "runtime":runtime.id.as_str(),
                    "virtual_ip": runtime.address, "peer_id":runtime.gateway.as_ref().and_then(|gateway|gateway.local_peer_id().ok()), "registrations": status.map(|value| value.registrations).unwrap_or(0),
                    "connections": status.map(|value| value.connections).unwrap_or(0),
                    "gateway_failures": status.map(|value| value.failures.min(i64::MAX as u64)).unwrap_or(0),
                    "unsupported_local_feedback":status.map(|value|value.unsupported_local_feedback.min(i64::MAX as u64)).unwrap_or(0),
                    "gateway_failure":status.and_then(|value|value.last_failure).map(|value|value.code()),
                    "failure":self.last_failure.map(AgentError::code)})
            }
        };
        snapshot["account"] = self
            .profile
            .as_ref()
            .map(Profile::status)
            .unwrap_or(json!({"available":false}));
        snapshot
    }
    async fn tick(&mut self) {
        let failure = self
            .runtime
            .as_mut()
            .and_then(|runtime| runtime.tick().err());
        if let Some(error) = failure {
            self.last_failure = Some(error);
            let _ = self.stop().await;
        }
    }
    async fn stop(&mut self) -> Result<(), AgentError> {
        self.bindings.clear();
        if let Some(mut runtime) = self.runtime.take() {
            if let Err(error) = runtime.stop().await {
                self.last_failure = Some(error);
                return Err(error);
            }
        }
        Ok(())
    }
    async fn account_command(&mut self, request: &Request) -> Result<Value, AgentError> {
        let args = &request.arguments;
        let profile = self.profile.as_mut().ok_or(AgentError::ProfileRequired)?;
        match request.operation.as_str() {
            "login" | "register" => {
                fields(args, &["server", "identifier", "password"])?;
                let server = AccountApi::server(text(args, "server")?)?;
                let (user, token) = self
                    .api
                    .authenticate(
                        &server,
                        text(args, "identifier")?,
                        text(args, "password")?,
                        request.operation == "register",
                    )
                    .await?;
                if !self.parent.is_live() {
                    return Err(AgentError::Protocol);
                }
                profile.login(server, user, token)?;
                Ok(profile.status())
            }
            "logout" => {
                fields(args, &[])?;
                profile.logout()?;
                Ok(profile.status())
            }
            "rooms" => {
                fields(args, &[])?;
                let (server, token, user) = profile.credentials()?;
                let response = self
                    .api
                    .request(server, Some(token), Method::GET, "rooms", None)
                    .await?;
                if response["user_id"] != user || response["room_protocol_version"] != 1 {
                    return Err(AgentError::ControlResponse);
                }
                let values = response["rooms"]
                    .as_array()
                    .ok_or(AgentError::ControlResponse)?;
                if values.len() > 64 {
                    return Err(AgentError::ControlResponse);
                }
                Ok(json!({"rooms":values.iter().map(account::room).collect::<Result<Vec<_>,_>>()?}))
            }
            "create_room" | "join_room" => {
                let create = request.operation == "create_room";
                fields(
                    args,
                    if create {
                        &["name", "password"]
                    } else {
                        &["code", "password"]
                    },
                )?;
                let (server, token, _) = profile.credentials()?;
                let body = if create {
                    json!({"name":text(args,"name")?,"password":optional_text(args,"password")?})
                } else {
                    json!({"room_code":text(args,"code")?,"password":optional_text(args,"password")?})
                };
                let response = self
                    .api
                    .request(
                        server,
                        Some(token),
                        Method::POST,
                        if create { "rooms" } else { "rooms/join" },
                        Some(body),
                    )
                    .await?;
                Ok(json!({"room":account::room(&response["room"])?}))
            }
            "leave_room" => {
                fields(args, &["room"])?;
                let id = text(args, "room")?;
                if !id.starts_with("room-")
                    || !id
                        .bytes()
                        .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
                {
                    return Err(AgentError::InvalidArguments);
                }
                let (server, token, _) = profile.credentials()?;
                self.api
                    .request(
                        server,
                        Some(token),
                        Method::POST,
                        &format!("rooms/{id}/leave"),
                        None,
                    )
                    .await?;
                Ok(json!({"left":true}))
            }
            "connect_room" => {
                fields(args, &["room"])?;
                let selected = text(args, "room")?;
                let (server, token, user) = profile.credentials()?;
                let response = self
                    .api
                    .request(server, Some(token), Method::GET, "rooms", None)
                    .await?;
                if response["user_id"] != user || response["room_protocol_version"] != 1 {
                    return Err(AgentError::ControlResponse);
                }
                let rooms = response["rooms"]
                    .as_array()
                    .filter(|rooms| rooms.len() <= 64)
                    .ok_or(AgentError::ControlResponse)?;
                let found = rooms
                    .iter()
                    .find(|room| room["id"] == selected || room["room_code"] == selected)
                    .ok_or(AgentError::RoomUnknown)?;
                let room = account::room(found)?;
                if !self.parent.is_live() {
                    return Err(AgentError::Protocol);
                }
                let config = profile.room_config(
                    room["id"].as_str().ok_or(AgentError::ControlResponse)?,
                    room["cidr"].as_str().ok_or(AgentError::ControlResponse)?,
                )?;
                let (server, token, _) = profile.credentials()?;
                self.api.request(server,Some(token),Method::POST,&format!("rooms/{}/device-access",room["id"].as_str().ok_or(AgentError::ControlResponse)?),
                    Some(json!({"public_key":config.node.public_key,"device_name":config.node.device_name,"platform":config.node.platform,"resume":true}))).await?;
                if !self.parent.is_live() {
                    return Err(AgentError::Protocol);
                }
                let runtime = ManagedRuntime::start(config, self.parent.clone())?;
                let result = json!({"runtime":runtime.id.as_str(),"phase":"starting","room":room});
                self.runtime = Some(runtime);
                self.last_failure = None;
                Ok(result)
            }
            _ => Err(AgentError::UnknownOperation),
        }
    }
    async fn execute(&mut self, request: &Request) -> Result<Value, AgentError> {
        if !self.parent.is_live() {
            return Err(AgentError::Protocol);
        }
        self.bindings.retain(|_, binding| {
            self.runtime
                .as_ref()
                .and_then(|runtime| runtime.gateway.as_ref())
                .is_some_and(|gateway| gateway.contains_registration(binding.id))
        });
        let args = &request.arguments;
        if matches!(
            request.operation.as_str(),
            "login"
                | "register"
                | "logout"
                | "rooms"
                | "create_room"
                | "join_room"
                | "leave_room"
                | "connect_room"
        ) {
            if self.runtime.is_some() {
                return Err(AgentError::RuntimeBusy);
            }
            return tokio::time::timeout(Duration::from_secs(3), self.account_command(request))
                .await
                .map_err(|_| AgentError::ControlTimeout)?;
        }
        match request.operation.as_str() {
            "ping" => {
                fields(args, &[])?;
                Ok(json!({"alive":true}))
            }
            "status" => {
                fields(args, &[])?;
                Ok(self.snapshot())
            }
            "start" => {
                fields(args, &["config"])?;
                if self.profile.is_some() {
                    return Err(AgentError::InvalidArguments);
                }
                if self.runtime.is_some() {
                    return Err(AgentError::RuntimeBusy);
                }
                let config: Config = serde_json::from_value(args["config"].clone())
                    .map_err(|_| AgentError::InvalidConfig)?;
                let runtime = ManagedRuntime::start(config, self.parent.clone())?;
                let result = json!({"runtime":runtime.id.as_str(), "phase":"starting"});
                self.runtime = Some(runtime);
                self.last_failure = None;
                Ok(result)
            }
            "stop" => {
                fields(args, &["runtime"])?;
                self.current(args)?;
                self.stop().await?;
                Ok(json!({"stopped":true}))
            }
            "host" => {
                fields(args, &["runtime", "world", "port", "key"])?;
                let world = text(args, "world")?.to_string();
                if world.len() != 32
                    || !world
                        .bytes()
                        .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
                {
                    return Err(AgentError::InvalidArguments);
                }
                let key: [u8; 32] = hex::decode(text(args, "key")?)
                    .map_err(|_| AgentError::InvalidArguments)?
                    .try_into()
                    .map_err(|_| AgentError::InvalidArguments)?;
                let target = LocalTarget::new(
                    SocketAddrV4::new(Ipv4Addr::LOCALHOST, port(args)?),
                    LocalKey::from_bytes(key),
                )
                .map_err(|_| AgentError::InvalidArguments)?;
                let runtime = self.current(args)?;
                let gateway = runtime
                    .gateway
                    .as_mut()
                    .ok_or(AgentError::RuntimeNotReady)?;
                let service = gateway.register_host(target).map_err(Self::gateway_error)?;
                let id = service.id.opaque();
                let result = json!({"registration":id, "world":world, "virtual_ip":service.virtual_address.ip().to_string(), "port":service.virtual_address.port()});
                self.bindings.insert(
                    id,
                    Binding {
                        id: service.id,
                        world,
                    },
                );
                Ok(result)
            }
            "proxy" => {
                fields(args, &["runtime", "peer", "port", "world"])?;
                let peer = text(args, "peer")?.to_string();
                let world = text(args, "world")?.to_string();
                let remote_port = port(args)?;
                let runtime = self.current(args)?;
                let gateway = runtime
                    .gateway
                    .as_mut()
                    .ok_or(AgentError::RuntimeNotReady)?;
                let proxy = gateway
                    .register_proxy(&peer, remote_port)
                    .await
                    .map_err(Self::gateway_error)?;
                if !self.parent.is_live() {
                    let _ = self.stop().await;
                    return Err(AgentError::Protocol);
                }
                let id = proxy.id.opaque();
                let result = json!({"registration":id, "world":world, "port":proxy.local_address.port(), "key":hex::encode(&proxy.key.prelude()[8..])});
                self.bindings.insert(
                    id,
                    Binding {
                        id: proxy.id,
                        world,
                    },
                );
                Ok(result)
            }
            "close_registration" => {
                fields(args, &["runtime", "registration", "world"])?;
                self.current(args)?;
                let id = text(args, "registration")?;
                let binding = self
                    .bindings
                    .get(id)
                    .ok_or(AgentError::RegistrationUnknown)?;
                if binding.world != text(args, "world")? {
                    return Err(AgentError::WorldChanged);
                }
                let registration_id = binding.id;
                self.current(args)?
                    .gateway
                    .as_mut()
                    .ok_or(AgentError::RuntimeNotReady)?
                    .stop(registration_id)
                    .map_err(Self::gateway_error)?;
                self.bindings.remove(id);
                Ok(json!({"closed":true}))
            }
            "shutdown" => {
                fields(args, &[])?;
                self.stop().await?;
                Ok(json!({"stopped":true}))
            }
            _ => Err(AgentError::UnknownOperation),
        }
    }
}

/// One bounded stdin producer and one state/stdout owner. Native I/O errors are
/// terminal; mutating commands are never replayed after uncertain delivery.
pub async fn run<R, W>(input: R, output: W) -> Result<(), AgentError>
where
    R: AsyncRead + Unpin + Send + 'static,
    W: AsyncWrite + Unpin,
{
    run_with_profile(input, output, None).await
}
pub async fn run_with_profile<R, W>(
    input: R,
    output: W,
    directory: Option<&Path>,
) -> Result<(), AgentError>
where
    R: AsyncRead + Unpin + Send + 'static,
    W: AsyncWrite + Unpin,
{
    let profile = directory.map(Profile::open).transpose()?;
    let api = AccountApi::new()?;
    let owner = Identity::generate()?;
    let mut writer = FrameWriter::new(output);
    writer
        .write(&json!({"version":1, "event":"hello", "owner":owner.as_str()}))
        .await
        .map_err(|_| AgentError::Protocol)?;
    let (send, mut receive) = mpsc::channel(32);
    let parser_owner = owner.clone();
    let parent_owner = ScopeOwner::new();
    let parent = parent_owner.token();
    let (parent_changed, mut parent_events) = tokio::sync::watch::channel(true);
    struct InputLifetime {
        owner: ScopeOwner,
        changed: tokio::sync::watch::Sender<bool>,
    }
    impl Drop for InputLifetime {
        fn drop(&mut self) {
            self.owner.revoke();
            self.changed.send_replace(false);
        }
    }
    let input_lifetime = InputLifetime {
        owner: parent_owner,
        changed: parent_changed,
    };
    let mut reader = tokio::spawn(async move {
        let _lifetime = input_lifetime;
        let mut frames = FrameReader::new(input);
        let mut ledger = RequestLedger::default();
        loop {
            let value = match frames.next().await {
                Ok(Some(value)) => value,
                Ok(None) => return Ok(()),
                Err(_) => return Err(AgentError::Protocol),
            };
            let request = Request::parse(value, &parser_owner)?;
            ledger.admit(request.id)?;
            tokio::time::timeout(Duration::from_millis(250), send.send(request))
                .await
                .map_err(|_| AgentError::QueueFull)?
                .map_err(|_| AgentError::Protocol)?;
        }
    });
    let mut state = State {
        parent,
        profile,
        api,
        runtime: None,
        bindings: HashMap::new(),
        last_failure: None,
    };
    let mut ticks = tokio::time::interval(Duration::from_millis(10));
    ticks.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut last_input = Instant::now();
    let mut last_snapshot = Value::Null;
    let mut revision = 0i64;
    let result = loop {
        tokio::select! {
            biased;
            _ = parent_events.changed() => {
                break match (&mut reader).await { Ok(result) => result, Err(_) => Err(AgentError::Protocol) };
            }
            _ = ticks.tick() => {
                if last_input.elapsed() >= Duration::from_secs(6) { break Err(AgentError::HeartbeatTimeout); }
                state.tick().await;
                let snapshot = state.snapshot();
                if snapshot != last_snapshot {
                    let Some(next) = revision.checked_add(1) else { break Err(AgentError::IdentityExhausted); };
                    revision = next;
                    if writer.write(&json!({"version":1,"event":"status","owner":owner.as_str(),"revision":revision,"data":snapshot})).await.is_err() { break Err(AgentError::Protocol); }
                    last_snapshot = snapshot;
                }
            }
            request = receive.recv() => {
                let Some(request) = request else {
                    break match (&mut reader).await { Ok(result) => result, Err(_) => Err(AgentError::Protocol) };
                };
                last_input = Instant::now();
                let result = state.execute(&request).await;
                let terminal_failure = result.as_ref().err().copied();
                let terminate = request.operation == "shutdown" && request.arguments.is_empty();
                let reply = match result {
                    Ok(result) => json!({"version":1, "owner":owner.as_str(), "id":request.id, "ok":true, "result":result}),
                    Err(error) => json!({"version":1, "owner":owner.as_str(), "id":request.id, "ok":false, "error":error.code()}),
                };
                if writer.write(&reply).await.is_err() { break Err(AgentError::Protocol); }
                if terminate { break terminal_failure.map_or(Ok(()), Err); }
            }
        }
    };
    let cleanup = state.stop().await;
    // Do not wait on an idle OS stdin read. The executable performs a bounded
    // runtime shutdown; a dropped producer cannot retain any application grant.
    reader.abort();
    result.and(cleanup)
}

#[cfg(all(test, unix))]
mod profile_tests;
