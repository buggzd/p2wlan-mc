//! One-shot encrypted-room service discovery. No peer roster or path cache.
use crate::{GatewayError, ServiceIdentity, MAX_REGISTRATIONS};
use p2wlan_mc_runtime_port::{FencedPacket, FramePermit, PeerGrant, ScopeOwner};
use smoltcp::phy::ChecksumCapabilities;
use smoltcp::wire::{IpAddress, IpProtocol, Ipv4Packet, Ipv4Repr, UdpPacket, UdpRepr};
use std::net::Ipv4Addr;
use std::time::{Duration, Instant};

pub const DISCOVERY_TIMEOUT: Duration = Duration::from_secs(2);
pub const DISCOVERY_PORT: u16 = 1023;
const RETRY_INTERVAL: Duration = Duration::from_millis(500);
const MAX_TRANSMISSIONS: u8 = 3;
const REQUEST: [u8; 8] = *b"P2MC\x00\x02\x00\x01";
const RESPONSE: [u8; 8] = *b"P2MC\x00\x02\x01\x01";
const PREFIX: usize = 24;
const ENTRY: usize = 34;
const MAX_PAYLOAD: usize = PREFIX + 1 + ENTRY * MAX_REGISTRATIONS;

#[derive(Clone, Copy, Eq, PartialEq)]
pub struct DiscoveryTicket([u8; 16]);
#[derive(Clone, Copy, Eq, PartialEq)]
pub struct DiscoveredService {
    pub port: u16,
    pub identity: ServiceIdentity,
}
pub(crate) enum Message {
    Request(DiscoveryTicket),
    Response(DiscoveryTicket, Vec<DiscoveredService>),
}
pub(crate) struct DiscoveryFrame {
    pub source: Ipv4Addr,
    pub destination: Ipv4Addr,
    pub message: Message,
}
impl DiscoveryFrame {
    /// Only UDP discovery is consumed here; TCP remains owned by TcpStack.
    pub(crate) fn parse(bytes: &[u8]) -> Result<Option<Self>, GatewayError> {
        let ip = Ipv4Packet::new_checked(bytes).map_err(|_| GatewayError::InvalidPacket)?;
        if ip.next_header() != IpProtocol::Udp {
            return Ok(None);
        }
        if ip.version() != 4
            || ip.total_len() as usize != bytes.len()
            || ip.more_frags()
            || ip.frag_offset() != 0
        {
            return Err(GatewayError::InvalidPacket);
        }
        if !ip.verify_checksum() {
            return Err(GatewayError::BadChecksum);
        }
        let udp = UdpPacket::new_checked(ip.payload()).map_err(|_| GatewayError::InvalidPacket)?;
        if udp.src_port() != DISCOVERY_PORT || udp.dst_port() != DISCOVERY_PORT {
            return Err(GatewayError::UnsupportedPacket);
        }
        if udp.len() as usize != ip.payload().len() {
            return Err(GatewayError::InvalidPacket);
        }
        if !udp.verify_checksum(
            &IpAddress::Ipv4(ip.src_addr()),
            &IpAddress::Ipv4(ip.dst_addr()),
        ) {
            return Err(GatewayError::BadChecksum);
        }
        Ok(Some(Self {
            source: ip.src_addr(),
            destination: ip.dst_addr(),
            message: Message::decode(udp.payload())?,
        }))
    }
}
impl Message {
    fn decode(bytes: &[u8]) -> Result<Self, GatewayError> {
        if bytes.len() < PREFIX || bytes.len() > MAX_PAYLOAD {
            return Err(GatewayError::InvalidPacket);
        }
        let ticket = DiscoveryTicket(
            bytes[8..PREFIX]
                .try_into()
                .map_err(|_| GatewayError::InvalidPacket)?,
        );
        match &bytes[..8] {
            magic if magic == REQUEST && bytes.len() == PREFIX => Ok(Self::Request(ticket)),
            magic if magic == RESPONSE && bytes.len() > PREFIX => {
                let count = bytes[PREFIX] as usize;
                if count > MAX_REGISTRATIONS || bytes.len() != PREFIX + 1 + count * ENTRY {
                    return Err(GatewayError::InvalidPacket);
                }
                let mut services: Vec<DiscoveredService> = Vec::with_capacity(count);
                for entry in bytes[PREFIX + 1..].chunks_exact(ENTRY) {
                    let port = u16::from_be_bytes([entry[0], entry[1]]);
                    let identity = ServiceIdentity::from_wire(
                        entry[2..]
                            .try_into()
                            .map_err(|_| GatewayError::InvalidPacket)?,
                    );
                    if !(1024..49152).contains(&port)
                        || services
                            .iter()
                            .any(|service| service.port == port || service.identity == identity)
                    {
                        return Err(GatewayError::InvalidPacket);
                    }
                    services.push(DiscoveredService { port, identity });
                }
                Ok(Self::Response(ticket, services))
            }
            _ => Err(GatewayError::InvalidPacket),
        }
    }
    pub(crate) fn packet(
        &self,
        source: Ipv4Addr,
        destination: Ipv4Addr,
    ) -> Result<Vec<u8>, GatewayError> {
        let (ticket, magic) = match self {
            Self::Request(ticket) => (ticket, REQUEST),
            Self::Response(ticket, _) => (ticket, RESPONSE),
        };
        let mut payload = magic.to_vec();
        payload.extend_from_slice(&ticket.0);
        if let Self::Response(_, services) = self {
            if services.len() > MAX_REGISTRATIONS {
                return Err(GatewayError::Capacity);
            }
            payload.push(services.len() as u8);
            for service in services {
                payload.extend_from_slice(&service.port.to_be_bytes());
                payload.extend_from_slice(&service.identity.wire());
            }
        }
        let mut bytes = vec![0; 28 + payload.len()];
        let checksums = ChecksumCapabilities::default();
        let ip = Ipv4Repr {
            src_addr: source,
            dst_addr: destination,
            next_header: IpProtocol::Udp,
            payload_len: 8 + payload.len(),
            hop_limit: 64,
        };
        ip.emit(
            &mut Ipv4Packet::new_unchecked(bytes.as_mut_slice()),
            &checksums,
        );
        UdpRepr {
            src_port: DISCOVERY_PORT,
            dst_port: DISCOVERY_PORT,
        }
        .emit(
            &mut UdpPacket::new_unchecked(&mut bytes[20..]),
            &IpAddress::Ipv4(source),
            &IpAddress::Ipv4(destination),
            payload.len(),
            |output| output.copy_from_slice(&payload),
            &checksums,
        );
        Ok(bytes)
    }
}

