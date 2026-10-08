use crate::local::LocalPrelude;
use crate::registry::{Flow, PendingLocal, Registration, RegistrationKind};
use crate::{
    ConnectionBridge, GatewayError, HostedService, JoinProxy, LocalKey, LocalTarget,
    RegistrationId, TcpFrame, MAX_REGISTRATIONS,
};
use p2wlan_mc_bridge::{ConnectionId, TcpStack, MAX_CONNECTIONS, PACKET_CAPACITY};
use p2wlan_mc_runtime_port::{
    AdmissionError, FencedPacket, FencedPacketEndpoint, PeerGrant, ScopeOwner, ScopeToken,
};
use std::collections::{HashMap, VecDeque};
use std::net::{Ipv4Addr, SocketAddrV4};
use std::sync::atomic::{AtomicU32, Ordering};
use std::task::{Context, Poll, Waker};
use std::time::{Duration, Instant};
use tokio::net::{TcpListener, TcpStream};
use tokio::task::JoinSet;

static NEXT_SERVICE_PORT: AtomicU32 = AtomicU32::new(1024);
pub const PACKET_WAIT_TIMEOUT: Duration = Duration::from_millis(250);

type ConnectResult = (ConnectionId, PeerGrant, Result<TcpStream, GatewayError>);
struct QueuedFrame {
    packet: FencedPacket,
    since: Instant,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum GatewayFailure {
    Admission(AdmissionError),
    Capacity,
    WrongTarget,
    InvalidPacket,
    LocalKeyMismatch,
    LocalTimeout,
    PacketDeadline,
    ConnectionClosed,
    TaskFailed,
    Io,
    ReceiveAdmissionMissing,
    ReceiveAdmissionPoisoned,
    WouldBlock,
    EntropyUnavailable,
    IdentityExhausted,
    StaleRegistration,
}
impl GatewayFailure {
    pub fn code(self) -> &'static str {
        match self {
            Self::Admission(reason) => reason.code(),
            Self::Capacity => "GATEWAY_CAPACITY",
            Self::WrongTarget => "GATEWAY_WRONG_TARGET",
            Self::InvalidPacket => "GATEWAY_INVALID_PACKET",
            Self::LocalKeyMismatch => "GATEWAY_LOCAL_KEY_MISMATCH",
            Self::LocalTimeout => "GATEWAY_LOCAL_TIMEOUT",
            Self::PacketDeadline => "GATEWAY_PACKET_DEADLINE",
            Self::ConnectionClosed => "GATEWAY_CONNECTION_CLOSED",
            Self::TaskFailed => "GATEWAY_TASK_FAILED",
            Self::Io => "GATEWAY_IO",
            Self::ReceiveAdmissionMissing => "GATEWAY_RECEIVE_ADMISSION_MISSING",
            Self::ReceiveAdmissionPoisoned => "GATEWAY_RECEIVE_ADMISSION_POISONED",
            Self::WouldBlock => "GATEWAY_WOULD_BLOCK",
            Self::EntropyUnavailable => "GATEWAY_ENTROPY_UNAVAILABLE",
            Self::IdentityExhausted => "GATEWAY_IDENTITY_EXHAUSTED",
            Self::StaleRegistration => "GATEWAY_STALE_REGISTRATION",
        }
    }
    fn from_error(error: &GatewayError) -> Self {
        match error {
            GatewayError::Admission(reason) => Self::Admission(*reason),
            GatewayError::Capacity => Self::Capacity,
            GatewayError::WrongTarget => Self::WrongTarget,
            GatewayError::InvalidPacket
            | GatewayError::BadChecksum
            | GatewayError::UnsupportedPacket => Self::InvalidPacket,
            GatewayError::LocalKeyMismatch => Self::LocalKeyMismatch,
            GatewayError::LocalTimeout => Self::LocalTimeout,
            GatewayError::Closed | GatewayError::IdleTimeout => Self::ConnectionClosed,
            GatewayError::Io(_) => Self::Io,
            GatewayError::Stack(p2wlan_mc_bridge::StackError::Capacity)
            | GatewayError::Packet(p2wlan_mc_bridge::PacketError::QueueFull)
            | GatewayError::Packet(p2wlan_mc_bridge::PacketError::TooLarge) => Self::Capacity,
            GatewayError::Stack(
                p2wlan_mc_bridge::StackError::InvalidEndpoint
                | p2wlan_mc_bridge::StackError::StaleConnection,
            ) => Self::WrongTarget,
            GatewayError::Stack(p2wlan_mc_bridge::StackError::Closed) => Self::ConnectionClosed,
            GatewayError::Stack(p2wlan_mc_bridge::StackError::WouldBlock)
            | GatewayError::WouldBlock => Self::WouldBlock,
            GatewayError::Stack(p2wlan_mc_bridge::StackError::IdentityExhausted)
            | GatewayError::IdentityExhausted => Self::IdentityExhausted,
            GatewayError::Packet(p2wlan_mc_bridge::PacketError::Invalid) => Self::InvalidPacket,
            GatewayError::MissingAdmission => Self::ReceiveAdmissionMissing,
            GatewayError::Poisoned => Self::ReceiveAdmissionPoisoned,
            GatewayError::Entropy => Self::EntropyUnavailable,
            GatewayError::StaleRegistration => Self::StaleRegistration,
            GatewayError::TaskFailed => Self::TaskFailed,
        }
    }
}
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct GatewayStatus {
    pub closed: bool,
    pub registrations: usize,
    pub connections: usize,
    pub tcp_sockets: usize,
    pub pending_connects: usize,
    pub local_handshakes: usize,
    pub queued_frames: usize,
    pub failures: u64,
    pub unsupported_local_feedback: u64,
    pub last_failure: Option<GatewayFailure>,
}

