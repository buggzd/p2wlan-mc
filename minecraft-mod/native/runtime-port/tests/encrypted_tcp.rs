//! Actual user-space TCP -> upstream dataplane -> encrypted loopback UDP ->
//! upstream decrypt/dataplane -> TCP. No OS TUN, fake echo, or direct byte pipe.
use std::net::{Ipv4Addr, SocketAddrV4};
use std::sync::Arc;
use std::time::{Duration, Instant};

use p2pnet_crypto::NodeIdentity;
use p2pnet_daemon::control::{PeerCapabilities, PeerInfo};
use p2pnet_daemon::dataplane::{DataPlane, InboundPacket, OutboundPacket};
use p2pnet_daemon::peer::PeerManager;
use p2pnet_daemon::transport::WireGuardTransport;
use p2pnet_wireguard::{HandshakeInitiator, HandshakeResponder, TransportSession};
use p2wlan_mc_bridge::{ReadResult, StackError, TcpStack};
use p2wlan_mc_runtime_port::{Config, PacketEndpoint, PacketInterface};
use tokio::net::UdpSocket;
use tokio::sync::mpsc;
use tokio::task::JoinSet;
use tokio::time::timeout;

fn config(identity: &NodeIdentity, address: Ipv4Addr) -> Config {
    let mut config = Config::generate_default("", "isolated-test").unwrap();
    config.network.manual = true;
    config.network.mtu = 1280;
    config.network.virtual_ip = address.to_string();
    config.network.cidr = "10.221.0.0/24".into();
    config.node.node_id = identity.node_id().into();
    config.node.public_key = hex::encode(identity.public_key());
    config.node.private_key = hex::encode(identity.private_key());
    config.node.device_name = "test-endpoint".into();
    config
}

async fn plane(
    local: &Config,
    remote: &Config,
    tasks: &mut JoinSet<()>,
) -> (
    PacketEndpoint,
    mpsc::Receiver<OutboundPacket>,
    mpsc::Sender<InboundPacket>,
) {
    let peers = Arc::new(PeerManager::new(local.clone()));
    peers
        .add_peer(&PeerInfo {
            node_id: remote.node.node_id.clone(),
            device_name: "test-peer".into(),
            app_version: String::new(),
            capabilities: PeerCapabilities::default(),
            registration_seq: 0,
            public_key: remote.node.public_key.clone(),
            endpoint: String::new(),
            nat_type: String::new(),
            virtual_ip: remote.network.virtual_ip.clone(),
            online: true,
            last_seen: 0,
            relay_rtt_ms: None,
        })
        .await;
    let (interface, endpoint) = PacketInterface::pair(local).unwrap();
    let (plane, outbound, inbound) = DataPlane::new_bidirectional(interface, peers);
    let mut plane = plane.with_overlay_cidr(&local.network.cidr);
    tasks.spawn(async move {
        // Endpoint/channel closure is expected teardown. No privileged device.
        let _ = plane.run().await;
    });
    (endpoint, outbound, inbound)
}

async fn wire(
    transport: WireGuardTransport,
    mut outbound: mpsc::Receiver<OutboundPacket>,
    inbound: mpsc::Sender<InboundPacket>,
    socket: UdpSocket,
    peer: std::net::SocketAddr,
) {
    let mut buffer = [0; 2048];
    loop {
        tokio::select! {
            packet = outbound.recv() => {
                let Some(packet) = packet else { return; };
                let encrypted = transport.encrypt_outbound(packet).await.unwrap().unwrap();
                socket.send_to(&encrypted.wire_bytes, peer).await.unwrap();
            }
            received = socket.recv_from(&mut buffer) => {
                let (length, source) = received.unwrap();
                assert_eq!(source, peer);
                let packet = transport.decrypt_inbound(&buffer[..length]).await.unwrap().unwrap();
                inbound.send(packet).await.unwrap();
            }
        }
    }
}

async fn drive(stack: &mut TcpStack, endpoint: &mut PacketEndpoint) {
    while let Ok(packet) = endpoint.incoming.try_recv() {
        stack.ingest(packet).unwrap();
    }
    while let Some(packet) = stack.take_packet() {
        timeout(Duration::from_secs(1), endpoint.outgoing.send(packet))
            .await
            .unwrap()
            .unwrap();
    }
}

