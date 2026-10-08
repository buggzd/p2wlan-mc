#[allow(dead_code)]
#[path = "common/mod.rs"]
mod support;
use p2pnet_daemon::udp::UdpTransport;
use p2wlan_mc_bridge::TcpStack;
use p2wlan_mc_gateway::{
    GatewayError, GatewayFailure, LocalKey, LocalTarget, ServiceIdentity, TcpGateway,
    LOCAL_PRELUDE_BYTES, MAX_REGISTRATIONS,
};
use p2wlan_mc_runtime_port::{AdmissionError, ScopeOwner};
use std::net::{Ipv4Addr, SocketAddrV4};
use std::sync::{
    atomic::{AtomicUsize, Ordering},
    Arc,
};
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::task::JoinSet;
use tokio::time::timeout;

fn target(port: u16) -> LocalTarget {
    LocalTarget::new(
        SocketAddrV4::new(Ipv4Addr::LOCALHOST, port),
        LocalKey::generate().unwrap(),
    )
    .unwrap()
}

#[tokio::test]
async fn registry_limits_stale_ids_shutdown_and_world_ports_are_exact() {
    let (rig, _) = support::pair().await;
    let (interface, endpoint) = rig.interface();
    let (other_interface, other_endpoint) = rig.interface();
    let mut gateway = TcpGateway::new(endpoint, 1).unwrap();
    let mut other = TcpGateway::new(other_endpoint, 1).unwrap();
    let mut services = Vec::new();
    for _ in 0..MAX_REGISTRATIONS {
        services.push(gateway.register_host(target(25565)).unwrap());
    }
    assert!(matches!(
        gateway.register_host(target(25565)),
        Err(GatewayError::Capacity)
    ));
    let foreign = other.register_host(target(25565)).unwrap();
    assert!(matches!(
        gateway.stop(foreign.id),
        Err(GatewayError::StaleRegistration)
    ));
    let old = services.remove(0);
    gateway.stop(old.id).unwrap();
    assert!(matches!(
        gateway.stop(old.id),
        Err(GatewayError::StaleRegistration)
    ));
    let new = gateway.register_host(target(25565)).unwrap();
    assert_ne!(old.virtual_address, new.virtual_address);
    assert!(old.identity != new.identity);
    gateway.shutdown();
    assert!(gateway.status().closed);
    assert_eq!(gateway.status().tcp_sockets, 0);
    assert_eq!(gateway.status().registrations, 0);
    assert!(matches!(
        gateway.tick(),
        Err(GatewayError::Admission(AdmissionError::RuntimeClosed))
    ));
    drop((interface, other_interface));
}

#[tokio::test]
async fn unguarded_targets_and_wrong_proxy_keys_fail_before_tcp_or_game_bytes() {
    assert!(matches!(
        LocalTarget::new(
            SocketAddrV4::new(Ipv4Addr::new(192, 0, 2, 1), 25565),
            LocalKey::generate().unwrap()
        ),
        Err(GatewayError::WrongTarget)
    ));
    let (rig, _) = support::pair().await;
    let (_interface, endpoint) = rig.interface();
    let mut gateway = TcpGateway::new(endpoint, 1).unwrap();
    let proxy = gateway
        .register_proxy(
            &rig.remote.node_id,
            25565,
            ServiceIdentity::generate().unwrap(),
        )
        .await
        .unwrap();
    let mut socket = TcpStream::connect(proxy.local_address).await.unwrap();
    socket
        .write_all(&LocalKey::generate().unwrap().prelude())
        .await
        .unwrap();
    for _ in 0..100 {
        gateway.tick().unwrap();
        if gateway.status().last_failure == Some(GatewayFailure::LocalKeyMismatch) {
            break;
        }
        tokio::task::yield_now().await;
    }
    assert_eq!(
        gateway.status().last_failure,
        Some(GatewayFailure::LocalKeyMismatch)
    );
    assert_eq!(gateway.status().connections, 0);
    assert_eq!(gateway.status().tcp_sockets, 0);
    assert_eq!(gateway.status().local_handshakes, 0);
    assert!(gateway
        .failure_counts()
        .contains(&("GATEWAY_LOCAL_KEY_MISMATCH", 1)));
    gateway.stop(proxy.id).unwrap();
    assert!(TcpStream::connect(proxy.local_address).await.is_err());
}

