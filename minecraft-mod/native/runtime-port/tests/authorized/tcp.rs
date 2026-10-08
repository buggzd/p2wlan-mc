use super::support::pair;
use p2pnet_daemon::dataplane::OutboundPacket;
use p2pnet_daemon::transport::WireGuardTransport;
use p2pnet_daemon::udp::UdpTransport;
use p2wlan_mc_bridge::{ReadResult, StackError, TcpStack};
use p2wlan_mc_runtime_port::{FencedPacketEndpoint, PeerGrant, ScopeOwner};
use std::net::SocketAddrV4;
use std::time::{Duration, Instant};
use tokio::sync::mpsc;
use tokio::time::timeout;

async fn wire(
    transport: WireGuardTransport,
    mut outbound: mpsc::Receiver<OutboundPacket>,
    udp: UdpTransport,
    peer: std::net::SocketAddr,
) {
    while let Some(packet) = outbound.recv().await {
        let encrypted = transport.encrypt_outbound(packet).await.unwrap().unwrap();
        udp.send_packet_to(&encrypted, peer).await.unwrap();
    }
}

async fn drive(stack: &mut TcpStack, endpoint: &mut FencedPacketEndpoint, grant: &PeerGrant) {
    while let Some(packet) = endpoint.try_recv().unwrap() {
        stack.ingest(packet.into_bytes().unwrap()).unwrap();
    }
    while let Some(packet) = stack.take_packet() {
        let packet = endpoint.make_packet(grant, packet).unwrap();
        endpoint.try_send(packet).unwrap();
    }
}

#[tokio::test]
async fn authorized_upstream_udp_carries_real_bidirectional_tcp() {
    timeout(Duration::from_secs(20), async {
        let (left, right) = pair().await;
        let left_scope = ScopeOwner::new();
        let right_scope = ScopeOwner::new();
        let left_grant = left
            .authority
            .peer_grant(&left.remote.node_id, left_scope.token())
            .await
            .unwrap();
        let right_grant = right
            .authority
            .peer_grant(&right.remote.node_id, right_scope.token())
            .await
            .unwrap();
        let mut client_plane = left.plane();
        let mut server_plane = right.plane();
        let left_udp = UdpTransport::bind("127.0.0.1:0".parse().unwrap(), left.peers.clone())
            .await
            .unwrap()
            .with_wireguard_transport(left.transport.clone());
        let right_udp = UdpTransport::bind("127.0.0.1:0".parse().unwrap(), right.peers.clone())
            .await
            .unwrap()
            .with_wireguard_transport(right.transport.clone());
        let (_, a_socket) = left_udp.socket_for_peer(None).await.unwrap();
        let (_, b_socket) = right_udp.socket_for_peer(None).await.unwrap();
        let a_endpoint = a_socket.local_addr().unwrap();
        let b_endpoint = b_socket.local_addr().unwrap();
        let (left_encrypted_tx, left_encrypted_rx) = mpsc::channel(128);
        let (right_encrypted_tx, right_encrypted_rx) = mpsc::channel(128);
        let mut tasks = tokio::task::JoinSet::new();
        let udp = left_udp.clone();
        tasks.spawn(async move {
            udp.run_inbound(left_encrypted_tx).await.unwrap();
        });
        let udp = right_udp.clone();
        tasks.spawn(async move {
            udp.run_inbound(right_encrypted_tx).await.unwrap();
        });
        let transport = left.transport.clone();
        let sender = client_plane.inbound.clone();
        let peers = left.peers.clone();
        let udp = left_udp.clone();
        let gate = client_plane.gate.clone();
        tasks.spawn(async move {
            transport
                .run_inbound_with_application_port(left_encrypted_rx, sender, peers, udp, gate)
                .await
                .unwrap();
        });
        let transport = right.transport.clone();
        let sender = server_plane.inbound.clone();
        let peers = right.peers.clone();
        let udp = right_udp.clone();
        let gate = server_plane.gate.clone();
        tasks.spawn(async move {
            transport
                .run_inbound_with_application_port(right_encrypted_rx, sender, peers, udp, gate)
                .await
                .unwrap();
        });
        let (_, placeholder) = mpsc::channel(1);
        let outgoing = std::mem::replace(&mut client_plane.outbound, placeholder);
        tasks.spawn(wire(left.transport.clone(), outgoing, left_udp, b_endpoint));
        let (_, placeholder) = mpsc::channel(1);
        let outgoing = std::mem::replace(&mut server_plane.outbound, placeholder);
        tasks.spawn(wire(
            right.transport.clone(),
            outgoing,
            right_udp,
            a_endpoint,
        ));
        let mut client = TcpStack::new(left.authority.address(), 1).unwrap();
        let mut server = TcpStack::new(right.authority.address(), 2).unwrap();
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
            drive(&mut client, &mut client_plane.endpoint, &left_grant).await;
            drive(&mut server, &mut server_plane.endpoint, &right_grant).await;
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
