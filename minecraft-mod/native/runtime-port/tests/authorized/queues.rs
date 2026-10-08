use std::sync::Arc;
use std::time::{Duration, Instant};

use p2pnet_daemon::dataplane::InboundPacket;
use p2pnet_daemon::udp::UdpTransport;
use p2pnet_tun::VirtualInterface;
use p2wlan_mc_runtime_port::{AdmissionError as E, ScopeOwner};
use tokio::net::UdpSocket;
use tokio::time::{sleep, timeout};

use super::support::{pair, sessions, Plane, Rig};

async fn decrypt(local: &Rig, remote: &Rig, remote_plane: &mut Plane) -> InboundPacket {
    let scope = ScopeOwner::new();
    let grant = remote
        .authority
        .peer_grant(&remote.remote.node_id, scope.token())
        .await
        .unwrap();
    let routed = remote_plane.route(&grant, remote.echo()).await;
    let encrypted = remote
        .transport
        .encrypt_outbound(routed)
        .await
        .unwrap()
        .unwrap();
    local
        .transport
        .decrypt_inbound(&encrypted.wire_bytes)
        .await
        .unwrap()
        .unwrap()
}

async fn receive_count(rig: &Rig, count: u64) {
    timeout(Duration::from_secs(2), async {
        loop {
            if rig
                .room
                .diagnostics(&rig.config.network.virtual_ip)
                .unwrap()
                .peers[0]
                .rx_delivered_packets
                >= count
            {
                return;
            }
            sleep(Duration::from_millis(1)).await;
        }
    })
    .await
    .unwrap();
}

async fn dropped(plane: &Plane, reason: E) {
    timeout(Duration::from_secs(2), async {
        while plane.endpoint.drops_by_reason(reason) == 0 {
            assert!(!plane.task.is_finished(), "dataplane exited unexpectedly");
            sleep(Duration::from_millis(1)).await;
        }
    })
    .await
    .unwrap();
}

#[tokio::test]
async fn backpressure_retains_original_frame_and_revocation_drops_exactly_the_backlog() {
    let (rig, _) = pair().await;
    let mut scope = ScopeOwner::new();
    let grant = rig
        .authority
        .peer_grant(&rig.remote.node_id, scope.token())
        .await
        .unwrap();
    let (mut interface, endpoint) = rig.interface();
    for _ in 0..128 {
        endpoint
            .try_send(endpoint.make_packet(&grant, rig.echo()).unwrap())
            .unwrap();
    }
    let extra = endpoint.make_packet(&grant, rig.echo()).unwrap();
    let identity = extra.permit().clone();
    let (reason, extra) = endpoint.try_send(extra).unwrap_err();
    assert_eq!(reason, E::QueueFull);
    assert_eq!(*extra.permit(), identity);
    scope.revoke();
    assert_eq!(endpoint.try_send(extra).unwrap_err().0, E::ScopeClosed);
    for _ in 0..128 {
        assert_eq!(interface.read(&mut [0; 1280]).await.unwrap(), 0);
    }
    assert_eq!(endpoint.dropped_packets(), 128);
    assert_eq!(endpoint.drops_by_reason(E::ScopeClosed), 128);
}

#[tokio::test]
async fn dropping_interface_revokes_frames_even_when_endpoint_and_gate_are_retained() {
    let (rig, _) = pair().await;
    let scope = ScopeOwner::new();
    let grant = rig
        .authority
        .peer_grant(&rig.remote.node_id, scope.token())
        .await
        .unwrap();
    let (interface, mut endpoint) = rig.interface();
    let gate = interface.application_gate().unwrap();
    let frame = endpoint.make_packet(&grant, rig.echo()).unwrap();
    drop(interface);
    assert_eq!(frame.validate(), Err(E::ScopeClosed));
    assert_eq!(endpoint.try_recv().unwrap_err(), E::PortClosed);
    assert_eq!(Arc::strong_count(&gate), 2);
}

#[tokio::test]
async fn inbound_queue_keeps_original_crypto_instance_and_rejects_unstamped_packets() {
    let (local, remote) = pair().await;
    let mut remote_plane = remote.plane();
    let mut local_plane = local.plane();
    let (interface, _) = local.interface();
    let wrong_gate = interface.application_gate().unwrap();
    // A gate with the same authority but a different port may not stamp work
    // that another DataPlane consumes.
    let packet = wrong_gate
        .authorize_inbound(
            decrypt(&local, &remote, &mut remote_plane).await,
            local.authority.network_generation(),
        )
        .unwrap();
    local_plane.inbound.send(packet).await.unwrap();
    dropped(&local_plane, E::PortMismatch).await;
    let packet = decrypt(&local, &remote, &mut remote_plane).await;
    local_plane.inbound.send(packet).await.unwrap();
    dropped(&local_plane, E::ContextMissing).await;
    assert!(local_plane.endpoint.try_recv().unwrap().is_none());
}