#[tokio::test]
async fn sealed_backpressure_cannot_be_reauthorized_after_proxy_replacement() {
    let (rig, _) = support::pair().await;
    let scope = ScopeOwner::new();
    let grant = rig
        .authority
        .peer_grant(&rig.remote.node_id, scope.token())
        .await
        .unwrap();
    let (_interface, endpoint) = rig.interface();
    let mut packets = TcpStack::new(rig.authority.address(), 7).unwrap();
    packets
        .connect(SocketAddrV4::new(grant.address(), 25565))
        .unwrap();
    packets.poll(Duration::ZERO);
    let bytes = packets.take_packet().unwrap();
    for _ in 0..128 {
        endpoint
            .try_send(endpoint.make_packet(&grant, bytes.clone()).unwrap())
            .unwrap();
    }
    let mut gateway = TcpGateway::new(endpoint, 1).unwrap();
    let proxy = gateway
        .register_proxy(
            &rig.remote.node_id,
            25565,
            ServiceIdentity::generate().unwrap(),
        )
        .await
        .unwrap();
    let mut socket = TcpStream::connect(proxy.local_address).await.unwrap();
    socket.write_all(&proxy.key.prelude()).await.unwrap();
    for _ in 0..100 {
        gateway.tick().unwrap();
        if gateway.status().queued_frames != 0 {
            break;
        }
        tokio::task::yield_now().await;
    }
    assert_eq!(gateway.status().queued_frames, 1);
    assert_eq!(gateway.status().connections, 1);
    gateway.stop(proxy.id).unwrap();
    assert_eq!(gateway.status().queued_frames, 0);
    assert_eq!(gateway.status().connections, 0);
    assert_eq!(
        gateway.status().last_failure,
        Some(GatewayFailure::Admission(AdmissionError::ScopeClosed))
    );
    let replacement = gateway
        .register_proxy(
            &rig.remote.node_id,
            25565,
            ServiceIdentity::generate().unwrap(),
        )
        .await
        .unwrap();
    assert_ne!(proxy.id, replacement.id);
    assert_ne!(proxy.key.prelude(), replacement.key.prelude());
    assert_eq!(gateway.status().connections, 0);
}

