"""Exact build-only adaptations of application handoffs, not network owners."""
from source_contract import ContractError, replace_once


def adapt_rooms(source):
    # Per-member lifetime identities live under the original authorization
    # owner's snapshot lock. Unrelated roster changes must not close every
    # existing TCP connection, while all queued frames keep the original global
    # snapshot fence exactly as before.
    source = replace_once(source, "    peers: HashMap<String, String>,", """    peers: HashMap<String, String>,
    memberships: HashMap<String, Arc<AtomicBool>>,""", "application-member-lifetimes")
    source = replace_once(source, """            if let Some(old) = snapshot.take() {
                old.live.store(false, Ordering::Release);
""", """            if let Some(old) = snapshot.take() {
                for member in old.memberships.values() { member.store(false, Ordering::Release); }
                old.live.store(false, Ordering::Release);
""", "application-member-revocation")
    source = replace_once(source, "        let live = match snapshot.as_ref() {", """        let reusable = snapshot.as_ref().filter(|old|
            old.local_ip == local_ip && old.expires_at > Instant::now()
                && old.live.load(Ordering::Acquire));
        let memberships: HashMap<String, Arc<AtomicBool>> = allowed.iter().map(|(id, ip)| {
            let member = reusable.filter(|old| old.peers.get(id) == Some(ip))
                .and_then(|old| old.memberships.get(id)).cloned()
                .unwrap_or_else(|| Arc::new(AtomicBool::new(true)));
            (id.clone(), member)
        }).collect();
        if let Some(old) = snapshot.as_ref() {
            for (id, member) in &old.memberships {
                if memberships.get(id).is_none_or(|next| !Arc::ptr_eq(member, next)) {
                    member.store(false, Ordering::Release);
                }
            }
        }
        let live = match snapshot.as_ref() {""", "application-member-renewal")
    source = replace_once(source, "            peers: allowed,", "            peers: allowed,\n            memberships,", "application-member-publication")
    source = replace_once(source, "/// A queue entry may never inherit a renewed lease or a later re-authorization.",
        """/// Optional application capability; no network state is owned by this hook.
pub(crate) trait AdditionalSendFence: std::fmt::Debug + Send + Sync {
    fn is_current(&self) -> bool;
}

/// A queue entry may never inherit a renewed lease or a later re-authorization.""", "application-fence-trait")
    source = replace_once(source, """pub struct RoomSendPermit {
    live: Arc<AtomicBool>,
    expires_at: Instant,
}""", """pub struct RoomSendPermit {
    live: Arc<AtomicBool>,
    expires_at: Instant,
    application: Option<Arc<dyn AdditionalSendFence>>,
    member: Arc<AtomicBool>,
}""", "application-fence-slot")
    source = replace_once(source,
        "        Arc::ptr_eq(&self.live, &other.live) && self.expires_at == other.expires_at",
        """        Arc::ptr_eq(&self.live, &other.live) && self.expires_at == other.expires_at
            && Arc::ptr_eq(&self.member, &other.member)
            && match (&self.application, &other.application) {
                (None, None) => true,
                (Some(a), Some(b)) => Arc::ptr_eq(a, b),
                _ => false,
            }""", "application-fence-equality")
    source = replace_once(source, "        self.live.load(Ordering::Acquire) && Instant::now() < self.expires_at",
        """        self.live.load(Ordering::Acquire) && Instant::now() < self.expires_at
            && self.application.as_ref().is_none_or(|fence| fence.is_current())""", "application-fence-final-check")
    source = replace_once(source, "impl RoomSendPermit {", """impl RoomSendPermit {
    pub(crate) fn with_application_fence(mut self, fence: Arc<dyn AdditionalSendFence>) -> Self {
        self.application = Some(fence);
        self
    }
    /// Connection identity only; callers separately check the current lease.
    pub(crate) fn mc_membership_live(&self) -> bool {
        self.member.load(Ordering::Acquire)
    }
    pub(crate) fn mc_same_membership(&self, other: &Self) -> bool {
        Arc::ptr_eq(&self.member, &other.member)
    }
    pub(crate) fn mc_lease_expired(&self) -> bool {
        Instant::now() >= self.expires_at
    }""", "application-membership-identity")
    source = replace_once(source, """        Some(RoomSendPermit {
            live: snapshot.live.clone(),
            expires_at: snapshot.expires_at,
        })""", """        Some(RoomSendPermit {
            live: snapshot.live.clone(),
            expires_at: snapshot.expires_at,
            application: None,
            member: snapshot.memberships.get(peer_id)?.clone(),
        })""", "application-base-permit")
    source = replace_once(source, "impl RoomAuthorization {", """#[path = "mc_roster.rs"]
mod mc_roster;

impl RoomAuthorization {""", "application-room-roster")
    return source