#[tokio::test]
async fn encrypted_udp_carries_real_tcp_stream_through_upstream_dataplanes() {
    timeout(Duration::from_secs(20), async {
        let alice = NodeIdentity::generate();
        let bob = NodeIdentity::generate();
        let mut initiator = HandshakeInitiator::new(alice.clone(), bob.public_key(), None);
        let mut responder = HandshakeResponder::new(bob.clone(), None);
        let initiation = initiator.create_initiation().unwrap();
        let (response, bob_keys) = responder
            .consume_initiation_and_respond(&initiation)
            .unwrap();
        let alice_keys = initiator.consume_response(&response).unwrap();
        let (client_transport, _) = WireGuardTransport::new();
        let (server_transport, _) = WireGuardTransport::new();
        // add_session reports replacement, not insertion success.
        assert!(
            !client_transport
                .add_session(bob.node_id(), TransportSession::new(alice_keys))
                .await
        );
        assert!(
            !server_transport
                .add_session(alice.node_id(), TransportSession::new(bob_keys))
                .await
        );
        assert!(client_transport.has_session(bob.node_id()).await);
        assert!(server_transport.has_session(alice.node_id()).await);
        let a = config(&alice, Ipv4Addr::new(10, 221, 0, 1));
        let b = config(&bob, Ipv4Addr::new(10, 221, 0, 2));
        let mut tasks = JoinSet::new();
        let (mut client_port, client_out, client_in) = plane(&a, &b, &mut tasks).await;
        let (mut server_port, server_out, server_in) = plane(&b, &a, &mut tasks).await;
        let a_socket = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let b_socket = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let a_endpoint = a_socket.local_addr().unwrap();
        let b_endpoint = b_socket.local_addr().unwrap();
        tasks.spawn(wire(
            client_transport,
            client_out,
            client_in,
            a_socket,
            b_endpoint,
        ));
        tasks.spawn(wire(
            server_transport,
            server_out,
            server_in,
            b_socket,
            a_endpoint,
        ));
        let mut client = TcpStack::new(a.network.virtual_ip.parse().unwrap(), 1).unwrap();
        let mut server = TcpStack::new(b.network.virtual_ip.parse().unwrap(), 2).unwrap();
        let server_id = server.listen(25565).unwrap();
        let client_id = client
            .connect(SocketAddrV4::new(server.address(), 25565))
            .unwrap();
        let payload: Vec<u8> = (0..128_000).map(|n| (n % 251) as u8).collect();
        let mut sent = 0;
        let mut received = Vec::new();
        let mut echoed = Vec::new();
        let mut echo_queue = Vec::new();
        let started = Instant::now();
        while echoed.len() < payload.len() {
            assert!(
                tasks.is_empty() || tasks.try_join_next().is_none(),
                "critical wire/dataplane exited"
            );
            client.poll(started.elapsed());
            server.poll(started.elapsed());
            drive(&mut client, &mut client_port).await;
            drive(&mut server, &mut server_port).await;
            if sent < payload.len() {
                match client.write(client_id, &payload[sent..]) {
                    Ok(count) => sent += count,
                    Err(StackError::WouldBlock) => {}
                    error => panic!("unexpected write {error:?}"),
                }
            }
            let mut buffer = [0; 8192];
            if let ReadResult::Data(count) = server.read(server_id, &mut buffer).unwrap() {
                received.extend_from_slice(&buffer[..count]);
                echo_queue.extend_from_slice(&buffer[..count]);
            }
            if !echo_queue.is_empty() {
                match server.write(server_id, &echo_queue) {
                    Ok(count) => {
                        echo_queue.drain(..count);
                    }
                    Err(StackError::WouldBlock) => {}
                    error => panic!("unexpected echo {error:?}"),
                }
            }
            if let ReadResult::Data(count) = client.read(client_id, &mut buffer).unwrap() {
                echoed.extend_from_slice(&buffer[..count]);
            }
            tokio::time::sleep(Duration::from_millis(1)).await;
        }
        assert_eq!(received, payload);
        assert_eq!(echoed, payload);
        tasks.abort_all();
        while tasks.join_next().await.is_some() {}
    })
    .await
    .expect("encrypted TCP must finish within its deadline");
}
