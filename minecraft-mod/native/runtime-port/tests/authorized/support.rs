use std::net::Ipv4Addr;
use std::sync::Arc;
use std::time::{Duration, Instant};

use p2pnet_crypto::NodeIdentity;
use p2pnet_daemon::control::{PeerCapabilities, PeerInfo};
use p2pnet_daemon::dataplane::{DataPlane, InboundPacket, OutboundPacket};
use p2pnet_daemon::peer::PeerManager;
use p2pnet_daemon::rooms::RoomAuthorization;
use p2pnet_daemon::transport::WireGuardTransport;
use p2pnet_tun::Ipv4Packet;
use p2pnet_wireguard::{HandshakeInitiator, HandshakeResponder, TransportSession};
use p2wlan_mc_runtime_port::{
    ApplicationAuthority, Config, FencedPacketEndpoint, PacketContext, PacketGate, PacketInterface,
    PeerGrant,
};
use tokio::sync::{mpsc, watch};
use tokio::task::JoinHandle;
use tokio::time::timeout;

pub struct Rig {
    pub config: Config,
    pub remote: PeerInfo,
    pub identity: NodeIdentity,
    pub peers: Arc<PeerManager>,
    pub room: Arc<RoomAuthorization>,
    pub transport: WireGuardTransport,
    pub authority: ApplicationAuthority,
    pub shutdown: watch::Sender<bool>,
}

pub fn sessions(left: &NodeIdentity, right: &NodeIdentity) -> (TransportSession, TransportSession) {
    let mut initiator = HandshakeInitiator::new(left.clone(), right.public_key(), None);
    let mut responder = HandshakeResponder::new(right.clone(), None);
    let init = initiator.create_initiation().unwrap();
    let (response, right_keys) = responder.consume_initiation_and_respond(&init).unwrap();
    let left_keys = initiator.consume_response(&response).unwrap();
    (
        TransportSession::new(left_keys),
        TransportSession::new(right_keys),
    )
}

pub async fn pair() -> (Rig, Rig) {
    let left = NodeIdentity::generate();
    let right = NodeIdentity::generate();
    let (left_keys, right_keys) = sessions(&left, &right);
    (
        Rig::new(left.clone(), &right, 1, 2, left_keys).await,
        Rig::new(right, &left, 2, 1, right_keys).await,
    )
}

impl Rig {
    async fn new(
        identity: NodeIdentity,
        remote: &NodeIdentity,
        octet: u8,
        remote_octet: u8,
        session: TransportSession,
    ) -> Self {
        let mut config =
            Config::generate_default("https://control.example.com", "room-test").unwrap();
        config.network.manual = true;
        config.network.mtu = 1280;
        config.network.virtual_ip = Ipv4Addr::new(10, 21, 7, octet).to_string();
        config.network.cidr = "10.21.7.0/24".into();
        config.node.node_id = identity.node_id().into();
        config.node.public_key = hex::encode(identity.public_key());
        config.node.private_key = hex::encode(identity.private_key());
        config.node.device_name = "test-endpoint".into();
        let remote = PeerInfo {
            node_id: remote.node_id().into(),
            public_key: hex::encode(remote.public_key()),
            virtual_ip: Ipv4Addr::new(10, 21, 7, remote_octet).to_string(),
            online: true,
            device_name: "test-peer".into(),
            app_version: String::new(),
            capabilities: PeerCapabilities::default(),
            registration_seq: 1,
            endpoint: String::new(),
            nat_type: String::new(),
            last_seen: 0,
            relay_rtt_ms: None,
        };
        let peers = Arc::new(PeerManager::new(config.clone()));
        peers.add_peer(&remote).await;
        let room = Arc::new(RoomAuthorization::new(&config.network.network_id));
        assert!(room.replace(
            &config.network.virtual_ip,
            [(remote.node_id.clone(), remote.virtual_ip.clone())],
            Instant::now(),
            30
        ));
        let (transport, _) = WireGuardTransport::new();
        assert!(!transport.add_session(&remote.node_id, session).await);
        let (shutdown, shutdown_rx) = watch::channel(false);
        let authority = ApplicationAuthority::new(
            &config,
            peers.clone(),
            room.clone(),
            transport.clone(),
            shutdown_rx,
        )
        .unwrap();
        Self {
            config,
            remote,
            identity,
            peers,
            room,
            transport,
            authority,
            shutdown,
        }
    }
    pub fn renew(&self, started: Instant, seconds: u64) {
        assert!(self.room.replace(
            &self.config.network.virtual_ip,
            [(self.remote.node_id.clone(), self.remote.virtual_ip.clone())],
            started,
            seconds
        ));
    }
    pub fn interface(&self) -> (PacketInterface, FencedPacketEndpoint) {
        PacketInterface::authorized_pair(PacketContext {
            config: self.config.clone(),
            authority: self.authority.clone(),
        })
        .unwrap()
    }
    pub fn plane(&self) -> Plane {
        let (interface, endpoint) = self.interface();
        let gate = interface.application_gate().unwrap();
        let (plane, outbound, inbound) =
            DataPlane::new_bidirectional(interface, self.peers.clone());
        let mut plane = plane
            .with_overlay_cidr(&self.config.network.cidr)
            .with_room_authorization(self.room.clone())
            .with_application_port(Some(gate.clone()));
        let task = tokio::spawn(async move { plane.run().await });
        Plane {
            gate,
            endpoint,
            outbound,
            inbound,
            task,
        }
    }
    pub fn echo(&self) -> Vec<u8> {
        Ipv4Packet::build_icmp_echo_request(
            self.authority.address(),
            self.remote.virtual_ip.parse().unwrap(),
            1,
            1,
            b"admission",
        )
    }
}

pub struct Plane {
    pub gate: Arc<PacketGate>,
    pub endpoint: FencedPacketEndpoint,
    pub outbound: mpsc::Receiver<OutboundPacket>,
    pub inbound: mpsc::Sender<InboundPacket>,
    pub task: JoinHandle<p2pnet_daemon::Result<()>>,
}
impl Drop for Plane {
    fn drop(&mut self) {
        self.task.abort();
    }
}
impl Plane {
    pub async fn route(&mut self, grant: &PeerGrant, bytes: Vec<u8>) -> OutboundPacket {
        let packet = self.endpoint.make_packet(grant, bytes).unwrap();
        self.endpoint.try_send(packet).unwrap();
        timeout(Duration::from_secs(2), self.outbound.recv())
            .await
            .unwrap()
            .unwrap()
    }
}