/// Sole owner of application registrations, TCP sockets and flow routing.
/// No peer/path/session roster is maintained here: every flow retains a grant
/// from the original runtime, and every delayed connect returns that exact grant.
/// All raw stack output is sealed before any flow identity can leave the map.
pub struct TcpGateway {
    endpoint: FencedPacketEndpoint,
    stack: TcpStack,
    lifetime: ScopeToken,
    lifetime_owner: Option<ScopeOwner>,
    closed: bool,
    generation: u64,
    started: Instant,
    registrations: HashMap<RegistrationId, Registration>,
    flows: HashMap<ConnectionId, Flow>,
    local: Vec<PendingLocal>,
    connects: JoinSet<ConnectResult>,
    outgoing: VecDeque<QueuedFrame>,
    incoming: Option<QueuedFrame>,
    failures: u64,
    last_failure: Option<GatewayFailure>,
}
impl TcpGateway {
    pub fn new(endpoint: FencedPacketEndpoint, seed: u64) -> Result<Self, GatewayError> {
        let owner = ScopeOwner::new();
        let mut gateway = Self::with_lifetime(endpoint, seed, owner.token())?;
        gateway.lifetime_owner = Some(owner);
        Ok(gateway)
    }
    /// Borrow one non-cloneable supervisor lifetime owner. Its revocation is
    /// retained through final upstream UDP/Relay sends without a fifth scope.
    pub fn with_lifetime(
        endpoint: FencedPacketEndpoint,
        seed: u64,
        lifetime: ScopeToken,
    ) -> Result<Self, GatewayError> {
        if !lifetime.is_live() {
            return Err(AdmissionError::ScopeClosed.into());
        }
        if !endpoint.authority().is_running() {
            return Err(AdmissionError::RuntimeClosed.into());
        }
        let generation = endpoint.authority().network_generation();
        let stack = TcpStack::with_transmit_mtu(
            endpoint.authority().address(),
            seed,
            endpoint
                .authority()
                .conservative_packet_budget()
                .min(p2wlan_mc_bridge::PACKET_MTU),
        )?;
        Ok(Self {
            endpoint,
            stack,
            lifetime,
            lifetime_owner: None,
            closed: false,
            generation,
            started: Instant::now(),
            registrations: HashMap::new(),
            flows: HashMap::new(),
            local: Vec::new(),
            connects: JoinSet::new(),
            outgoing: VecDeque::new(),
            incoming: None,
            failures: 0,
            last_failure: None,
        })
    }
    pub fn contains_registration(&self, id: RegistrationId) -> bool {
        !self.closed && self.lifetime.is_live() && self.registrations.contains_key(&id)
    }
    pub fn local_peer_id(&self) -> Result<&str, GatewayError> {
        self.check_current()?;
        Ok(self.endpoint.authority().local_peer_id())
    }
    pub fn status(&self) -> GatewayStatus {
        GatewayStatus {
            closed: self.closed || !self.lifetime.is_live(),
            registrations: self.registrations.len(),
            connections: self.flows.len(),
            tcp_sockets: self.stack.connection_count(),
            pending_connects: self.connects.len(),
            local_handshakes: self.local.len(),
            queued_frames: self.outgoing.len(),
            failures: self.failures,
            unsupported_local_feedback: self
                .endpoint
                .drops_by_reason(AdmissionError::UnsupportedLocalFeedback),
            last_failure: self.last_failure,
        }
    }
    fn check_current(&self) -> Result<(), GatewayError> {
        if self.closed || !self.lifetime.is_live() || !self.endpoint.authority().is_running() {
            return Err(AdmissionError::RuntimeClosed.into());
        }
        if self.generation != self.endpoint.authority().network_generation() {
            return Err(AdmissionError::NetworkChanged.into());
        }
        Ok(())
    }
    pub fn register_host(&mut self, target: LocalTarget) -> Result<HostedService, GatewayError> {
        self.check_current()?;
        if self.registrations.len() >= MAX_REGISTRATIONS {
            return Err(GatewayError::Capacity);
        }
        // Never reuse a world/service port within this process, including across
        // replacement gateways. Exhaustion is explicit, not wraparound.
        let port = NEXT_SERVICE_PORT
            .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |value| {
                (value < 49152).then_some(value + 1)
            })
            .map_err(|_| GatewayError::IdentityExhausted)? as u16;
        let id = RegistrationId::allocate()?;
        let listener = self.stack.listen(port)?;
        self.registrations.insert(
            id,
            Registration {
                lifetime: ScopeOwner::new(),
                kind: RegistrationKind::Host {
                    target,
                    port,
                    listener: Some(listener),
                },
            },
        );
        Ok(HostedService {
            id,
            virtual_address: SocketAddrV4::new(self.stack.address(), port),
        })
    }
    pub async fn register_proxy(
        &mut self,
        peer_id: &str,
        port: u16,
    ) -> Result<JoinProxy, GatewayError> {
        self.check_current()?;
        if self.registrations.len() >= MAX_REGISTRATIONS {
            return Err(GatewayError::Capacity);
        }
        if port == 0 {
            return Err(GatewayError::WrongTarget);
        }
        let id = RegistrationId::allocate()?;
        let lifetime = ScopeOwner::new();
        let grant = self
            .endpoint
            .authority()
            .peer_grant(peer_id, self.lifetime.clone())
            .await?
            .with_scope(lifetime.token())?;
        self.check_current()?;
        grant.validate()?;
        let key = LocalKey::generate()?;
        let socket =
            std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).map_err(GatewayError::Io)?;
        socket.set_nonblocking(true).map_err(GatewayError::Io)?;
        let address = socket.local_addr().map_err(GatewayError::Io)?;
        let std::net::SocketAddr::V4(address) = address else {
            return Err(GatewayError::WrongTarget);
        };
        let listener = TcpListener::from_std(socket).map_err(GatewayError::Io)?;
        let remote = SocketAddrV4::new(grant.address(), port);
        self.registrations.insert(
            id,
            Registration {
                lifetime,
                kind: RegistrationKind::Proxy {
                    listener,
                    key: key.clone(),
                    remote,
                    grant: Box::new(grant),
                },
            },
        );
        Ok(JoinProxy {
            id,
            local_address: address,
            key,
        })
    }
    pub fn stop(&mut self, id: RegistrationId) -> Result<(), GatewayError> {
        if !self.registrations.contains_key(&id) {
            return Err(GatewayError::StaleRegistration);
        }
        self.seal_output(Instant::now())?;
        let mut registration = self
            .registrations
            .remove(&id)
            .ok_or(GatewayError::StaleRegistration)?;
        registration.lifetime.revoke();
        self.local.retain(|pending| pending.registration != id);
        let ids: Vec<_> = self
            .flows
            .iter()
            .filter_map(|(flow_id, flow)| (flow.registration == id).then_some(*flow_id))
            .collect();
        for flow_id in ids {
            self.remove_flow(flow_id)?;
        }
        if let RegistrationKind::Host {
            listener: Some(listener),
            ..
        } = registration.kind
        {
            self.stack.remove(listener)?;
        }
        // Sealed backlog still contains the old scopes and can only fail its
        // original final send check; stopping cannot re-tag it to a new service.
        self.flush_output(Instant::now());
        Ok(())
    }
    pub fn shutdown(&mut self) {
        self.closed = true;
        self.lifetime_owner = None;
        self.connects = JoinSet::new();
        self.registrations.clear();
        self.flows.clear();
        self.local.clear();
        self.outgoing.clear();
        self.incoming = None;
        self.stack.clear();
    }
    fn failure(&mut self, reason: GatewayFailure) {
        self.failures = self.failures.saturating_add(1);
        self.last_failure = Some(reason);
    }
    fn remove_flow(&mut self, id: ConnectionId) -> Result<(), GatewayError> {
        // All callers have sealed generated output first. No smoltcp polling is
        // performed between removing the grant and removing its socket.
        if let Some(flow) = self.flows.remove(&id) {
            if let Some(task) = flow.connect {
                task.abort();
            }
            self.stack.remove(id)?;
        }
        Ok(())
    }
    fn seal_output(&mut self, now: Instant) -> Result<(), GatewayError> {
        while let Some(bytes) = self.stack.take_packet() {
            let frame = match TcpFrame::parse(&bytes) {
                Ok(frame) => frame,
                Err(error) => {
                    self.failure(GatewayFailure::from_error(&error));
                    continue;
                }
            };
            let grant = self
                .flows
                .values()
                .find(|flow| flow.local == frame.source && flow.remote == frame.destination)
                .map(|flow| flow.bridge.grant());
            let Some(grant) = grant else {
                self.failure(GatewayFailure::WrongTarget);
                continue;
            };
            let packet = match self.endpoint.make_packet(grant, bytes) {
                Ok(packet) => packet,
                Err(error) => {
                    self.failure(GatewayFailure::Admission(error));
                    continue;
                }
            };
            if self.outgoing.len() >= PACKET_CAPACITY {
                return Err(GatewayError::Capacity);
            }
            self.outgoing.push_back(QueuedFrame { packet, since: now });
        }
        Ok(())
    }
    fn flush_output(&mut self, now: Instant) {
        while let Some(queued) = self.outgoing.pop_front() {
            if now.saturating_duration_since(queued.since) >= PACKET_WAIT_TIMEOUT {
                self.failure(GatewayFailure::PacketDeadline);
                continue;
            }
            match self.endpoint.try_send(queued.packet) {
                Ok(()) => {}
                Err((AdmissionError::QueueFull | AdmissionError::Contended, packet)) => {
                    self.outgoing.push_front(QueuedFrame {
                        packet,
                        since: queued.since,
                    });
                    break;
                }
                Err((reason, _)) => self.failure(GatewayFailure::Admission(reason)),
            }
        }
    }
    /// Call regularly from the bounded native supervisor. No network await or
    /// user callback runs here; each call handles at most 128 inbound packets,
    /// 32 local accepts/handshakes, 32 flows, and 32 connect completions.
    pub fn tick(&mut self) -> Result<GatewayStatus, GatewayError> {
        if let Err(error) = self.check_current() {
            self.shutdown();
            return Err(error);
        }
        let now = Instant::now();
        self.reap_connects()?;
        self.flush_output(now);
        if !self.outgoing.is_empty() {
            return Ok(self.status());
        }
        self.accept_local(now)?;
        self.admit_local(now)?;
        for _ in 0..PACKET_CAPACITY {
            let queued = match self.incoming.take() {
                Some(queued) => queued,
                None => match self.endpoint.try_recv() {
                    Ok(Some(packet)) => QueuedFrame { packet, since: now },
                    Ok(None) => break,
                    Err(AdmissionError::Contended) => break,
                    Err(
                        error @ (AdmissionError::PortClosed
                        | AdmissionError::RuntimeClosed
                        | AdmissionError::NetworkChanged),
                    ) => {
                        self.shutdown();
                        return Err(error.into());
                    }
                    Err(error) => {
                        self.failure(GatewayFailure::Admission(error));
                        break;
                    }
                },
            };
            if now.saturating_duration_since(queued.since) >= PACKET_WAIT_TIMEOUT {
                self.failure(GatewayFailure::PacketDeadline);
                continue;
            }
            let result = self.receive(&queued.packet, now);
            self.seal_output(now)?;
            self.flush_output(now);
            match result {
                Ok(()) => {}
                Err(GatewayError::Admission(AdmissionError::Contended)) => {
                    self.incoming = Some(queued);
                    break;
                }
                Err(error) => self.failure(GatewayFailure::from_error(&error)),
            }
            if !self.outgoing.is_empty() {
                break;
            }
        }
        if self.outgoing.is_empty() {
            let ids: Vec<_> = self.flows.keys().copied().collect();
            for id in ids {
                let result = self
                    .flows
                    .get_mut(&id)
                    .ok_or(GatewayError::WrongTarget)?
                    .bridge
                    .step(&mut self.stack);
                self.stack.poll(now.saturating_duration_since(self.started));
                self.seal_output(now)?;
                match result {
                    Ok(progress) if progress.complete => self.remove_flow(id)?,
                    Ok(_) | Err(GatewayError::Admission(AdmissionError::Contended)) => {}
                    Err(error) => {
                        self.failure(GatewayFailure::from_error(&error));
                        self.remove_flow(id)?;
                    }
                }
                self.flush_output(now);
                if !self.outgoing.is_empty() {
                    break;
                }
            }
            self.rearm_hosts()?;
        }
        Ok(self.status())
    }
    fn accept_local(&mut self, now: Instant) -> Result<(), GatewayError> {
        let mut context = Context::from_waker(Waker::noop());
        let mut failed = Vec::new();
        let mut revoked = Vec::new();
        for (&id, registration) in &self.registrations {
            let RegistrationKind::Proxy {
                listener, grant, ..
            } = &registration.kind
            else {
                continue;
            };
            if let Err(error) = grant.validate() {
                revoked.push((id, error));
                continue;
            }
            for _ in 0..MAX_CONNECTIONS {
                if self.local.len() + self.flows.len() >= MAX_CONNECTIONS {
                    break;
                }
                match listener.poll_accept(&mut context) {
                    Poll::Ready(Ok((socket, _))) => self.local.push(PendingLocal {
                        registration: id,
                        socket,
                        prelude: LocalPrelude::new(now),
                    }),
                    Poll::Ready(Err(_)) => {
                        failed.push(GatewayFailure::Io);
                        break;
                    }
                    Poll::Pending => break,
                }
            }
        }
        for reason in failed {
            self.failure(reason);
        }
        for (id, error) in revoked {
            self.stop(id)?;
            self.failure(GatewayFailure::Admission(error));
        }
        Ok(())
    }
    fn admit_local(&mut self, now: Instant) -> Result<(), GatewayError> {
        let mut pending = std::mem::take(&mut self.local);
        for mut local in pending.drain(..) {
            let result = self
                .registrations
                .get(&local.registration)
                .ok_or(GatewayError::StaleRegistration)
                .and_then(|registration| match &registration.kind {
                    RegistrationKind::Proxy {
                        key, grant, remote, ..
                    } => {
                        grant.validate()?;
                        local
                            .prelude
                            .poll(&local.socket, key, now)
                            .map(|ready| (ready, grant.as_ref().clone(), *remote))
                    }
                    _ => Err(GatewayError::WrongTarget),
                });
            match result {
                Ok((false, _, _)) => self.local.push(local),
                Ok((true, grant, remote)) => {
                    let result =
                        self.connect_local(local.registration, remote, grant, local.socket);
                    if let Err(error) = result {
                        self.failure(GatewayFailure::from_error(&error));
                    }
                }
                Err(error) => self.failure(GatewayFailure::from_error(&error)),
            }
        }
        Ok(())
    }
    fn connect_local(
        &mut self,
        registration: RegistrationId,
        remote: SocketAddrV4,
        grant: PeerGrant,
        socket: TcpStream,
    ) -> Result<(), GatewayError> {
        let id = self.stack.connect(remote)?;
        let result = (|| {
            let mut bridge = ConnectionBridge::new(&mut self.stack, id, grant)?;
            bridge.attach_local(socket, &bridge.grant().clone())?;
            let local = self.stack.local(id)?.ok_or(GatewayError::WrongTarget)?;
            self.flows.insert(
                id,
                Flow {
                    registration,
                    local,
                    remote,
                    bridge,
                    connect: None,
                },
            );
            Ok(())
        })();
        if result.is_err() {
            self.stack.remove(id)?;
        }
        result
    }
    fn receive(&mut self, packet: &FencedPacket, now: Instant) -> Result<(), GatewayError> {
        let frame = TcpFrame::parse(packet.checked_bytes()?)?;
        let id = self.flows.iter().find_map(|(id, flow)| {
            (flow.local == frame.destination && flow.remote == frame.source).then_some(*id)
        });
        let id = match id {
            Some(id) => id,
            None => self.open_inbound(packet, frame)?,
        };
        let result = self
            .flows
            .get_mut(&id)
            .ok_or(GatewayError::WrongTarget)?
            .bridge
            .receive_packet(
                &mut self.stack,
                packet,
                now.saturating_duration_since(self.started),
            );
        if let Err(error) = result {
            if !matches!(error, GatewayError::Admission(AdmissionError::Contended)) {
                self.seal_output(now)?;
                self.remove_flow(id)?;
            }
            return Err(error);
        }
        if self.stack.remote(id)? != Some(frame.source) {
            self.seal_output(now)?;
            self.remove_flow(id)?;
            return Err(GatewayError::InvalidPacket);
        }
        let flow = self.flows.get_mut(&id).ok_or(GatewayError::WrongTarget)?;
        if !flow.bridge.has_local() && flow.connect.is_none() {
            if self.connects.len() >= MAX_CONNECTIONS {
                return Err(GatewayError::Capacity);
            }
            let registration = self
                .registrations
                .get(&flow.registration)
                .ok_or(GatewayError::StaleRegistration)?;
            let RegistrationKind::Host { target, .. } = &registration.kind else {
                return Err(GatewayError::WrongTarget);
            };
            let target = target.clone();
            let grant = flow.bridge.grant().clone();
            flow.connect = Some(
                self.connects
                    .spawn(async move { (id, grant.clone(), target.connect(grant).await) }),
            );
        }
        Ok(())
    }
    fn open_inbound(
        &mut self,
        packet: &FencedPacket,
        frame: TcpFrame,
    ) -> Result<ConnectionId, GatewayError> {
        if !frame.syn
            || frame.acknowledgement.is_some()
            || frame.fin
            || frame.reset
            || frame.payload_bytes != 0
        {
            return Err(GatewayError::InvalidPacket);
        }
        let registration_id = self
            .registrations
            .iter()
            .find_map(|(&id, registration)| match &registration.kind {
                RegistrationKind::Host {
                    port,
                    listener: Some(_),
                    ..
                } if frame.destination == SocketAddrV4::new(self.stack.address(), *port) => {
                    Some(id)
                }
                _ => None,
            })
            .ok_or(GatewayError::WrongTarget)?;
        let registration = self
            .registrations
            .get_mut(&registration_id)
            .ok_or(GatewayError::StaleRegistration)?;
        let grant = packet
            .permit()
            .grant()
            .with_scope(self.lifetime.clone())?
            .with_scope(registration.lifetime.token())?;
        let RegistrationKind::Host { listener, .. } = &mut registration.kind else {
            return Err(GatewayError::WrongTarget);
        };
        let id = listener.ok_or(GatewayError::Capacity)?;
        let bridge = ConnectionBridge::new(&mut self.stack, id, grant)?;
        *listener = None;
        self.flows.insert(
            id,
            Flow {
                registration: registration_id,
                local: frame.destination,
                remote: frame.source,
                bridge,
                connect: None,
            },
        );
        Ok(id)
    }
    fn reap_connects(&mut self) -> Result<(), GatewayError> {
        for _ in 0..MAX_CONNECTIONS {
            let Some(result) = self.connects.try_join_next() else {
                break;
            };
            let (id, grant, result) = match result {
                Ok(result) => result,
                Err(error) if error.is_cancelled() => continue,
                Err(_) => {
                    self.failure(GatewayFailure::TaskFailed);
                    self.shutdown();
                    return Err(GatewayError::TaskFailed);
                }
            };
            let Some(flow) = self.flows.get_mut(&id) else {
                continue;
            };
            flow.connect = None;
            let result = result.and_then(|socket| flow.bridge.attach_local(socket, &grant));
            if let Err(error) = result {
                self.seal_output(Instant::now())?;
                self.failure(GatewayFailure::from_error(&error));
                self.remove_flow(id)?;
            }
        }
        Ok(())
    }
    fn rearm_hosts(&mut self) -> Result<(), GatewayError> {
        for registration in self.registrations.values_mut() {
            if let RegistrationKind::Host { port, listener, .. } = &mut registration.kind {
                if listener.is_none() && self.stack.connection_count() < MAX_CONNECTIONS {
                    *listener = Some(self.stack.listen(*port)?);
                }
            }
        }
        Ok(())
    }
}
impl Drop for TcpGateway {
    fn drop(&mut self) {
        self.shutdown();
    }
}