#[tokio::test]
async fn queued_authenticated_frame_cannot_inherit_a_replacement_session() {
    let (local, remote) = pair().await;
    let mut remote_plane = remote.plane();
    let mut local_plane = local.plane();
    let gate = local_plane.gate.clone();
    let packet = gate
        .authorize_inbound(
            decrypt(&local, &remote, &mut remote_plane).await,
            local.authority.network_generation(),
        )
        .unwrap();
    local_plane.inbound.send(packet).await.unwrap();
    receive_count(&local, 1).await;
    let (replacement, _) = sessions(&local.identity, &remote.identity);
    // Replace without overlap: the old authenticated instance must disappear.
    local
        .transport
        .remove_session(remote.identity.node_id())
        .await;
    local
        .transport
        .add_session(remote.identity.node_id(), replacement)
        .await;
    assert_eq!(
        local_plane.endpoint.try_recv().unwrap_err(),
        E::SessionChanged
    );
}

#[tokio::test]
async fn receive_admission_keeps_ingress_network_and_original_membership_before_queues() {
    let (local, remote) = pair().await;
    let mut remote_plane = remote.plane();
    let mut local_plane = local.plane();
    let gate = local_plane.gate.clone();
    let generation = local.authority.network_generation();
    let packet = gate
        .authorize_inbound(
            decrypt(&local, &remote, &mut remote_plane).await,
            generation,
        )
        .unwrap();
    local.room.invalidate();
    local.renew(Instant::now(), 30);
    assert_eq!(
        gate.authorize_inbound(packet.clone(), generation)
            .unwrap_err(),
        E::AlreadyAdmitted
    );
    local_plane.inbound.send(packet).await.unwrap();
    dropped(&local_plane, E::RoomRevoked).await;
    assert!(local_plane.endpoint.try_recv().unwrap().is_none());
    let packet = decrypt(&local, &remote, &mut remote_plane).await;
    local
        .peers
        .advance_network_generation("test-handover")
        .await;
    assert_eq!(
        gate.authorize_inbound(packet, generation).unwrap_err(),
        E::NetworkChanged
    );
}

#[tokio::test]
async fn inbound_admission_needs_the_actual_authenticated_session() {
    let (local, remote) = pair().await;
    let mut remote_plane = remote.plane();
    let local_plane = local.plane();
    let gate = local_plane.gate.clone();
    let mut packet = decrypt(&local, &remote, &mut remote_plane).await;
    packet.session_instance = None;
    assert_eq!(
        gate.authorize_inbound(packet, local.authority.network_generation())
            .unwrap_err(),
        E::SessionMissing
    );
}

#[tokio::test]
async fn inbound_capacity_has_deadline_and_rechecks_a_waiting_original_grant() {
    let (local, remote) = pair().await;
    let mut remote_plane = remote.plane();
    let mut local_plane = local.plane();
    let gate = local_plane.gate.clone();
    for index in 0..129 {
        let packet = gate
            .authorize_inbound(
                decrypt(&local, &remote, &mut remote_plane).await,
                local.authority.network_generation(),
            )
            .unwrap();
        local_plane.inbound.send(packet).await.unwrap();
        if index < 128 {
            receive_count(&local, index + 1).await;
        }
    }
    dropped(&local_plane, E::Timeout).await;
    assert_eq!(local_plane.endpoint.drops_by_reason(E::Timeout), 1);
    let packet = gate
        .authorize_inbound(
            decrypt(&local, &remote, &mut remote_plane).await,
            local.authority.network_generation(),
        )
        .unwrap();
    local_plane.inbound.send(packet).await.unwrap();
    // Make room only after revocation; neither the waiting frame nor the queued
    // frames may acquire the new room membership when capacity returns.
    local.room.invalidate();
    local.renew(Instant::now(), 30);
    assert_eq!(local_plane.endpoint.try_recv().unwrap_err(), E::RoomRevoked);
    dropped(&local_plane, E::RoomRevoked).await;
}

#[derive(Debug)]
enum Revocation {
    Scope,
    Peer,
    Network,
    Room,
    Port,
}