#[tokio::test]
async fn service_registry_multiplexes_guarded_local_streams_through_original_encrypted_udp() {
    timeout(Duration::from_secs(15), async {
        let (left, right) = support::pair().await;
        let mut a = left.plane();
        let mut b = right.plane();
        let ua = UdpTransport::bind("127.0.0.1:0".parse().unwrap(), left.peers.clone())
            .await
            .unwrap()
            .with_wireguard_transport(left.transport.clone());
        let ub = UdpTransport::bind("127.0.0.1:0".parse().unwrap(), right.peers.clone())
            .await
            .unwrap()
            .with_wireguard_transport(right.transport.clone());
        let (_, socket) = ua.socket_for_peer(None).await.unwrap();
        let aa = socket.local_addr().unwrap();
        let (_, socket) = ub.socket_for_peer(None).await.unwrap();
        let bb = socket.local_addr().unwrap();
        let mut network = JoinSet::new();
        support::network(&left, &mut a, ua, bb, &mut network).await;
        support::network(&right, &mut b, ub, aa, &mut network).await;
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let std::net::SocketAddr::V4(address) = listener.local_addr().unwrap() else {
            panic!("IPv4 guard required");
        };
        let target_key = LocalKey::generate().unwrap();
        let guard_key = target_key.clone();
        let accepted = Arc::new(AtomicUsize::new(0));
        let target_accepts = accepted.clone();
        let echo = tokio::spawn(async move {
            let mut echoes = JoinSet::new();
            for _ in 0..3 {
                let (mut socket, _) = listener.accept().await.unwrap();
                target_accepts.fetch_add(1, Ordering::SeqCst);
                let key = guard_key.clone();
                echoes.spawn(async move {
                    let mut prelude = [0; LOCAL_PRELUDE_BYTES];
                    socket.read_exact(&mut prelude).await.unwrap();
                    assert_eq!(prelude, key.prelude());
                    socket.write_all(&[1]).await.unwrap();
                    let mut buffer = [0; 8192];
                    loop {
                        let count = socket.read(&mut buffer).await.unwrap();
                        if count == 0 {
                            break;
                        }
                        socket.write_all(&buffer[..count]).await.unwrap();
                    }
                    socket.shutdown().await.unwrap();
                });
            }
            while let Some(result) = echoes.join_next().await {
                result.unwrap();
            }
        });
        let (_unused_a, placeholder_a) = left.interface();
        let (_unused_b, placeholder_b) = right.interface();
        let mut client =
            TcpGateway::new(std::mem::replace(&mut a.endpoint, placeholder_a), 1).unwrap();
        let mut host =
            TcpGateway::new(std::mem::replace(&mut b.endpoint, placeholder_b), 2).unwrap();
        let old_service = host
            .register_host(LocalTarget::new(address, target_key.clone()).unwrap())
            .unwrap();
        host.stop(old_service.id).unwrap();
        let service = host
            .register_host(LocalTarget::new(address, target_key).unwrap())
            .unwrap();
        assert!(old_service.identity != service.identity);
        let ticket = client.discover(&left.remote.node_id).await.unwrap();
        let catalog = timeout(Duration::from_secs(3), async {
            loop {
                client.tick().unwrap();
                host.tick().unwrap();
                if let Some(catalog) = client.poll_discovery(ticket).unwrap() {
                    break catalog;
                }
                tokio::time::sleep(Duration::from_millis(1)).await;
            }
        })
        .await
        .expect("encrypted service discovery must use the existing packet port");
        assert_eq!(catalog.len(), 1);
        assert_eq!(catalog[0].port, service.virtual_address.port());
        assert!(catalog[0].identity == service.identity);
        assert_eq!(client.status().connections, 0);
        assert_eq!(client.status().tcp_sockets, 0);
        assert_eq!(accepted.load(Ordering::SeqCst), 0);
        // A NEW peer grant cannot make old selected-world metadata current.
        // Even when the caller supplies a CURRENT port, identity must match
        // before the gateway opens any local target connection.
        let wrong = client
            .register_proxy(
                &left.remote.node_id,
                service.virtual_address.port(),
                old_service.identity,
            )
            .await
            .unwrap();
        let mut stale = TcpStream::connect(wrong.local_address).await.unwrap();
        stale
            .write_all(&[wrong.key.prelude().as_slice(), b"wrong-world-game"].concat())
            .await
            .unwrap();
        timeout(Duration::from_secs(3), async {
            loop {
                client.tick().unwrap();
                host.tick().unwrap();
                if host.status().last_failure == Some(GatewayFailure::ServiceIdentityMismatch) {
                    break;
                }
                tokio::time::sleep(Duration::from_millis(1)).await;
            }
        })
        .await
        .expect("stale world identity must be rejected without target admission");
        assert_eq!(accepted.load(Ordering::SeqCst), 0);
        client.stop(wrong.id).unwrap();
        drop(stale);
        let proxy = client
            .register_proxy(
                &left.remote.node_id,
                service.virtual_address.port(),
                service.identity,
            )
            .await
            .unwrap();
        let mut users = JoinSet::new();
        for user in 0..3 {
            let mut socket = TcpStream::connect(proxy.local_address).await.unwrap();
            let prelude = proxy.key.prelude();
            users.spawn(async move {
                // Deliberate prelude fragmentation, followed by game bytes in
                // the same final write. The gateway may consume only 40 bytes.
                socket.write_all(&prelude[..7]).await.unwrap();
                let payload: Vec<_> = (0..64_000).map(|n| ((n + user * 3) % 251) as u8).collect();
                let mut first = prelude[7..].to_vec();
                first.extend_from_slice(&payload[..1024]);
                socket.write_all(&first).await.unwrap();
                socket.write_all(&payload[1024..]).await.unwrap();
                socket.shutdown().await.unwrap();
                let mut result = Vec::new();
                socket.take(64_001).read_to_end(&mut result).await.unwrap();
                assert_eq!(result, payload);
            });
        }
        while !users.is_empty() || !echo.is_finished() {
            client.tick().unwrap();
            host.tick().unwrap();
            while let Some(result) = users.try_join_next() {
                result.unwrap();
            }
            assert!(network.try_join_next().is_none());
            tokio::time::sleep(Duration::from_millis(1)).await;
        }
        echo.await.unwrap();
        assert_eq!(accepted.load(Ordering::SeqCst), 3);
        client.stop(proxy.id).unwrap();
        host.stop(service.id).unwrap();
        assert_eq!(client.status().connections, 0);
        assert_eq!(host.status().connections, 0);
        network.abort_all();
        while network.join_next().await.is_some() {}
    })
    .await
    .expect("multiplexed guarded gateway must finish within deadline");
}

