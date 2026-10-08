//! Read-only application capabilities over the upstream network owners.
use std::fmt;
use std::net::Ipv4Addr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Weak};
use std::time::Duration;

use tokio::sync::watch;
use tokio::time::timeout;

use crate::peer::{PeerManager, PeerSessionGeneration};
use crate::rooms::{AdditionalSendFence, RoomAuthorization, RoomSendPermit};
use crate::transport::WireGuardTransport;
use crate::Config;

pub const MAX_SCOPES: usize = 4;
pub const ADMISSION_TIMEOUT: Duration = Duration::from_millis(100);

#[repr(usize)]
#[derive(Debug, Clone, Copy, Eq, PartialEq, thiserror::Error)]
pub enum AdmissionError {
    #[error("ROOM_REQUIRED")]
    RoomRequired,
    #[error("RUNTIME_CLOSED")]
    RuntimeClosed,
    #[error("SCOPE_CLOSED")]
    ScopeClosed,
    #[error("SCOPE_CAPACITY")]
    ScopeCapacity,
    #[error("NETWORK_CHANGED")]
    NetworkChanged,
    #[error("PEER_CHANGED")]
    PeerChanged,
    #[error("ROOM_REVOKED")]
    RoomRevoked,
    #[error("ROOM_SNAPSHOT_CHANGED")]
    RoomSnapshotChanged,
    #[error("ROOM_LEASE_EXPIRED")]
    RoomLeaseExpired,
    #[error("UNKNOWN_PEER")]
    UnknownPeer,
    #[error("ADDRESS_MISMATCH")]
    AddressMismatch,
    #[error("INVALID_PACKET")]
    InvalidPacket,
    #[error("PACKET_TOO_LARGE")]
    PacketTooLarge,
    #[error("QUEUE_FULL")]
    QueueFull,
    #[error("PORT_CLOSED")]
    PortClosed,
    #[error("PORT_MISMATCH")]
    PortMismatch,
    #[error("ALREADY_ADMITTED")]
    AlreadyAdmitted,
    #[error("AUTHORITY_MISMATCH")]
    AuthorityMismatch,
    #[error("PORT_CONTEXT_MISSING")]
    ContextMissing,
    #[error("ADMISSION_TIMEOUT")]
    Timeout,
    #[error("SESSION_MISSING")]
    SessionMissing,
    #[error("SESSION_CHANGED")]
    SessionChanged,
    #[error("SESSION_CONTENDED")]
    Contended,
}

/// One non-cloneable gateway/world/connection lifetime owner. Snapshots below
/// hold only Weak tokens; dropping an old owner cannot revoke a replacement.
pub struct ScopeOwner {
    live: Arc<AtomicBool>,
}
#[derive(Clone)]
pub struct ScopeToken {
    live: Weak<AtomicBool>,
}

impl Default for ScopeOwner {
    fn default() -> Self {
        Self::new()
    }
}
impl ScopeOwner {
    pub fn new() -> Self {
        Self {
            live: Arc::new(AtomicBool::new(true)),
        }
    }
    pub fn token(&self) -> ScopeToken {
        ScopeToken {
            live: Arc::downgrade(&self.live),
        }
    }
    pub fn revoke(&mut self) {
        self.live.store(false, Ordering::Release);
    }
}
impl Drop for ScopeOwner {
    fn drop(&mut self) {
        self.revoke();
    }
}
impl ScopeToken {
    pub fn is_live(&self) -> bool {
        self.live
            .upgrade()
            .is_some_and(|live| live.load(Ordering::Acquire))
    }
}

/// Handles to the actual upstream authorities, not a second roster/session map.
#[derive(Clone)]
pub struct ApplicationAuthority {
    identity: Arc<()>,
    peers: Arc<PeerManager>,
    room: Arc<RoomAuthorization>,
    transport: WireGuardTransport,
    shutdown: watch::Receiver<bool>,
    local_ip: Ipv4Addr,
}

impl ApplicationAuthority {
    pub fn new(
        config: &Config,
        peers: Arc<PeerManager>,
        room: Arc<RoomAuthorization>,
        transport: WireGuardTransport,
        shutdown: watch::Receiver<bool>,
    ) -> Result<Self, AdmissionError> {
        if !config.network.network_id.starts_with("room-") || !room.enabled() {
            return Err(AdmissionError::RoomRequired);
        }
        let local_ip = config
            .network
            .virtual_ip
            .parse()
            .map_err(|_| AdmissionError::AddressMismatch)?;
        Ok(Self {
            identity: Arc::new(()),
            peers,
            room,
            transport,
            shutdown,
            local_ip,
        })
    }
    pub fn address(&self) -> Ipv4Addr {
        self.local_ip
    }
    pub fn network_generation(&self) -> u64 {
        self.peers.current_network_generation_sync()
    }
    pub fn is_running(&self) -> bool {
        !*self.shutdown.borrow() && self.shutdown.has_changed().is_ok()
    }