def adapt_dataplane(source):
    source = replace_once(source, "pub struct InboundPacket {", """pub struct InboundPacket {
    /// Immutable admission stamped before post-decrypt awaits and queueing.
    pub(crate) mc_application_permit: Option<Arc<crate::mc_embed::FramePermit>>,""", "application-rx-permit-slot")
    source = replace_once(source, "    room_authorization: Option<Arc<crate::rooms::RoomAuthorization>>,",
        """    room_authorization: Option<Arc<crate::rooms::RoomAuthorization>>,
    application_port: Option<Arc<crate::mc_embed::PacketGate>>,""", "application-port-slot")
    before = "                room_authorization: None,"
    if source.count(before) != 2:
        raise ContractError("upstream_contract_changed:application-port-constructors")
    source = source.replace(before, before + "\n                application_port: None,")
    source = replace_once(source, "    pub fn with_room_authorization(",
        """    pub fn with_application_port(mut self, port: Option<Arc<crate::mc_embed::PacketGate>>) -> Self {
        self.application_port = port;
        self
    }

    pub fn with_room_authorization(""", "application-port-builder")
    signature = """    async fn route_outbound_packet(
        &mut self,
        packet: &[u8],
        tun_read_started: std::time::Instant,
        tun_read_completed: std::time::Instant,
    ) -> Result<()> {"""
    source = replace_once(source, signature, signature + """
        let application_tx = match self.application_port.as_ref() {
            Some(port) => Some(port.take_outbound().map_err(|error| DaemonError::Network(error.to_string()))?),
            None => None,
        };""", "application-tx-capture")
    start = "        let room_authorization = match self\n"
    end = "            None => None,\n        };"
    if source.count(start) != 1:
        raise ContractError("upstream_contract_changed:application-room-capture")
    first = source.index(start)
    last = source.index(end, first) + len(end)
    source = source[:last] + """
        let room_authorization = if let Some(captured) = application_tx {
            let Some(port) = self.application_port.as_ref() else { return Ok(()); };
            let Some(permit) = port.route_permit(captured, &peer_id, &dst_ip) else { return Ok(()); };
            Some(permit)
        } else {
            room_authorization
        };
""" + source[last:]
    feedback = """    async fn write_local_feedback(&mut self, packet: &[u8]) -> Result<()> {
        IpPacket::new(packet).map_err(|error| {
            DaemonError::Network(format!("invalid locally generated MTU feedback: {error}"))
        })?;"""
    source = replace_once(source, feedback, feedback + """
        if let Some(port) = self.application_port.as_ref() {
            // The userspace TCP endpoint has an explicit conservative transmit
            // ceiling, not a kernel PMTU consumer. This is control feedback, not
            // an authenticated received application packet. Reject it visibly
            // without acquiring a new peer/world grant or claiming delivery.
            // Unsupported feedback must not kill the whole network daemon.
            port.record_drop(crate::mc_embed::AdmissionError::UnsupportedLocalFeedback);
            return Ok(());
        }""", "application-local-feedback-policy")
    source = replace_once(source, """        let written = self
            .tun
            .write(&inbound_packet)
            .await
            .map_err(|e| DaemonError::Network(format!("TUN write failed: {e}")))?;""",
        """        let written = if let Some(port) = self.application_port.as_ref() {
            match port.deliver_inbound(&packet, &inbound_packet).await {
                Ok(written) => written,
                Err(reason) => { port.record_drop(reason); return Ok(()); }
            }
        } else {
            self.tun.write(&inbound_packet).await
                .map_err(|e| DaemonError::Network(format!("TUN write failed: {e}")))?
        };""", "application-rx-authenticated-handoff")
    return source


def adapt_transport(source):
    if "mc_application_session_retained_try" in source:
        raise ContractError("upstream_contract_changed:application-transport-helper")
    return replace_once(source, "    pub(in crate::transport) fn try_session_instance_state(", """    /// Read the same session owner at the application handoff. No parallel table.
    pub(crate) fn mc_same_session_owner(&self, other: &Self) -> bool {
        Arc::ptr_eq(&self.sessions, &other.sessions)
    }
    pub(crate) fn mc_application_session_retained_try(&self, peer: &str, instance: u64) -> Option<bool> {
        self.try_session_instance_state(peer, instance).map(|(retained, _)| retained)
    }

    pub(in crate::transport) fn try_session_instance_state(""", "application-transport-helper")


def adapt_decryption(source):
    if "mc_application_permit" in source:
        raise ContractError("upstream_contract_changed:application-decrypt-constructors")
    before = "trace: None,"
    if source.count(before) != 3:
        raise ContractError("upstream_contract_changed:application-decrypt-constructors")
    return source.replace(before, "mc_application_permit: None, " + before)


