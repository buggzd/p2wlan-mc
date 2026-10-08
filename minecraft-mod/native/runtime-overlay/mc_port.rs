//! Fenced application packet queues and exact upstream dataplane handoffs.
use std::net::Ipv4Addr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use p2pnet_tun::IpPacket;
use tokio::sync::mpsc;
use tokio::time::{sleep, timeout_at, Instant};

use super::{
    AdmissionError, ApplicationAuthority, FramePermit, PeerGrant, ScopeOwner, ScopeToken,
    PACKET_CAPACITY, PACKET_MTU,
};
use crate::dataplane::InboundPacket;
use crate::rooms::RoomSendPermit;

/// Packet bytes cannot be separated from their original admission accidentally.
#[derive(Debug)]
pub struct FencedPacket {
    pub(super) bytes: Vec<u8>,
    pub(super) permit: FramePermit,
}
impl FencedPacket {
    pub fn permit(&self) -> &FramePermit {
        &self.permit
    }
    pub fn validate(&self) -> Result<(), AdmissionError> {
        self.permit.validate()
    }
    /// Borrowing retains the original envelope on contention/backpressure.
    pub fn checked_bytes(&self) -> Result<&[u8], AdmissionError> {
        self.validate()?;
        Ok(&self.bytes)
    }
    pub fn into_bytes(self) -> Result<Vec<u8>, AdmissionError> {
        self.validate()?;
        Ok(self.bytes)
    }
}

pub struct FencedPacketEndpoint {
    incoming: mpsc::Receiver<FencedPacket>,
    pending_receive: Option<(FencedPacket, std::time::Instant)>,
    outgoing: mpsc::Sender<FencedPacket>,
    authority: ApplicationAuthority,
    gate: Arc<PacketGate>,
}
impl FencedPacketEndpoint {
    pub fn authority(&self) -> &ApplicationAuthority {
        &self.authority
    }
    pub fn drops_by_reason(&self, reason: AdmissionError) -> u64 {
        self.gate.reasons[reason as usize].load(Ordering::Relaxed)
    }
    pub fn dropped_packets(&self) -> u64 {
        self.gate.drops.load(Ordering::Relaxed)
    }
    pub fn try_recv(&mut self) -> Result<Option<FencedPacket>, AdmissionError> {
        if !self.gate.is_up() {
            return Err(AdmissionError::PortClosed);
        }
        let (packet, started) = match self.pending_receive.take() {
            Some(pending) => pending,
            None => match self.incoming.try_recv() {
                Ok(packet) => (packet, std::time::Instant::now()),
                Err(mpsc::error::TryRecvError::Empty) => return Ok(None),
                Err(mpsc::error::TryRecvError::Disconnected) => {
                    return Err(AdmissionError::PortClosed)
                }
            },
        };
        if started.elapsed() >= Duration::from_millis(250) {
            self.gate.record_drop(AdmissionError::Timeout);
            return Err(AdmissionError::Timeout);
        }
        let result = self
            .authority
            .validate_grant(packet.permit.grant())
            .and_then(|()| packet.validate());
        match result {
            Ok(()) => Ok(Some(packet)),
            Err(AdmissionError::Contended) => {
                self.pending_receive = Some((packet, started));
                Err(AdmissionError::Contended)
            }
            Err(reason) => {
                self.gate.record_drop(reason);
                Err(reason)
            }
        }
    }
    pub fn make_packet(
        &self,
        grant: &PeerGrant,
        bytes: Vec<u8>,
    ) -> Result<FencedPacket, AdmissionError> {
        self.authority.validate_grant(grant)?;
        check_packet(&bytes)?;
        let parsed = IpPacket::new(&bytes).map_err(|_| AdmissionError::InvalidPacket)?;
        if parsed.src_addr_string() != self.authority.address().to_string()
            || parsed.dst_addr_string() != grant.address().to_string()
        {
            return Err(AdmissionError::AddressMismatch);
        }
        let grant = grant.with_scope(self.gate.lifetime.clone())?;
        Ok(FencedPacket {
            bytes,
            permit: grant.frame_permit()?,
        })
    }
    /// Return the original envelope on backpressure. Do not re-authorize it.
    pub fn try_send(&self, packet: FencedPacket) -> Result<(), (AdmissionError, FencedPacket)> {
        if let Err(error) = self.authority.validate_grant(packet.permit.grant()) {
            return Err((error, packet));
        }
        if !packet.permit.grant().has_scope(&self.gate.lifetime) {
            return Err((AdmissionError::PortMismatch, packet));
        }
        if let Err(error) = packet.validate() {
            return Err((error, packet));
        }
        self.outgoing.try_send(packet).map_err(|error| match error {
            mpsc::error::TrySendError::Full(packet) => (AdmissionError::QueueFull, packet),
            mpsc::error::TrySendError::Closed(packet) => (AdmissionError::PortClosed, packet),
        })
    }
}