    /// Capture once for a specific peer connection. The original membership
    /// generation prevents same-node remove/re-add and offline/online reuse.
    pub async fn peer_grant(
        &self,
        peer_id: &str,
        scope: ScopeToken,
    ) -> Result<PeerGrant, AdmissionError> {
        if !self.is_running() {
            return Err(AdmissionError::RuntimeClosed);
        }
        let generation = self.peers.current_network_generation_sync();
        let peer_session = self
            .peers
            .peer_session_generation_sync(peer_id)
            .ok_or(AdmissionError::UnknownPeer)?;
        let peer = timeout(ADMISSION_TIMEOUT, self.peers.get_connection(peer_id))
            .await
            .map_err(|_| AdmissionError::Timeout)?
            .ok_or(AdmissionError::UnknownPeer)?;
        let ip = peer
            .virtual_ip
            .parse()
            .map_err(|_| AdmissionError::AddressMismatch)?;
        let grant = self.grant_at(peer_id, ip, scope, generation, peer_session)?;
        grant.validate()?;
        Ok(grant)
    }
    pub async fn grant_for_ip(
        &self,
        ip: Ipv4Addr,
        scope: ScopeToken,
    ) -> Result<PeerGrant, AdmissionError> {
        let id = timeout(
            ADMISSION_TIMEOUT,
            self.peers.resolve_virtual_ip(&ip.to_string()),
        )
        .await
        .map_err(|_| AdmissionError::Timeout)?
        .ok_or(AdmissionError::UnknownPeer)?;
        let grant = self.peer_grant(&id, scope).await?;
        if grant.address != ip {
            return Err(AdmissionError::AddressMismatch);
        }
        Ok(grant)
    }
    pub(crate) fn inbound_grant(
        &self,
        peer_id: &str,
        ip: Ipv4Addr,
        scope: ScopeToken,
    ) -> Result<PeerGrant, AdmissionError> {
        let generation = self.peers.current_network_generation_sync();
        let session = self
            .peers
            .peer_session_generation_sync(peer_id)
            .ok_or(AdmissionError::UnknownPeer)?;
        self.grant_at(peer_id, ip, scope, generation, session)
    }
    fn grant_at(
        &self,
        id: &str,
        address: Ipv4Addr,
        scope: ScopeToken,
        generation: u64,
        peer_session: PeerSessionGeneration,
    ) -> Result<PeerGrant, AdmissionError> {
        let membership = self
            .room
            .send_permit(id, &address.to_string(), &self.local_ip.to_string())
            .ok_or(AdmissionError::RoomRevoked)?;
        let grant = PeerGrant {
            authority: self.clone(),
            id: id.into(),
            address,
            generation,
            peer_session,
            membership,
            scopes: vec![scope],
        };
        grant.validate()?;
        Ok(grant)
    }
    /// Clones may share grants; another runtime may not, even when all public
    /// peer IDs and addresses happen to be identical. This is immutable identity,
    /// not a parallel source of network truth.
    pub(super) fn validate_grant(&self, grant: &PeerGrant) -> Result<(), AdmissionError> {
        if !Arc::ptr_eq(&self.identity, &grant.authority.identity) {
            return Err(AdmissionError::AuthorityMismatch);
        }
        grant.validate()
    }
    pub(super) fn matches_upstream(
        &self,
        peers: &Arc<PeerManager>,
        transport: &WireGuardTransport,
    ) -> bool {
        Arc::ptr_eq(&self.peers, peers) && self.transport.mc_same_session_owner(transport)
    }
    fn session_valid(&self, peer_id: &str, instance: u64) -> Result<(), AdmissionError> {
        match self
            .transport
            .mc_application_session_retained_try(peer_id, instance)
        {
            Some(true) => Ok(()),
            Some(false) => Err(AdmissionError::SessionChanged),
            None => Err(AdmissionError::Contended),
        }
    }
}

