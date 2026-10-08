//! Remote service selection is separate from local socket capabilities and the
//! original daemon's peer/session authority. Never admit a TCP port alone.
use crate::{ConnectionBridge, GatewayError, ReceivedBytes};
use p2wlan_mc_bridge::TcpStack;
use std::sync::Arc;
use std::time::{Duration, Instant};

pub const SERVICE_HANDSHAKE_BYTES: usize = 40;
pub const SERVICE_HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);
const REQUEST_MAGIC: [u8; 8] = *b"P2MC\x00\x01\x00\x01";
const READY_MAGIC: [u8; 8] = *b"P2MC\x00\x01\x01\x01";

/// A fresh immutable identity for ONE hosted-world registration. It is not a
/// credential: original room/peer authorization is still required. It never
/// survives world replacement, runtime replacement, or native child restart.
#[derive(Clone, Copy, Eq, PartialEq)]
pub struct ServiceIdentity([u8; 32]);
impl ServiceIdentity {
    pub fn generate() -> Result<Self, GatewayError> {
        let mut bytes = [0; 32];
        getrandom::getrandom(&mut bytes).map_err(|_| GatewayError::Entropy)?;
        Ok(Self(bytes))
    }
    pub fn from_hex(text: &str) -> Result<Self, GatewayError> {
        if text.len() != 64
            || !text
                .bytes()
                .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
        {
            return Err(GatewayError::InvalidServiceIdentity);
        }
        let mut bytes = [0; 32];
        for (index, byte) in bytes.iter_mut().enumerate() {
            *byte = u8::from_str_radix(&text[index * 2..index * 2 + 2], 16)
                .map_err(|_| GatewayError::InvalidServiceIdentity)?;
        }
        Ok(Self(bytes))
    }
    pub fn opaque(self) -> String {
        self.0.iter().map(|byte| format!("{byte:02x}")).collect()
    }
    fn frame(self, magic: [u8; 8]) -> [u8; SERVICE_HANDSHAKE_BYTES] {
        let mut bytes = [0; SERVICE_HANDSHAKE_BYTES];
        bytes[..8].copy_from_slice(&magic);
        bytes[8..].copy_from_slice(&self.0);
        bytes
    }
}

/// Retains the original authenticated receive envelopes across target-connect
/// awaits. It cannot be reauthorized using a fresh peer grant or crypto key.
#[derive(Clone)]
pub(crate) struct ServiceAdmission(Arc<Vec<ReceivedBytes>>);
impl ServiceAdmission {
    pub(crate) fn validate(&self) -> Result<(), GatewayError> {
        for bytes in self.0.iter() {
            bytes.validate()?;
        }
        Ok(())
    }
}

#[derive(Clone, Copy, Eq, PartialEq)]
enum Role {
    Host,
    Proxy,
}