def adapt_inbound(source):
    # Fail closed if upstream adds/removes a control family. Several probes use
    # valid IPv4/ICMP envelopes, so IP parsing alone cannot classify business.
    controls = """                    if internal_rekey_confirmation
                        || direct_validation.is_some()
                        || dplpmtud.is_some()
                        || relay_probe.is_some()
                        || path_commit.is_some()
                    {
                        continue;
                    }"""
    source = replace_once(source, controls, controls, "application-original-control-families")
    source = replace_once(source, "impl WireGuardTransport {", """impl WireGuardTransport {
    /// Checked application port over the same inbound network/crypto owner.
    pub async fn run_inbound_with_application_port(
        &self,
        encrypted_rx: mpsc::Receiver<ReceivedEncryptedPacket>,
        inbound_tx: mpsc::Sender<InboundPacket>,
        peers: Arc<PeerManager>,
        udp: crate::udp::UdpTransport,
        application_port: Arc<crate::mc_embed::PacketGate>,
    ) -> Result<()> {
        self.run_inbound_with_udp_source(encrypted_rx, inbound_tx, Some(peers),
            InboundUdpTransport::Static(Box::new(Some(udp))), None, Some(application_port)).await
    }
""", "application-static-inbound-entry")
    before = """        evidence: Option<InboundEvidenceFeed>,
    ) -> Result<()> {"""
    if source.count(before) != 2:
        raise ContractError("upstream_contract_changed:application-inbound-signatures")
    source = source.replace(before, """        evidence: Option<InboundEvidenceFeed>,
        application_port: Option<Arc<crate::mc_embed::PacketGate>>,
    ) -> Result<()> {""")
    source = replace_once(source, """            InboundUdpTransport::Static(Box::new(udp)),
            None,
""", """            InboundUdpTransport::Static(Box::new(udp)),
            None,
            None,
""", "application-inbound-static")
    source = replace_once(source, """            InboundUdpTransport::Watch(udp_updates),
            evidence,
""", """            InboundUdpTransport::Watch(udp_updates),
            evidence,
            application_port,
""", "application-inbound-live")
    source = replace_once(source, """            udp_updates,
            None,
""", """            udp_updates,
            None,
            None,
""", "application-inbound-test-default")
    source = replace_once(source, """        while let Some(packet) = encrypted_rx.recv().await {""", """        if let Some(port) = application_port.as_ref() {
            let manager = peers.as_ref().ok_or_else(||
                DaemonError::Network(crate::mc_embed::AdmissionError::ContextMissing.to_string()))?;
            port.validate_upstream(manager, self).map_err(|reason| DaemonError::Network(reason.to_string()))?;
        }
        while let Some(packet) = encrypted_rx.recv().await {""", "application-inbound-upstream-owner")
    source = replace_once(source, """                Ok(Some(mut inbound)) => {
""", """                Ok(Some(mut inbound)) => {
                    // Control payloads are consumed by their original handlers;
                    // only application IP packets acquire this extra capability.
                    if let Some(port) = application_port.as_ref() {
                        // Some upstream probes are valid IPv4 packets too. Their
                        // original crypto/path handlers, not the application IP
                        // gate, own their synthetic addresses and payloads.
                        let control = is_rekey_confirmation_packet(&inbound.packet)
                            || parse_direct_validation_token(&inbound.packet).is_some()
                            || crate::dplpmtud::parse_control_packet(&inbound.packet).is_some()
                            || crate::relay_probe::parse_relay_probe_token(&inbound.packet).is_some()
                            || crate::path_commit::parse_path_commit_token(&inbound.packet).is_some();
                        if !control && p2pnet_tun::IpPacket::new(&inbound.packet).is_ok() {
                            let Some(generation) = packet_network_generation else {
                                port.record_drop(crate::mc_embed::AdmissionError::ContextMissing);
                                continue;
                            };
                            inbound = match port.authorize_inbound(inbound, generation) {
                                Ok(packet) => packet,
                                Err(reason) => { port.record_drop(reason); continue; }
                            };
                        }
                    }
""", "application-rx-capture-before-await")
    source = replace_once(source, """                    inbound_tx.send(inbound).await.map_err(|_| {
                        DaemonError::Network("inbound packet channel closed".to_string())
                    })?;""", """                    if let Some(port) = application_port.as_ref() {
                        if let Err(reason) = port.queue_inbound(&inbound_tx, inbound).await {
                            port.record_drop(reason);
                        }
                    } else {
                        inbound_tx.send(inbound).await.map_err(|_| {
                            DaemonError::Network("inbound packet channel closed".to_string())
                        })?;
                    }""", "application-rx-fenced-queue")
    return source


def adapt_udp_business_mtu(source):
    # Inner game IPv4 does not imply outer UDP IPv4. Resolve the original
    # committed endpoint's socket family; all exact-socket/path checks below
    # remain unchanged. An upstream resolver change must fail the build.
    before = """            let Some((socket_index, socket)) = self.socket_for_peer(Some(&snapshot.peer_id)).await
            else {
                self.dplpmtud
                    .cancel_peer(&snapshot.peer_id, "local_socket_missing", now);
                continue;
            };"""
    after = before.replace("self.socket_for_peer(Some(&snapshot.peer_id)).await",
        "self.socket_for_peer_endpoint(Some(&snapshot.peer_id), Some(remote_endpoint)).await")
    return replace_once(source, before, after, "application-mtu-outer-socket-family")