struct Query {
    ticket: DiscoveryTicket,
    grant: PeerGrant,
    _lifetime: ScopeOwner,
    deadline: Instant,
    result: Option<(Vec<DiscoveredService>, FramePermit)>,
    request: Option<FencedPacket>,
    transmissions: u8,
    next_transmission: Instant,
}
/// At most ONE in-flight query and ONE bounded response. The ticket is random
/// per query. Timeout/cancellation revokes queued envelopes, not just UI state.
#[derive(Default)]
pub(crate) struct Discovery {
    query: Option<Query>,
    expired: Option<DiscoveryTicket>,
}
impl Discovery {
    pub(crate) fn active(&self) -> bool {
        self.query.is_some()
    }
    pub(crate) fn expire(&mut self, now: Instant) -> bool {
        if self
            .query
            .as_ref()
            .is_some_and(|query| now >= query.deadline)
        {
            self.expired = self.query.take().map(|query| query.ticket);
            return true;
        }
        false
    }
    pub(crate) fn start(
        &mut self,
        grant: PeerGrant,
        now: Instant,
    ) -> Result<(DiscoveryTicket, PeerGrant), GatewayError> {
        self.expire(now);
        if self.query.is_some() {
            return Err(GatewayError::DiscoveryBusy);
        }
        let mut ticket = [0; 16];
        getrandom::getrandom(&mut ticket).map_err(|_| GatewayError::Entropy)?;
        let ticket = DiscoveryTicket(ticket);
        let lifetime = ScopeOwner::new();
        let grant = grant.with_scope(lifetime.token())?;
        self.query = Some(Query {
            ticket,
            grant: grant.clone(),
            _lifetime: lifetime,
            deadline: now + DISCOVERY_TIMEOUT,
            result: None,
            request: None,
            transmissions: 1,
            next_transmission: now + RETRY_INTERVAL,
        });
        self.expired = None;
        Ok((ticket, grant))
    }
    pub(crate) fn remember_request(
        &mut self,
        ticket: DiscoveryTicket,
        packet: &FencedPacket,
    ) -> Result<(), GatewayError> {
        let query = self
            .query
            .as_mut()
            .filter(|query| query.ticket == ticket)
            .ok_or(GatewayError::StaleDiscovery)?;
        let Some(frame) = DiscoveryFrame::parse(packet.checked_bytes()?)? else {
            return Err(GatewayError::InvalidPacket);
        };
        if !matches!(frame.message, Message::Request(id) if id == ticket)
            || frame.destination != query.grant.address()
        {
            return Err(GatewayError::InvalidPacket);
        }
        query.request = Some(packet.clone());
        Ok(())
    }
    /// Loss recovery for this READ-ONLY metadata query only. Reuse the original
    /// nonce/permit/scope/deadline; never acquire a fresh grant or replay an old
    /// ciphertext/game message. The original daemon encrypts each transmission.
    pub(crate) fn retry(&mut self, now: Instant) -> Result<Option<FencedPacket>, GatewayError> {
        self.expire(now);
        let Some(query) = self.query.as_mut() else {
            return Ok(None);
        };
        if query.result.is_some()
            || query.transmissions >= MAX_TRANSMISSIONS
            || now < query.next_transmission
        {
            return Ok(None);
        }
        let Some(packet) = query.request.as_ref() else {
            return Ok(None);
        };
        packet.validate()?;
        query.transmissions += 1;
        query.next_transmission = now + RETRY_INTERVAL;
        Ok(Some(packet.clone()))
    }
    pub(crate) fn receive(
        &mut self,
        ticket: DiscoveryTicket,
        services: Vec<DiscoveredService>,
        packet: &FencedPacket,
        now: Instant,
    ) -> Result<(), GatewayError> {
        self.expire(now);
        let query = self
            .query
            .as_mut()
            .filter(|query| query.ticket == ticket)
            .ok_or(GatewayError::StaleDiscovery)?;
        if query.result.is_some() {
            return Err(GatewayError::StaleDiscovery);
        }
        let permit = packet.permit().bind_received_connection(&query.grant)?;
        permit.validate()?;
        query.result = Some((services, permit));
        Ok(())
    }
    pub(crate) fn poll(
        &mut self,
        ticket: DiscoveryTicket,
        now: Instant,
    ) -> Result<Option<Vec<DiscoveredService>>, GatewayError> {
        self.expire(now);
        if self.expired == Some(ticket) {
            return Err(GatewayError::DiscoveryTimeout);
        }
        let query = self
            .query
            .as_ref()
            .filter(|query| query.ticket == ticket)
            .ok_or(GatewayError::StaleDiscovery)?;
        query.grant.validate()?;
        if let Some((_, permit)) = &query.result {
            permit.validate()?;
        } else {
            return Ok(None);
        }
        // No await/callback between revalidation and publishing this snapshot.
        let query = self.query.take().ok_or(GatewayError::StaleDiscovery)?;
        Ok(query.result.map(|(services, _)| services))
    }
    pub(crate) fn cancel(&mut self, ticket: DiscoveryTicket) -> Result<(), GatewayError> {
        if self
            .query
            .as_ref()
            .is_some_and(|query| query.ticket == ticket)
        {
            self.query = None;
            Ok(())
        } else {
            Err(GatewayError::StaleDiscovery)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn wire_bounds_counts_checksum_and_duplicates_fail_closed() {
        let a = "10.21.7.1".parse().unwrap();
        let b = "10.21.7.2".parse().unwrap();
        let ticket = DiscoveryTicket([1; 16]);
        let identity = ServiceIdentity::generate().unwrap();
        for message in [
            Message::Request(ticket),
            Message::Response(ticket, vec![]),
            Message::Response(
                ticket,
                vec![DiscoveredService {
                    port: 1024,
                    identity,
                }],
            ),
        ] {
            let bytes = message.packet(a, b).unwrap();
            let parsed = DiscoveryFrame::parse(&bytes).unwrap().unwrap();
            assert_eq!(parsed.source, a);
            assert_eq!(parsed.destination, b);
            let mut bad = bytes.clone();
            *bad.last_mut().unwrap() ^= 1;
            assert!(matches!(
                DiscoveryFrame::parse(&bad),
                Err(GatewayError::BadChecksum)
            ));
            assert!(DiscoveryFrame::parse(&bytes[..bytes.len() - 1]).is_err());
        }
        let full: Vec<_> = (1024..1032)
            .map(|port| DiscoveredService {
                port,
                identity: ServiceIdentity::generate().unwrap(),
            })
            .collect();
        let full = Message::Response(ticket, full).packet(a, b).unwrap();
        assert_eq!(full.len(), 325);
        let Some(DiscoveryFrame {
            message: Message::Response(_, services),
            ..
        }) = DiscoveryFrame::parse(&full).unwrap()
        else {
            panic!("full catalog fixture");
        };
        assert_eq!(services.len(), MAX_REGISTRATIONS);
        let mut bytes = RESPONSE.to_vec();
        bytes.extend_from_slice(&ticket.0);
        bytes.push(9);
        assert!(Message::decode(&bytes).is_err());
        bytes[PREFIX] = 0;
        bytes.push(0);
        assert!(Message::decode(&bytes).is_err());
        let service = DiscoveredService {
            port: 1024,
            identity,
        };
        let duplicate = Message::Response(ticket, vec![service, service])
            .packet(a, b)
            .unwrap();
        assert!(DiscoveryFrame::parse(&duplicate).is_err());
        assert!(Message::Response(ticket, vec![service; 9])
            .packet(a, b)
            .is_err());
        let invalid = Message::Response(
            ticket,
            vec![DiscoveredService {
                port: DISCOVERY_PORT,
                identity,
            }],
        )
        .packet(a, b)
        .unwrap();
        assert!(DiscoveryFrame::parse(&invalid).is_err());
    }
    async fn inbound(
        local: &crate::test_support::Rig,
        remote: &crate::test_support::Rig,
    ) -> (crate::test_support::Plane, FencedPacket) {
        let mut local_plane = local.plane();
        let mut remote_plane = remote.plane();
        let owner = ScopeOwner::new();
        let grant = remote
            .authority
            .peer_grant(&remote.remote.node_id, owner.token())
            .await
            .unwrap();
        let packet = remote_plane.route(&grant, remote.echo()).await;
        let encrypted = remote
            .transport
            .encrypt_outbound(packet)
            .await
            .unwrap()
            .unwrap();
        let packet = local
            .transport
            .decrypt_inbound(&encrypted.wire_bytes)
            .await
            .unwrap()
            .unwrap();
        let packet = local_plane
            .gate
            .authorize_inbound(packet, local.authority.network_generation())
            .unwrap();
        local_plane.inbound.send(packet).await.unwrap();
        let packet = tokio::time::timeout(Duration::from_secs(2), async {
            loop {
                if let Some(packet) = local_plane.endpoint.try_recv().unwrap() {
                    break packet;
                }
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        (local_plane, packet)
    }
    #[tokio::test]
    async fn stale_response_original_crypto_and_revoked_snapshot_cannot_be_reauthorized() {
        use crate::test_support;
        use p2wlan_mc_runtime_port::AdmissionError;
        let (local, remote) = test_support::pair().await;
        let (_plane, packet) = inbound(&local, &remote).await;
        let owner = ScopeOwner::new();
        let grant = local
            .authority
            .peer_grant(&local.remote.node_id, owner.token())
            .await
            .unwrap();
        let mut discovery = Discovery::default();
        let now = Instant::now();
        let (old, _) = discovery.start(grant.clone(), now).unwrap();
        discovery.cancel(old).unwrap();
        let (next, _) = discovery.start(grant, now).unwrap();
        assert!(matches!(
            discovery.receive(old, vec![], &packet, now),
            Err(GatewayError::StaleDiscovery)
        ));
        let (foreign, sender) = test_support::pair().await;
        let (_foreign_plane, foreign_packet) = inbound(&foreign, &sender).await;
        assert!(matches!(
            discovery.receive(next, vec![], &foreign_packet, now),
            Err(GatewayError::Admission(AdmissionError::AuthorityMismatch))
        ));
        assert!(discovery.poll(next, now).unwrap().is_none());
        discovery.receive(next, vec![], &packet, now).unwrap();
        assert!(matches!(
            discovery.receive(next, vec![], &packet, now),
            Err(GatewayError::StaleDiscovery)
        ));
        local.room.invalidate();
        local.renew(now, 30);
        assert!(matches!(
            discovery.poll(next, now),
            Err(GatewayError::Admission(AdmissionError::RoomRevoked))
        ));
        discovery.cancel(next).unwrap();
        let grant = local
            .authority
            .peer_grant(&local.remote.node_id, owner.token())
            .await
            .unwrap();
        let (next, _) = discovery.start(grant, now).unwrap();
        assert!(matches!(
            discovery.receive(next, vec![], &packet, now),
            Err(GatewayError::Admission(AdmissionError::RoomRevoked))
        ));
        let (_plane, current) = inbound(&local, &remote).await;
        discovery.receive(next, vec![], &current, now).unwrap();
        local.transport.remove_session(&local.remote.node_id).await;
        assert!(matches!(
            discovery.poll(next, now),
            Err(GatewayError::Admission(AdmissionError::SessionChanged))
        ));
    }
    #[tokio::test]
    async fn query_capacity_deadline_cancel_and_old_tickets_retain_original_scope() {
        use crate::test_support;
        use p2wlan_mc_runtime_port::{AdmissionError, ScopeOwner};
        let (local, _) = test_support::pair().await;
        let parent = ScopeOwner::new();
        let grant = local
            .authority
            .peer_grant(&local.remote.node_id, parent.token())
            .await
            .unwrap();
        let mut discovery = Discovery::default();
        let now = Instant::now();
        let (old, queued) = discovery.start(grant.clone(), now).unwrap();
        let (_interface, endpoint) = local.interface();
        let bytes = Message::Request(old)
            .packet(local.authority.address(), queued.address())
            .unwrap();
        let packet = endpoint.make_packet(&queued, bytes).unwrap();
        discovery.remember_request(old, &packet).unwrap();
        assert!(discovery.retry(now).unwrap().is_none());
        let first = discovery.retry(now + RETRY_INTERVAL).unwrap().unwrap();
        assert_eq!(
            first.checked_bytes().unwrap(),
            packet.checked_bytes().unwrap()
        );
        assert!(discovery.retry(now + RETRY_INTERVAL * 2).unwrap().is_some());
        assert!(discovery.retry(now + RETRY_INTERVAL * 3).unwrap().is_none());
        assert!(matches!(
            discovery.start(grant.clone(), now),
            Err(GatewayError::DiscoveryBusy)
        ));
        assert!(discovery.poll(old, now).unwrap().is_none());
        assert!(discovery.expire(now + DISCOVERY_TIMEOUT));
        assert!(matches!(first.validate(), Err(AdmissionError::ScopeClosed)));
        assert!(matches!(
            queued.validate(),
            Err(AdmissionError::ScopeClosed)
        ));
        assert!(matches!(
            discovery.poll(old, now + DISCOVERY_TIMEOUT),
            Err(GatewayError::DiscoveryTimeout)
        ));
        let (next, queued) = discovery.start(grant, now + DISCOVERY_TIMEOUT).unwrap();
        assert!(old != next);
        assert!(matches!(
            discovery.poll(old, now),
            Err(GatewayError::StaleDiscovery)
        ));
        assert!(matches!(
            discovery.cancel(old),
            Err(GatewayError::StaleDiscovery)
        ));
        assert!(discovery.active());
        discovery.cancel(next).unwrap();
        assert!(matches!(
            queued.validate(),
            Err(AdmissionError::ScopeClosed)
        ));
        assert!(!discovery.active());
    }
}