#[tokio::test]
async fn actual_upstream_udp_writer_refuses_revoked_packets_after_routing_and_encryption() {
    for reason in [
        Revocation::Scope,
        Revocation::Peer,
        Revocation::Network,
        Revocation::Room,
        Revocation::Port,
    ] {
        let (rig, remote) = pair().await;
        let mut scope = ScopeOwner::new();
        let grant = rig
            .authority
            .peer_grant(&rig.remote.node_id, scope.token())
            .await
            .unwrap();
        let mut plane = rig.plane();
        let udp = UdpTransport::bind("127.0.0.1:0".parse().unwrap(), rig.peers.clone())
            .await
            .unwrap();
        let receiver = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let endpoint = receiver.local_addr().unwrap();
        let routed = plane.route(&grant, rig.echo()).await;
        let first = rig
            .transport
            .encrypt_outbound(routed)
            .await
            .unwrap()
            .unwrap();
        udp.send_packet_to(&first, endpoint).await.unwrap();
        let mut buffer = [0; 2048];
        let (count, _) = timeout(Duration::from_secs(1), receiver.recv_from(&mut buffer))
            .await
            .unwrap()
            .unwrap();
        assert_eq!(
            remote
                .transport
                .decrypt_inbound(&buffer[..count])
                .await
                .unwrap()
                .unwrap()
                .packet,
            rig.echo()
        );
        let routed = plane.route(&grant, rig.echo()).await;
        let queued = rig
            .transport
            .encrypt_outbound(routed)
            .await
            .unwrap()
            .unwrap();
        match reason {
            Revocation::Scope => scope.revoke(),
            Revocation::Peer => {
                rig.peers.remove_peer(&rig.remote.node_id).await;
                rig.peers.add_peer(&rig.remote).await;
            }
            Revocation::Network => {
                rig.peers.advance_network_generation("test-handover").await;
            }
            Revocation::Room => {
                rig.room.invalidate();
                rig.renew(Instant::now(), 30);
            }
            Revocation::Port => {
                plane.task.abort();
                let _ = (&mut plane.task).await;
            }
        }
        assert!(
            matches!(
                udp.send_packet_to(&queued, endpoint).await,
                Err(p2pnet_daemon::DaemonError::Network(_))
            ),
            "late packet passed {reason:?}"
        );
        assert!(
            timeout(Duration::from_millis(50), receiver.recv_from(&mut buffer))
                .await
                .is_err(),
            "revoked UDP packet reached the socket: {reason:?}"
        );
    }
}

#[tokio::test]
async fn receive_only_crypto_overlap_remains_deliverable_until_the_original_owner_retires_it() {
    let (local, remote) = pair().await;
    let mut remote_plane = remote.plane();
    let mut local_plane = local.plane();
    let gate = local_plane.gate.clone();
    let packet = gate
        .authorize_inbound(
            decrypt(&local, &remote, &mut remote_plane).await,
            local.authority.network_generation(),
        )
        .unwrap();
    local_plane.inbound.send(packet).await.unwrap();
    receive_count(&local, 1).await;
    let (replacement, _) = sessions(&local.identity, &remote.identity);
    assert!(
        local
            .transport
            .install_active_session(remote.identity.node_id(), None, replacement)
            .await
    );
    let queued = local_plane.endpoint.try_recv().unwrap().unwrap();
    assert!(
        queued.validate().is_ok(),
        "retained receive-only overlap was mistaken for stale data"
    );
    local
        .transport
        .remove_session(remote.identity.node_id())
        .await;
    assert_eq!(queued.into_bytes().unwrap_err(), E::SessionChanged);
}

#[tokio::test]
async fn actual_inbound_worker_refuses_foreign_upstream_owners_before_reading_packets() {
    let (rig, other) = pair().await;
    let (interface, _endpoint) = rig.interface();
    let gate = interface.application_gate().unwrap();
    let udp = UdpTransport::bind("127.0.0.1:0".parse().unwrap(), rig.peers.clone())
        .await
        .unwrap();
    let (foreign_transport, _) = p2pnet_daemon::transport::WireGuardTransport::new();
    for (transport, peers) in [
        (foreign_transport, rig.peers.clone()),
        (rig.transport.clone(), other.peers.clone()),
    ] {
        let (_sender, receiver) = tokio::sync::mpsc::channel(1);
        let (sender, _receiver) = tokio::sync::mpsc::channel(1);
        let result = timeout(
            Duration::from_millis(100),
            transport.run_inbound_with_application_port(
                receiver,
                sender,
                peers,
                udp.clone(),
                gate.clone(),
            ),
        )
        .await
        .unwrap();
        assert!(matches!(
            result,
            Err(p2pnet_daemon::DaemonError::Network(_))
        ));
    }
}
