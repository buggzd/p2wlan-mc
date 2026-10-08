use crate::discovery::{Discovery, DiscoveryFrame, Message};
use crate::local::LocalPrelude;
use crate::registry::{Flow, PendingLocal, Registration, RegistrationKind};
use crate::service::ServiceHandshake;
use crate::{
    ConnectionBridge, DiscoveredService, DiscoveryTicket, GatewayError, HostedService, JoinProxy,
    LocalKey, LocalTarget, RegistrationId, ServiceIdentity, TcpFrame, MAX_REGISTRATIONS,
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
const MAX_FAILURE_REASONS: usize = 128;
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
    InvalidServiceIdentity,
    ServiceIdentityMismatch,
    ServiceTimeout,
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
    DiscoveryBusy,
    DiscoveryTimeout,
    StaleDiscovery,
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
            Self::InvalidServiceIdentity => "GATEWAY_INVALID_SERVICE_IDENTITY",
            Self::ServiceIdentityMismatch => "GATEWAY_SERVICE_IDENTITY_MISMATCH",
            Self::ServiceTimeout => "GATEWAY_SERVICE_TIMEOUT",
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
            Self::DiscoveryBusy => "GATEWAY_DISCOVERY_BUSY",
            Self::DiscoveryTimeout => "GATEWAY_DISCOVERY_TIMEOUT",
            Self::StaleDiscovery => "GATEWAY_STALE_DISCOVERY",
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
            GatewayError::InvalidServiceIdentity => Self::InvalidServiceIdentity,
            GatewayError::ServiceIdentityMismatch => Self::ServiceIdentityMismatch,
            GatewayError::ServiceTimeout => Self::ServiceTimeout,
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
            GatewayError::DiscoveryBusy => Self::DiscoveryBusy,
            GatewayError::DiscoveryTimeout => Self::DiscoveryTimeout,
            GatewayError::StaleDiscovery => Self::StaleDiscovery,
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
    pub ready_targets: usize,
    pub local_handshakes: usize,
    pub queued_frames: usize,
    pub discovery_queries: usize,
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
    discovery: Discovery,
    failures: u64,
    last_failure: Option<GatewayFailure>,
    failure_counts: Vec<(GatewayFailure, u64)>,
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
            discovery: Discovery::default(),
            failures: 0,
            last_failure: None,
            failure_counts: Vec::new(),
        })
    }
    pub fn contains_registration(&self, id: RegistrationId) -> bool {
        !self.closed && self.lifetime.is_live() && self.registrations.contains_key(&id)
    }
    pub fn local_peer_id(&self) -> Result<&str, GatewayError> {
        self.check_current()?;
        Ok(self.endpoint.authority().local_peer_id())
    }
    pub fn authorized_peers(
        &self,
    ) -> Result<p2wlan_mc_runtime_port::AuthorizedPeers, GatewayError> {
        self.check_current()?;
        let view = self.endpoint.authority().authorized_peers(&self.lifetime)?;
        self.check_current()?;
        Ok(view)
    }
    pub async fn peer_view(
        &self,
        peer_id: &str,
    ) -> Result<p2wlan_mc_runtime_port::PeerView, GatewayError> {
        self.check_current()?;
        let grant = self
            .endpoint
            .authority()
            .peer_grant(peer_id, self.lifetime.clone())
            .await?;
        self.check_current()?;
        Ok(grant.peer_view()?)
    }
    /// Request-time projection of the packet port's existing counter owner.
    /// At most one entry per fixed admission reason; no roster/cache is kept.
    pub fn admission_drops(&self) -> Vec<(&'static str, u64)> {
        AdmissionError::ALL
            .into_iter()
            .filter_map(|reason| {
                let count = self.endpoint.drops_by_reason(reason);
                (count > 0).then_some((reason.code(), count))
            })
            .collect()
    }
    pub fn status(&self) -> GatewayStatus {
        GatewayStatus {
            closed: self.closed || !self.lifetime.is_live(),
            registrations: self.registrations.len(),
            connections: self.flows.len(),
            tcp_sockets: self.stack.connection_count(),
            pending_connects: self.connects.len(),
            ready_targets: self
                .flows
                .values()
                .filter(|flow| flow.ready_target.is_some())
                .count(),
            local_handshakes: self.local.len(),
            queued_frames: self.outgoing.len(),
            discovery_queries: usize::from(self.discovery.active()),
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
        let identity = ServiceIdentity::generate()?;
        let listener = self.stack.listen(port)?;
        self.registrations.insert(
            id,
            Registration {
                lifetime: ScopeOwner::new(),
                kind: RegistrationKind::Host {
                    target,
                    identity,
                    port,
                    listener: Some(listener),
                },
            },
        );
        Ok(HostedService {
            id,
            virtual_address: SocketAddrV4::new(self.stack.address(), port),
            identity,
        })
    }
    pub async fn register_proxy(
        &mut self,
        peer_id: &str,
        port: u16,
        identity: ServiceIdentity,
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
                    identity,
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
    /// One bounded one-shot query; existing TCP flows continue to be ticked.
    pub async fn discover(&mut self, peer_id: &str) -> Result<DiscoveryTicket, GatewayError> {
        self.check_current()?;
        self.discovery.expire(Instant::now());
        if self.discovery.active() {
            return Err(GatewayError::DiscoveryBusy);
        }
        let grant = self
            .endpoint
            .authority()
            .peer_grant(peer_id, self.lifetime.clone())
            .await?;
        self.check_current()?;
        if self.outgoing.len() >= PACKET_CAPACITY {
            return Err(GatewayError::Capacity);
        }
        let now = Instant::now();
        let (ticket, grant) = self.discovery.start(grant, now)?;
        let result = Message::Request(ticket)
            .packet(self.stack.address(), grant.address())
            .and_then(|bytes| {
                self.endpoint
                    .make_packet(&grant, bytes)
                    .map_err(GatewayError::from)
            });
        match result {
            Ok(packet) => {
                if let Err(error) = self.discovery.remember_request(ticket, &packet) {
                    let _ = self.discovery.cancel(ticket);
                    return Err(error);
                }
                self.outgoing.push_back(QueuedFrame { packet, since: now });
                Ok(ticket)
            }
            Err(error) => {
                let _ = self.discovery.cancel(ticket);
                Err(error)
            }
        }
    }
    pub fn poll_discovery(
        &mut self,
        ticket: DiscoveryTicket,
    ) -> Result<Option<Vec<DiscoveredService>>, GatewayError> {
        self.check_current()?;
        self.discovery.poll(ticket, Instant::now())
    }
    pub fn cancel_discovery(&mut self, ticket: DiscoveryTicket) -> Result<(), GatewayError> {
        self.discovery.cancel(ticket)
    }
    fn receive_discovery(
        &mut self,
        packet: &FencedPacket,
        frame: DiscoveryFrame,
        now: Instant,
    ) -> Result<(), GatewayError> {
        if frame.destination != self.stack.address()
            || frame.source != packet.permit().grant().address()
        {
            return Err(GatewayError::WrongTarget);
        }
        match frame.message {
            Message::Response(ticket, services) => {
                self.discovery.receive(ticket, services, packet, now)
            }
            Message::Request(ticket) => {
                let grant = packet.permit().grant().with_scope(self.lifetime.clone())?;
                // Read-only registration snapshot, not live availability or an
                // authorization grant. Final joining verifies each identity.
                let mut services: Vec<_> = self
                    .registrations
                    .values()
                    .filter_map(|registration| match &registration.kind {
                        RegistrationKind::Host { port, identity, .. }
                            if registration.lifetime.token().is_live() =>
                        {
                            Some(DiscoveredService {
                                port: *port,
                                identity: *identity,
                            })
                        }
                        _ => None,
                    })
                    .collect();
                services.sort_by_key(|service| service.port);
                if self.outgoing.len() >= PACKET_CAPACITY {
                    return Err(GatewayError::Capacity);
                }
                packet.validate()?;
                let bytes = Message::Response(ticket, services)
                    .packet(self.stack.address(), frame.source)?;
                let response = self.endpoint.make_packet(&grant, bytes)?;
                self.outgoing.push_back(QueuedFrame {
                    packet: response,
                    since: now,
                });
                Ok(())
            }
        }
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
        self.discovery = Discovery::default();
        self.stack.clear();
    }
    pub fn failure_counts(&self) -> Vec<(&'static str, u64)> {
        self.failure_counts
            .iter()
            .map(|(reason, count)| (reason.code(), *count))
            .collect()
    }
    fn failure(&mut self, reason: GatewayFailure) {
        self.failures = self.failures.saturating_add(1);
        self.last_failure = Some(reason);
        if let Some((_, count)) = self
            .failure_counts
            .iter_mut()
            .find(|(key, _)| *key == reason)
        {
            *count = count.saturating_add(1);
        } else if self.failure_counts.len() < MAX_FAILURE_REASONS {
            // Typed, closed reason set (including the fixed admission enum),
            // not externally supplied strings; even future growth is bounded.
            self.failure_counts.push((reason, 1));
        } else {
            self.shutdown();
            self.last_failure = Some(GatewayFailure::Capacity);
        }
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
        if self.discovery.expire(now) {
            self.failure(GatewayFailure::DiscoveryTimeout);
        }
        self.reap_connects()?;
        if self.outgoing.len() < PACKET_CAPACITY {
            match self.discovery.retry(now) {
                Ok(Some(packet)) => self.outgoing.push_back(QueuedFrame { packet, since: now }),
                Ok(None) | Err(GatewayError::Admission(AdmissionError::Contended)) => {}
                Err(error) => self.failure(GatewayFailure::from_error(&error)),
            }
        }
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
                let result = self.drive_flow(id, now);
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
                        key,
                        grant,
                        remote,
                        identity,
                        ..
                    } => {
                        grant.validate()?;
                        local
                            .prelude
                            .poll(&local.socket, key, now)
                            .map(|ready| (ready, grant.as_ref().clone(), *remote, *identity))
                    }
                    _ => Err(GatewayError::WrongTarget),
                });
            match result {
                Ok((false, _, _, _)) => self.local.push(local),
                Ok((true, grant, remote, identity)) => {
                    let result = self.connect_local(
                        local.registration,
                        remote,
                        identity,
                        grant,
                        local.socket,
                    );
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
        identity: ServiceIdentity,
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
                    service: ServiceHandshake::proxy(identity, Instant::now()),
                    connect: None,
                    ready_target: None,
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
        if let Some(frame) = DiscoveryFrame::parse(packet.checked_bytes()?)? {
            return self.receive_discovery(packet, frame, now);
        }
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
        let RegistrationKind::Host {
            listener, identity, ..
        } = &mut registration.kind
        else {
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
                service: ServiceHandshake::host(*identity, Instant::now()),
                connect: None,
                ready_target: None,
            },
        );
        Ok(id)
    }
    fn drive_flow(
        &mut self,
        id: ConnectionId,
        now: Instant,
    ) -> Result<crate::BridgeProgress, GatewayError> {
        let flow = self.flows.get_mut(&id).ok_or(GatewayError::WrongTarget)?;
        if flow.service.poll(&mut flow.bridge, &mut self.stack, now)? {
            return flow.bridge.step(&mut self.stack);
        }
        if !flow.bridge.has_local() && flow.connect.is_none() && flow.ready_target.is_none() {
            if let Some(admission) = flow.service.target_admission() {
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
                admission.validate()?;
                let target = target.clone();
                let grant = flow.bridge.grant().clone();
                flow.connect = Some(self.connects.spawn(async move {
                    (id, grant.clone(), target.connect(grant, admission).await)
                }));
            }
        }
        Ok(crate::BridgeProgress {
            moved_bytes: 0,
            complete: false,
        })
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
            match result {
                Ok(socket) => flow.ready_target = Some((grant, socket)),
                Err(error) => {
                    self.seal_output(Instant::now())?;
                    self.failure(GatewayFailure::from_error(&error));
                    self.remove_flow(id)?;
                }
            }
        }
        let ready: Vec<_> = self
            .flows
            .iter()
            .filter_map(|(id, flow)| flow.ready_target.is_some().then_some(*id))
            .collect();
        for id in ready {
            let flow = self.flows.get_mut(&id).ok_or(GatewayError::WrongTarget)?;
            let (grant, socket) = flow.ready_target.take().ok_or(GatewayError::WrongTarget)?;
            let result = match flow.service.prepare_target() {
                Ok(()) => flow.bridge.attach_local_retained(socket, &grant),
                Err(error) => Err((error, socket)),
            };
            match result {
                Ok(()) => flow.service.mark_target_connected(),
                Err((GatewayError::Admission(AdmissionError::Contended), socket)) => {
                    // Keep the exact target and receive proof until the existing
                    // five-second remote handshake deadline. No new task/grant.
                    flow.ready_target = Some((grant, socket));
                }
                Err((error, _)) => {
                    self.seal_output(Instant::now())?;
                    self.failure(GatewayFailure::from_error(&error));
                    self.remove_flow(id)?;
                }
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