#[tokio::test]
async fn peer_revocation_closes_proxy_and_port_owner_loss_cancels_gateway() {
    let (rig, _) = support::pair().await;
    let (interface, endpoint) = rig.interface();
    let mut gateway = TcpGateway::new(endpoint, 1).unwrap();
    let proxy = gateway
        .register_proxy(
            &rig.remote.node_id,
            25565,
            ServiceIdentity::generate().unwrap(),
        )
        .await
        .unwrap();
    rig.peers.remove_peer(&rig.remote.node_id).await;
    gateway.tick().unwrap();
    assert_eq!(gateway.status().registrations, 0);
    assert!(TcpStream::connect(proxy.local_address).await.is_err());
    drop(interface);
    assert!(matches!(
        gateway.tick(),
        Err(GatewayError::Admission(AdmissionError::PortClosed))
    ));
    assert!(gateway.status().closed);
    assert_eq!(gateway.status().tcp_sockets, 0);
}

#[tokio::test]
async fn partial_local_handshakes_are_bounded_and_stop_closes_exactly_their_sockets() {
    let (rig, _) = support::pair().await;
    let (_interface, endpoint) = rig.interface();
    let mut gateway = TcpGateway::new(endpoint, 1).unwrap();
    let proxy = gateway
        .register_proxy(
            &rig.remote.node_id,
            25565,
            ServiceIdentity::generate().unwrap(),
        )
        .await
        .unwrap();
    let mut sockets = Vec::new();
    for _ in 0..p2wlan_mc_bridge::MAX_CONNECTIONS {
        sockets.push(TcpStream::connect(proxy.local_address).await.unwrap());
        gateway.tick().unwrap();
    }
    for _ in 0..100 {
        gateway.tick().unwrap();
        if gateway.status().local_handshakes == p2wlan_mc_bridge::MAX_CONNECTIONS {
            break;
        }
        tokio::task::yield_now().await;
    }
    assert_eq!(gateway.status().local_handshakes, 32);
    assert_eq!(gateway.status().connections, 0);
    let mut extra = TcpStream::connect(proxy.local_address).await.unwrap();
    extra.write_all(&proxy.key.prelude()).await.unwrap();
    gateway.tick().unwrap();
    assert_eq!(gateway.status().local_handshakes, 32);
    assert_eq!(gateway.status().connections, 0);
    gateway.stop(proxy.id).unwrap();
    assert_eq!(gateway.status().local_handshakes, 0);
    timeout(Duration::from_secs(2), async {
        for mut socket in sockets {
            assert_eq!(socket.read(&mut [0; 1]).await.unwrap(), 0);
        }
    })
    .await
    .expect("stopping the registration must close accepted handshake sockets");
}

#[tokio::test]
async fn borrowed_supervisor_scope_revocation_closes_all_gateway_work() {
    let (rig, _) = support::pair().await;
    let (_interface, endpoint) = rig.interface();
    let mut supervisor = ScopeOwner::new();
    let mut gateway = TcpGateway::with_lifetime(endpoint, 1, supervisor.token()).unwrap();
    let service = gateway.register_host(target(25565)).unwrap();
    assert!(gateway.contains_registration(service.id));
    supervisor.revoke();
    assert!(!gateway.contains_registration(service.id));
    assert!(matches!(
        gateway.tick(),
        Err(GatewayError::Admission(AdmissionError::RuntimeClosed))
    ));
    assert!(gateway.status().closed);
    assert_eq!(gateway.status().tcp_sockets, 0);
}
