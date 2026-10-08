//! Actual local socket -> scoped bridge -> user-space TCP -> original encrypted
//! UDP -> scoped bridge -> local socket, with bidirectional transfer and EOF.
#[allow(dead_code)]
#[path = "../../runtime-port/tests/authorized/support.rs"]
mod support;
use p2pnet_daemon::dataplane::OutboundPacket;
use p2pnet_daemon::transport::WireGuardTransport;
use p2pnet_daemon::udp::UdpTransport;
use p2wlan_mc_bridge::TcpStack;
use p2wlan_mc_gateway::{ConnectionBridge, GatewayError};
use p2wlan_mc_runtime_port::{AdmissionError, FencedPacketEndpoint, ScopeOwner};
use std::net::SocketAddrV4;
use std::time::{Duration, Instant};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::mpsc;
use tokio::task::JoinSet;
use tokio::time::timeout;

async fn send(
    transport: WireGuardTransport,
    mut packets: mpsc::Receiver<OutboundPacket>,
    udp: UdpTransport,
    target: std::net::SocketAddr,
) {
    while let Some(packet) = packets.recv().await {
        let encrypted = transport.encrypt_outbound(packet).await.unwrap().unwrap();
        udp.send_packet_to(&encrypted, target).await.unwrap();
    }
}
async fn network(
    local: &support::Rig,
    plane: &mut support::Plane,
    udp: UdpTransport,
    remote: std::net::SocketAddr,
    tasks: &mut JoinSet<()>,
) {
    let (sender, receiver) = mpsc::channel(128);
    let transport = local.transport.clone();
    let inbound = plane.inbound.clone();
    let peers = local.peers.clone();
    let gate = plane.gate.clone();
    let socket = udp.clone();
    tasks.spawn(async move {
        socket.run_inbound(sender).await.unwrap();
    });
    let socket = udp.clone();
    tasks.spawn(async move {
        transport
            .run_inbound_with_application_port(receiver, inbound, peers, socket, gate)
            .await
            .unwrap();
    });
    let (_, placeholder) = mpsc::channel(1);
    let packets = std::mem::replace(&mut plane.outbound, placeholder);
    tasks.spawn(send(local.transport.clone(), packets, udp, remote));
}
async fn sockets() -> (TcpStream, TcpStream) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let left = TcpStream::connect(listener.local_addr().unwrap())
        .await
        .unwrap();
    let (right, _) = listener.accept().await.unwrap();
    (left, right)
}
async fn drive(
    stack: &mut TcpStack,
    bridge: &mut ConnectionBridge,
    endpoint: &mut FencedPacketEndpoint,
    elapsed: Duration,
) {
    while let Some(packet) = endpoint.try_recv().unwrap() {
        bridge.receive_packet(stack, packet, elapsed).unwrap();
    }
    bridge.step(stack).unwrap();
    stack.poll(elapsed);
    while let Some(packet) = stack.take_packet() {
        let packet = endpoint.make_packet(bridge.grant(), packet).unwrap();
        endpoint.try_send(packet).unwrap();
    }
}