/// One fixed 40-byte input and output per existing flow; no extra socket,
/// queue, task, retry or discovery cache. At most 40 one-byte receive fragments.
pub(crate) struct ServiceHandshake {
    role: Role,
    expected: [u8; SERVICE_HANDSHAKE_BYTES],
    output: [u8; SERVICE_HANDSHAKE_BYTES],
    received: Vec<ReceivedBytes>,
    pending: Option<ReceivedBytes>,
    used: usize,
    sent: usize,
    admission: Option<ServiceAdmission>,
    target_ready: bool,
    complete: bool,
    deadline: Instant,
}
impl ServiceHandshake {
    fn new(identity: ServiceIdentity, role: Role, now: Instant) -> Self {
        let (input, output) = match role {
            Role::Host => (REQUEST_MAGIC, READY_MAGIC),
            Role::Proxy => (READY_MAGIC, REQUEST_MAGIC),
        };
        Self {
            role,
            expected: identity.frame(input),
            output: identity.frame(output),
            received: Vec::new(),
            pending: None,
            used: 0,
            sent: 0,
            admission: None,
            target_ready: false,
            complete: false,
            deadline: now + SERVICE_HANDSHAKE_TIMEOUT,
        }
    }
    pub(crate) fn host(identity: ServiceIdentity, now: Instant) -> Self {
        Self::new(identity, Role::Host, now)
    }
    pub(crate) fn proxy(identity: ServiceIdentity, now: Instant) -> Self {
        Self::new(identity, Role::Proxy, now)
    }
    fn record(&mut self) -> Result<(), GatewayError> {
        let bytes = self
            .pending
            .as_ref()
            .ok_or(GatewayError::MissingAdmission)?;
        let end = self
            .used
            .checked_add(bytes.remaining())
            .ok_or(GatewayError::Capacity)?;
        if end > SERVICE_HANDSHAKE_BYTES || end == self.used {
            return Err(GatewayError::Capacity);
        }
        if !bytes.checked_matches(&self.expected[self.used..end])? {
            return Err(GatewayError::ServiceIdentityMismatch);
        }
        self.used = end;
        self.received
            .push(self.pending.take().ok_or(GatewayError::MissingAdmission)?);
        if self.used == SERVICE_HANDSHAKE_BYTES {
            self.admission = Some(ServiceAdmission(Arc::new(std::mem::take(
                &mut self.received,
            ))));
        }
        Ok(())
    }
    pub(crate) fn target_admission(&self) -> Option<ServiceAdmission> {
        if self.role == Role::Host && !self.target_ready {
            self.admission.clone()
        } else {
            None
        }
    }
    pub(crate) fn target_connected(&mut self) -> Result<(), GatewayError> {
        if Instant::now() >= self.deadline {
            return Err(GatewayError::ServiceTimeout);
        }
        if self.role != Role::Host || self.target_ready {
            return Err(GatewayError::WrongTarget);
        }
        self.admission
            .as_ref()
            .ok_or(GatewayError::MissingAdmission)?
            .validate()?;
        self.target_ready = true;
        Ok(())
    }
    /// Exactly bounded reads cannot consume game bytes following the prelude.
    /// READY is sent only AFTER the immutable local world guard admits us.
    pub(crate) fn poll(
        &mut self,
        bridge: &mut ConnectionBridge,
        stack: &mut TcpStack,
        now: Instant,
    ) -> Result<bool, GatewayError> {
        bridge.grant().validate()?;
        if self.complete {
            return Ok(true);
        }
        if now >= self.deadline {
            return Err(GatewayError::ServiceTimeout);
        }
        for bytes in &self.received {
            bytes.validate()?;
        }
        if let Some(admission) = &self.admission {
            admission.validate()?;
        }
        if (self.role == Role::Proxy || self.target_ready) && self.sent < SERVICE_HANDSHAKE_BYTES {
            self.sent += bridge.write_control(stack, &self.output[self.sent..])?;
        }
        if self.used < SERVICE_HANDSHAKE_BYTES {
            if self.pending.is_none() {
                self.pending = bridge.read_control(stack, SERVICE_HANDSHAKE_BYTES - self.used)?;
            }
            if self.pending.is_some() {
                self.record()?;
            }
        }
        let complete = self.used == SERVICE_HANDSHAKE_BYTES
            && self.sent == SERVICE_HANDSHAKE_BYTES
            && (self.role == Role::Proxy || self.target_ready);
        if complete {
            self.admission
                .as_ref()
                .ok_or(GatewayError::MissingAdmission)?
                .validate()?;
            // Selection is now bound to this immutable flow/world scope. Do not
            // pin the initial crypto key for the lifetime of a long game stream.
            self.admission = None;
            self.complete = true;
        }
        Ok(self.complete)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{test_support as support, LocalKey, LocalTarget, ReceiveWindow};
    use p2wlan_mc_runtime_port::{AdmissionError, ScopeOwner};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;
    use tokio::time::timeout;

    struct Authenticated {
        local: support::Rig,
        _plane: support::Plane,
        world: ScopeOwner,
        bridge: ConnectionBridge,
        stack: TcpStack,
        handshake: ServiceHandshake,
    }
    impl Authenticated {
        async fn new() -> Self {
            let (local, remote) = support::pair().await;
            let mut local_plane = local.plane();
            let mut remote_plane = remote.plane();
            let source = ScopeOwner::new();
            let grant = remote
                .authority
                .peer_grant(&remote.remote.node_id, source.token())
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
            let permit = timeout(Duration::from_secs(2), async {
                loop {
                    if let Some(packet) = local_plane.endpoint.try_recv().unwrap() {
                        break packet.permit().clone();
                    }
                    tokio::task::yield_now().await;
                }
            })
            .await
            .unwrap();
            let world = ScopeOwner::new();
            let grant = local
                .authority
                .peer_grant(&local.remote.node_id, world.token())
                .await
                .unwrap();
            let mut stack = TcpStack::new(local.authority.address(), 1).unwrap();
            let id = stack
                .connect(std::net::SocketAddrV4::new(grant.address(), 25565))
                .unwrap();
            let bridge = ConnectionBridge::new(&mut stack, id, grant).unwrap();
            let identity = ServiceIdentity::generate().unwrap();
            let mut window = ReceiveWindow::new(bridge.grant().clone(), 0).unwrap();
            window.record(0, 40, &permit).unwrap();
            let mut handshake = ServiceHandshake::host(identity, Instant::now());
            handshake.pending = Some(window.seal(identity.frame(REQUEST_MAGIC).to_vec()).unwrap());
            handshake.record().unwrap();
            Self {
                local,
                _plane: local_plane,
                world,
                bridge,
                stack,
                handshake,
            }
        }
    }
    #[tokio::test]
    async fn service_admission_keeps_exact_crypto_and_world_envelopes_until_target_handoff() {
        let mut fixture = Authenticated::new().await;
        let captured = fixture.handshake.target_admission().unwrap();
        fixture.world.revoke();
        assert!(matches!(
            captured.validate(),
            Err(GatewayError::Admission(AdmissionError::ScopeClosed))
        ));
        assert!(matches!(
            fixture.handshake.target_connected(),
            Err(GatewayError::Admission(AdmissionError::ScopeClosed))
        ));
        let mut fixture = Authenticated::new().await;
        let captured = fixture.handshake.target_admission().unwrap();
        fixture
            .local
            .transport
            .remove_session(&fixture.local.remote.node_id)
            .await;
        assert!(matches!(
            captured.validate(),
            Err(GatewayError::Admission(AdmissionError::SessionChanged))
        ));
        assert!(matches!(
            fixture.handshake.target_connected(),
            Err(GatewayError::Admission(AdmissionError::SessionChanged))
        ));
    }
    #[tokio::test]
    async fn total_deadline_is_checked_before_late_target_attachment() {
        let mut fixture = Authenticated::new().await;
        let deadline = fixture.handshake.deadline;
        assert!(matches!(
            fixture
                .handshake
                .poll(&mut fixture.bridge, &mut fixture.stack, deadline),
            Err(GatewayError::ServiceTimeout)
        ));
        assert!(!fixture.handshake.complete);
        fixture.handshake.deadline = Instant::now();
        assert!(matches!(
            fixture.handshake.target_connected(),
            Err(GatewayError::ServiceTimeout)
        ));
    }
    #[tokio::test]
    async fn revocation_during_guard_readiness_cannot_admit_late_game_target() {
        let fixture = Authenticated::new().await;
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let std::net::SocketAddr::V4(address) = listener.local_addr().unwrap() else {
            panic!("IPv4 fixture");
        };
        let key = LocalKey::generate().unwrap();
        let target = LocalTarget::new(address, key.clone()).unwrap();
        let admission = fixture.handshake.target_admission().unwrap();
        let grant = fixture.bridge.grant().clone();
        let task = tokio::spawn(target.connect(grant, admission));
        let (mut socket, _) = timeout(Duration::from_secs(1), listener.accept())
            .await
            .unwrap()
            .unwrap();
        let mut prelude = [0; 40];
        timeout(Duration::from_secs(1), socket.read_exact(&mut prelude))
            .await
            .unwrap()
            .unwrap();
        assert_eq!(prelude, key.prelude());
        fixture.local.room.invalidate();
        socket.write_all(&[1]).await.unwrap();
        let result = timeout(Duration::from_secs(1), task)
            .await
            .unwrap()
            .unwrap();
        assert!(matches!(
            result,
            Err(GatewayError::Admission(AdmissionError::RoomRevoked))
        ));
        let closed = timeout(Duration::from_secs(1), socket.read(&mut [0; 1]))
            .await
            .unwrap();
        assert!(
            matches!(&closed, Ok(0))
                || matches!(closed, Err(ref error)
            if error.kind() == std::io::ErrorKind::ConnectionReset)
        );
    }

    #[test]
    fn identity_codec_is_exact_and_distinct_from_local_capabilities() {
        let identity = ServiceIdentity::generate().unwrap();
        assert!(ServiceIdentity::from_hex(&identity.opaque()).unwrap() == identity);
        for text in [
            "",
            "f",
            &"0".repeat(63),
            &"0".repeat(65),
            &"A".repeat(64),
            &"é".repeat(32),
        ] {
            assert!(matches!(
                ServiceIdentity::from_hex(text),
                Err(GatewayError::InvalidServiceIdentity)
            ));
        }
        assert_ne!(&identity.frame(REQUEST_MAGIC)[..8], b"P2MC\0\0\0\x01");
        assert_ne!(identity.frame(REQUEST_MAGIC), identity.frame(READY_MAGIC));
    }
    #[test]
    fn fragmented_remote_identity_is_bounded_and_cannot_consume_game_bytes() {
        let identity = ServiceIdentity::generate().unwrap();
        let frame = identity.frame(REQUEST_MAGIC);
        let mut handshake = ServiceHandshake::host(identity, Instant::now());
        for byte in frame {
            handshake.pending = Some(ReceivedBytes::new(vec![byte], vec![]));
            handshake.record().unwrap();
        }
        assert_eq!(handshake.used, 40);
        assert_eq!(handshake.admission.as_ref().unwrap().0.len(), 40);
        handshake.pending = Some(ReceivedBytes::new(vec![1], vec![]));
        assert!(matches!(handshake.record(), Err(GatewayError::Capacity)));
        let mut wrong = ServiceHandshake::host(identity, Instant::now());
        let other = ServiceIdentity::generate().unwrap().frame(REQUEST_MAGIC);
        wrong.pending = Some(ReceivedBytes::new(other.to_vec(), vec![]));
        assert!(matches!(
            wrong.record(),
            Err(GatewayError::ServiceIdentityMismatch)
        ));
        assert!(wrong.target_admission().is_none());
    }
}