/// Immutable connection identity. Healthy renewal can continue this connection,
/// but re-authorization after expiry/revocation cannot revive its membership.
#[derive(Clone)]
pub struct PeerGrant {
    authority: ApplicationAuthority,
    id: String,
    address: Ipv4Addr,
    generation: u64,
    peer_session: PeerSessionGeneration,
    membership: RoomSendPermit,
    scopes: Vec<ScopeToken>,
}
impl fmt::Debug for PeerGrant {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("PeerGrant(..)")
    }
}
impl PartialEq for PeerGrant {
    fn eq(&self, other: &Self) -> bool {
        Arc::ptr_eq(&self.authority.identity, &other.authority.identity)
            && self.id == other.id
            && self.address == other.address
            && self.generation == other.generation
            && self.peer_session == other.peer_session
            && self.membership == other.membership
            && self.scopes.len() == other.scopes.len()
            && self
                .scopes
                .iter()
                .zip(&other.scopes)
                .all(|(a, b)| Weak::ptr_eq(&a.live, &b.live))
    }
}
impl Eq for PeerGrant {}
impl PeerGrant {
    pub fn peer_id(&self) -> &str {
        &self.id
    }
    pub fn address(&self) -> Ipv4Addr {
        self.address
    }
    pub fn validate(&self) -> Result<(), AdmissionError> {
        if !self.authority.is_running() {
            return Err(AdmissionError::RuntimeClosed);
        }
        if self.scopes.iter().any(|scope| !scope.is_live()) {
            return Err(AdmissionError::ScopeClosed);
        }
        if self.authority.peers.current_network_generation_sync() != self.generation {
            return Err(AdmissionError::NetworkChanged);
        }
        if !self
            .authority
            .peers
            .peer_session_is_current_sync(&self.id, self.peer_session)
        {
            return Err(AdmissionError::PeerChanged);
        }
        if !self.membership.mc_membership_live()
            || !self.authority.room.allows(
                &self.id,
                &self.address.to_string(),
                &self.authority.local_ip.to_string(),
            )
        {
            return Err(AdmissionError::RoomRevoked);
        }
        Ok(())
    }
    pub fn with_scope(&self, scope: ScopeToken) -> Result<Self, AdmissionError> {
        if self
            .scopes
            .iter()
            .any(|current| Weak::ptr_eq(&current.live, &scope.live))
        {
            self.validate()?;
            return Ok(self.clone());
        }
        if self.scopes.len() >= MAX_SCOPES {
            return Err(AdmissionError::ScopeCapacity);
        }
        let mut next = self.clone();
        next.scopes.push(scope);
        next.validate()?;
        Ok(next)
    }
    pub(crate) fn has_scope(&self, scope: &ScopeToken) -> bool {
        self.scopes
            .iter()
            .any(|current| Weak::ptr_eq(&current.live, &scope.live))
    }
    pub fn frame_permit(&self) -> Result<FramePermit, AdmissionError> {
        self.validate()?;
        let room = self
            .authority
            .room
            .send_permit(
                &self.id,
                &self.address.to_string(),
                &self.authority.local_ip.to_string(),
            )
            .ok_or(AdmissionError::RoomRevoked)?;
        let permit = FramePermit {
            grant: self.clone(),
            room,
            authenticated_session: None,
        };
        permit.validate()?;
        Ok(permit)
    }
}

/// Per-IP-frame lease. It never inherits the expiry of a later room renewal.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct FramePermit {
    grant: PeerGrant,
    room: RoomSendPermit,
    authenticated_session: Option<u64>,
}
impl FramePermit {
    pub fn grant(&self) -> &PeerGrant {
        &self.grant
    }
    pub fn validate(&self) -> Result<(), AdmissionError> {
        self.grant.validate()?;
        if !self.room.is_valid() {
            return Err(if self.room.mc_lease_expired() {
                AdmissionError::RoomLeaseExpired
            } else {
                AdmissionError::RoomSnapshotChanged
            });
        }
        if let Some(instance) = self.authenticated_session {
            self.grant
                .authority
                .session_valid(&self.grant.id, instance)?;
        }
        Ok(())
    }
    pub(crate) fn validate_authenticated(
        &self,
        instance: Option<u64>,
    ) -> Result<(), AdmissionError> {
        if instance.is_none() || self.authenticated_session != instance {
            return Err(AdmissionError::SessionChanged);
        }
        self.validate()
    }
    pub(crate) fn authenticated(mut self, instance: Option<u64>) -> Result<Self, AdmissionError> {
        self.authenticated_session = Some(instance.ok_or(AdmissionError::SessionMissing)?);
        self.validate()?;
        Ok(self)
    }
    pub(crate) fn into_room_permit(
        self,
        peer: &str,
        ip: &str,
    ) -> Result<RoomSendPermit, AdmissionError> {
        if self.grant.id != peer || self.grant.address.to_string() != ip {
            return Err(AdmissionError::AddressMismatch);
        }
        self.validate()?;
        let room = self.room.clone();
        Ok(room.with_application_fence(Arc::new(self)))
    }
}
impl AdditionalSendFence for FramePermit {
    fn is_current(&self) -> bool {
        self.validate().is_ok()
    }
}