#[tokio::test]
async fn scoped_connection_proxy_carries_real_encrypted_tcp_and_preserves_half_close() {
    timeout(Duration::from_secs(20), async {
        let (left, right) = support::pair().await;
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
        let mut left_plane = left.plane();
        let mut right_plane = right.plane();
        let left_udp = UdpTransport::bind("127.0.0.1:0".parse().unwrap(), left.peers.clone())
            .await
            .unwrap()
            .with_wireguard_transport(left.transport.clone());
        let right_udp = UdpTransport::bind("127.0.0.1:0".parse().unwrap(), right.peers.clone())
            .await
            .unwrap()
            .with_wireguard_transport(right.transport.clone());
        let (_, socket) = left_udp.socket_for_peer(None).await.unwrap();
        let a = socket.local_addr().unwrap();
        let (_, socket) = right_udp.socket_for_peer(None).await.unwrap();
        let b = socket.local_addr().unwrap();
        let mut tasks = JoinSet::new();
        network(&left, &mut left_plane, left_udp, b, &mut tasks).await;
        network(&right, &mut right_plane, right_udp, a, &mut tasks).await;
        let mut client = TcpStack::new(left.authority.address(), 1).unwrap();
        let mut server = TcpStack::new(right.authority.address(), 2).unwrap();
        let server_id = server.listen(25565).unwrap();
        let client_id = client
            .connect(SocketAddrV4::new(server.address(), 25565))
            .unwrap();
        let mut client_bridge = ConnectionBridge::new(&mut client, client_id, left_grant).unwrap();
        let mut server_bridge = ConnectionBridge::new(&mut server, server_id, right_grant).unwrap();
        let (mut user, client_socket) = sockets().await;
        let (server_socket, mut echo) = sockets().await;
        client_bridge
            .attach_local(client_socket, &client_bridge.grant().clone())
            .unwrap();
        server_bridge
            .attach_local(server_socket, &server_bridge.grant().clone())
            .unwrap();
        let payload: Vec<u8> = (0..128_000).map(|n| (n % 251) as u8).collect();
        let expected = payload.clone();
        let user_task = tokio::spawn(async move {
            user.write_all(&payload).await.unwrap();
            user.shutdown().await.unwrap();
            let mut received = Vec::new();
            user.take(128_001).read_to_end(&mut received).await.unwrap();
            assert_eq!(received, payload);
        });
        let echo_task = tokio::spawn(async move {
            let mut total = 0;
            let mut bytes = [0; 8192];
            loop {
                let count = echo.read(&mut bytes).await.unwrap();
                if count == 0 {
                    break;
                }
                assert_eq!(bytes[..count], expected[total..total + count]);
                total += count;
                echo.write_all(&bytes[..count]).await.unwrap();
            }
            assert_eq!(total, expected.len());
            echo.shutdown().await.unwrap();
        });
        let started = Instant::now();
        while !user_task.is_finished() || !echo_task.is_finished() {
            assert!(
                tasks.try_join_next().is_none(),
                "network owner exited unexpectedly"
            );
            drive(
                &mut client,
                &mut client_bridge,
                &mut left_plane.endpoint,
                started.elapsed(),
            )
            .await;
            drive(
                &mut server,
                &mut server_bridge,
                &mut right_plane.endpoint,
                started.elapsed(),
            )
            .await;
            tokio::time::sleep(Duration::from_millis(1)).await;
        }
        user_task.await.unwrap();
        echo_task.await.unwrap();
        tasks.abort_all();
        while tasks.join_next().await.is_some() {}
    })
    .await
    .expect("encrypted local TCP proxy must complete within its deadline");
}

#[tokio::test]
async fn delayed_connect_result_cannot_attach_to_a_replacement_or_closed_scope() {
    let (rig, _) = support::pair().await;
    let mut world = ScopeOwner::new();
    let grant = rig
        .authority
        .peer_grant(&rig.remote.node_id, world.token())
        .await
        .unwrap();
    let mut stack = TcpStack::new(rig.authority.address(), 1).unwrap();
    let id = stack
        .connect(SocketAddrV4::new(grant.address(), 25565))
        .unwrap();
    let mut bridge = ConnectionBridge::new(&mut stack, id, grant).unwrap();
    let captured = bridge.grant().clone();
    let (socket, _) = sockets().await;
    world.revoke();
    assert!(matches!(
        bridge.attach_local(socket, &captured),
        Err(GatewayError::Admission(AdmissionError::ScopeClosed))
    ));
    assert!(matches!(
        bridge.step(&mut stack),
        Err(GatewayError::Admission(AdmissionError::ScopeClosed))
    ));
}

#[tokio::test]
async fn attached_local_socket_and_virtual_stack_identity_cannot_be_substituted() {
    let (rig, _) = support::pair().await;
    let world = ScopeOwner::new();
    let grant = rig
        .authority
        .peer_grant(&rig.remote.node_id, world.token())
        .await
        .unwrap();
    let mut stack = TcpStack::new(rig.authority.address(), 1).unwrap();
    let id = stack
        .connect(SocketAddrV4::new(grant.address(), 25565))
        .unwrap();
    let mut bridge = ConnectionBridge::new(&mut stack, id, grant.clone()).unwrap();
    let captured = bridge.grant().clone();
    let (socket, _) = sockets().await;
    bridge.attach_local(socket, &captured).unwrap();
    let (socket, _) = sockets().await;
    assert!(matches!(
        bridge.attach_local(socket, &captured),
        Err(GatewayError::WrongTarget)
    ));
    let mut other = TcpStack::new(rig.authority.address(), 1).unwrap();
    other
        .connect(SocketAddrV4::new(grant.address(), 25565))
        .unwrap();
    assert!(matches!(
        bridge.step(&mut other),
        Err(GatewayError::Stack(
            p2wlan_mc_bridge::StackError::StaleConnection
        ))
    ));
}