pub struct PacketGate {
    authority: ApplicationAuthority,
    to_stack: mpsc::Sender<FencedPacket>,
    last_outbound: Mutex<Option<FramePermit>>,
    lifetime: ScopeToken,
    drops: AtomicU64,
    reasons: [AtomicU64; AdmissionError::Contended as usize + 1],
}
impl PacketGate {
    pub(crate) fn validate_upstream(
        &self,
        peers: &Arc<crate::peer::PeerManager>,
        transport: &crate::transport::WireGuardTransport,
    ) -> Result<(), AdmissionError> {
        if !self.authority.matches_upstream(peers, transport) {
            return Err(AdmissionError::AuthorityMismatch);
        }
        Ok(())
    }
    pub(super) fn is_up(&self) -> bool {
        self.lifetime.is_live() && self.authority.is_running() && !self.to_stack.is_closed()
    }
    pub(super) fn pair(
        authority: ApplicationAuthority,
    ) -> (
        mpsc::Receiver<FencedPacket>,
        Arc<Self>,
        FencedPacketEndpoint,
        ScopeOwner,
    ) {
        let (to_core, core_read) = mpsc::channel(PACKET_CAPACITY);
        let (to_stack, stack_read) = mpsc::channel(PACKET_CAPACITY);
        let lifetime = ScopeOwner::new();
        let gate = Arc::new(Self {
            authority: authority.clone(),
            to_stack,
            last_outbound: Mutex::new(None),
            lifetime: lifetime.token(),
            drops: AtomicU64::new(0),
            reasons: std::array::from_fn(|_| AtomicU64::new(0)),
        });
        let endpoint = FencedPacketEndpoint {
            incoming: stack_read,
            pending_receive: None,
            outgoing: to_core,
            authority,
            gate: gate.clone(),
        };
        (core_read, gate, endpoint, lifetime)
    }
    pub(crate) fn record_drop(&self, reason: AdmissionError) {
        self.drops.fetch_add(1, Ordering::Relaxed);
        self.reasons[reason as usize].fetch_add(1, Ordering::Relaxed);
    }
    pub(super) fn capture_outbound(&self, permit: FramePermit) -> Result<(), AdmissionError> {
        self.authority.validate_grant(permit.grant())?;
        permit.validate()?;
        if !permit.grant().has_scope(&self.lifetime) {
            return Err(AdmissionError::PortMismatch);
        }
        let mut context = self
            .last_outbound
            .try_lock()
            .map_err(|_| AdmissionError::Contended)?;
        if context.is_some() {
            return Err(AdmissionError::ContextMissing);
        }
        *context = Some(permit);
        Ok(())
    }
    pub(crate) fn take_outbound(&self) -> Result<FramePermit, AdmissionError> {
        self.last_outbound
            .try_lock()
            .map_err(|_| AdmissionError::Contended)?
            .take()
            .ok_or(AdmissionError::ContextMissing)
    }
    pub(crate) fn route_permit(
        &self,
        permit: FramePermit,
        peer: &str,
        ip: &str,
    ) -> Option<RoomSendPermit> {
        let routed = self
            .authority
            .validate_grant(permit.grant())
            .and_then(|()| permit.into_room_permit(peer, ip));
        match routed {
            Ok(room) => Some(room),
            Err(reason) => {
                self.record_drop(reason);
                None
            }
        }
    }
    /// Capture at decryption, before upstream evidence awaits and queueing.
    /// The encrypted-ingress network identity may never be inferred anew here.
    pub fn authorize_inbound(
        &self,
        mut packet: InboundPacket,
        network_generation: u64,
    ) -> Result<InboundPacket, AdmissionError> {
        if packet.mc_application_permit.is_some() {
            return Err(AdmissionError::AlreadyAdmitted);
        }
        if self.authority.network_generation() != network_generation {
            return Err(AdmissionError::NetworkChanged);
        }
        check_packet(&packet.packet)?;
        let parsed = IpPacket::new(&packet.packet).map_err(|_| AdmissionError::InvalidPacket)?;
        if parsed.dst_addr_string() != self.authority.address().to_string() {
            return Err(AdmissionError::AddressMismatch);
        }
        let address = parsed
            .src_addr_string()
            .parse::<Ipv4Addr>()
            .map_err(|_| AdmissionError::InvalidPacket)?;
        let grant =
            self.authority
                .inbound_grant(&packet.peer_id, address, self.lifetime.clone())?;
        let permit = grant
            .frame_permit()?
            .authenticated(packet.session_instance)?;
        // Recheck the ingress generation after all admission reads.
        if self.authority.network_generation() != network_generation {
            return Err(AdmissionError::NetworkChanged);
        }
        packet.mc_application_permit = Some(Arc::new(permit));
        Ok(packet)
    }
    fn inbound_permit(&self, packet: &InboundPacket) -> Result<FramePermit, AdmissionError> {
        let permit = packet
            .mc_application_permit
            .as_ref()
            .ok_or(AdmissionError::ContextMissing)?;
        self.authority.validate_grant(permit.grant())?;
        if !permit.grant().has_scope(&self.lifetime) {
            return Err(AdmissionError::PortMismatch);
        }
        if permit.grant().peer_id() != packet.peer_id {
            return Err(AdmissionError::AddressMismatch);
        }
        permit.validate_authenticated(packet.session_instance)?;
        Ok((**permit).clone())
    }
    /// Retain one original encrypted-ingress envelope while its existing
    /// crypto owner is busy. No fresh grant, lease or session is acquired.
    /// There is no new queue/task or lock held over sleep. Both contention
    /// and capacity share one 250 ms operation deadline.
    async fn ready_inbound(
        &self,
        packet: &InboundPacket,
        deadline: Instant,
    ) -> Result<FramePermit, AdmissionError> {
        timeout_at(deadline, async {
            loop {
                if Instant::now() >= deadline {
                    return Err(AdmissionError::Timeout);
                }
                match self.inbound_permit(packet) {
                    Err(AdmissionError::Contended) => sleep(Duration::from_millis(1)).await,
                    result => return result,
                }
            }
        })
        .await
        .map_err(|_| AdmissionError::Timeout)?
    }
    /// Use the existing upstream channel, not another unbounded queue. Reserve
    /// first, then validate the original identity before committing the packet.
    pub(crate) async fn queue_inbound(
        &self,
        sender: &mpsc::Sender<InboundPacket>,
        packet: InboundPacket,
    ) -> Result<(), AdmissionError> {
        let deadline = Instant::now() + Duration::from_millis(250);
        self.ready_inbound(&packet, deadline).await?;
        let slot = timeout_at(deadline, sender.reserve())
            .await
            .map_err(|_| AdmissionError::Timeout)?
            .map_err(|_| AdmissionError::PortClosed)?;
        self.ready_inbound(&packet, deadline).await?;
        slot.send(packet);
        Ok(())
    }
    /// The original decryption admission survives both inbound channels. No
    /// fresh membership or lease is acquired after either queue boundary.
    pub(crate) async fn deliver_inbound(
        &self,
        packet: &InboundPacket,
        bytes: &[u8],
    ) -> Result<usize, AdmissionError> {
        check_packet(bytes)?;
        let deadline = Instant::now() + Duration::from_millis(250);
        let permit = self.ready_inbound(packet, deadline).await?;
        let parsed = IpPacket::new(bytes).map_err(|_| AdmissionError::InvalidPacket)?;
        if parsed.src_addr_string() != permit.grant().address().to_string()
            || parsed.dst_addr_string() != self.authority.address().to_string()
        {
            return Err(AdmissionError::AddressMismatch);
        }
        let slot = timeout_at(deadline, self.to_stack.reserve())
            .await
            .map_err(|_| AdmissionError::Timeout)?
            .map_err(|_| AdmissionError::PortClosed)?;
        let permit = self.ready_inbound(packet, deadline).await?;
        slot.send(FencedPacket {
            bytes: bytes.to_vec(),
            permit,
        });
        Ok(bytes.len())
    }
}

fn check_packet(bytes: &[u8]) -> Result<(), AdmissionError> {
    if bytes.len() > PACKET_MTU {
        return Err(AdmissionError::PacketTooLarge);
    }
    if bytes.len() < 20 || bytes[0] >> 4 != 4 {
        return Err(AdmissionError::InvalidPacket);
    }
    IpPacket::new(bytes).map_err(|_| AdmissionError::InvalidPacket)?;
    Ok(())
}
