use super::support::pair;
use p2wlan_mc_runtime_port::{AdmissionError as E, ApplicationAuthority, ScopeOwner, MAX_SCOPES};
use std::time::{Duration, Instant};
use tokio::time::sleep;

#[test]
fn only_the_exact_scope_owner_can_close_its_snapshots() {
    let mut owner = ScopeOwner::new();
    let token = owner.token();
    let replacement = ScopeOwner::new();
    owner.revoke();
    assert!(!token.is_live());
    assert!(replacement.token().is_live());
    let replacement_token = replacement.token();
    drop(owner);
    assert!(replacement_token.is_live());
    drop(replacement);
    assert!(!replacement_token.is_live());
}

#[tokio::test]
async fn grants_are_bound_to_the_exact_authority_and_port() {
    let (rig, _) = pair().await;
    let scope = ScopeOwner::new();
    let grant = rig
        .authority
        .peer_grant(&rig.remote.node_id, scope.token())
        .await
        .unwrap();
    let foreign = ApplicationAuthority::new(
        &rig.config,
        rig.peers.clone(),
        rig.room.clone(),
        rig.transport.clone(),
        rig.shutdown.subscribe(),
    )
    .unwrap();
    let foreign_grant = foreign
        .peer_grant(&rig.remote.node_id, scope.token())
        .await
        .unwrap();
    let (_interface, endpoint) = rig.interface();
    assert_eq!(
        endpoint
            .make_packet(&foreign_grant, rig.echo())
            .unwrap_err(),
        E::AuthorityMismatch
    );
    let original = endpoint.make_packet(&grant, rig.echo()).unwrap();
    let (_other_interface, other_endpoint) = rig.interface();
    assert_eq!(
        other_endpoint.try_send(original).unwrap_err().0,
        E::PortMismatch
    );
    let ip = rig.remote.virtual_ip.parse().unwrap();
    let by_ip = endpoint
        .authority()
        .grant_for_ip(ip, scope.token())
        .await
        .unwrap();
    assert_eq!(by_ip.peer_id(), grant.peer_id());
    assert!(endpoint.make_packet(&by_ip, rig.echo()).is_ok());
}

#[tokio::test]
async fn scopes_have_fixed_capacity_and_duplicate_tokens_do_not_spend_it() {
    let (rig, _) = pair().await;
    let scopes: Vec<_> = (0..=MAX_SCOPES).map(|_| ScopeOwner::new()).collect();
    let mut grant = rig
        .authority
        .peer_grant(&rig.remote.node_id, scopes[0].token())
        .await
        .unwrap();
    for scope in &scopes[..MAX_SCOPES] {
        grant = grant.with_scope(scope.token()).unwrap();
    }
    assert_eq!(
        grant.with_scope(scopes[MAX_SCOPES].token()).unwrap_err(),
        E::ScopeCapacity
    );
    assert!(grant.with_scope(scopes[0].token()).is_ok());
    drop(scopes);
    assert_eq!(grant.validate(), Err(E::ScopeClosed));
}

#[tokio::test]
async fn address_packet_size_and_room_boundaries_fail_closed() {
    let (rig, _) = pair().await;
    let scope = ScopeOwner::new();
    let grant = rig
        .authority
        .peer_grant(&rig.remote.node_id, scope.token())
        .await
        .unwrap();
    let (_interface, endpoint) = rig.interface();
    let mut bad_source = rig.echo();
    bad_source[15] = 9;
    assert_eq!(
        endpoint.make_packet(&grant, bad_source).unwrap_err(),
        E::AddressMismatch
    );
    assert_eq!(
        endpoint.make_packet(&grant, vec![0; 1281]).unwrap_err(),
        E::PacketTooLarge
    );
    for bytes in [vec![], vec![0; 20], vec![0x44; 20]] {
        assert_eq!(
            endpoint.make_packet(&grant, bytes).unwrap_err(),
            E::InvalidPacket
        );
    }
    assert_eq!(
        rig.authority
            .grant_for_ip("10.21.7.9".parse().unwrap(), scope.token())
            .await
            .unwrap_err(),
        E::UnknownPeer
    );
    let mut config = rig.config.clone();
    config.network.network_id = "not-a-room".into();
    assert!(matches!(
        ApplicationAuthority::new(
            &config,
            rig.peers.clone(),
            rig.room.clone(),
            rig.transport.clone(),
            rig.shutdown.subscribe()
        ),
        Err(E::RoomRequired)
    ));
}

#[tokio::test]
async fn remove_readd_and_offline_online_never_revive_old_peer_grants() {
    let (rig, _) = pair().await;
    let scope = ScopeOwner::new();
    let original = rig
        .authority
        .peer_grant(&rig.remote.node_id, scope.token())
        .await
        .unwrap();
    rig.peers.remove_peer(&rig.remote.node_id).await;
    rig.peers.add_peer(&rig.remote).await;
    assert_eq!(original.validate(), Err(E::PeerChanged));
    let online = rig
        .authority
        .peer_grant(&rig.remote.node_id, scope.token())
        .await
        .unwrap();
    let mut offline = rig.remote.clone();
    offline.online = false;
    rig.peers.add_peer(&offline).await;
    assert_eq!(online.validate(), Err(E::PeerChanged));
    rig.peers.add_peer(&rig.remote).await;
    assert_eq!(online.validate(), Err(E::PeerChanged));
    assert!(rig
        .authority
        .peer_grant(&rig.remote.node_id, scope.token())
        .await
        .is_ok());
}

#[tokio::test]
async fn network_handover_and_runtime_shutdown_invalidate_old_capabilities() {
    let (rig, _) = pair().await;
    let scope = ScopeOwner::new();
    let grant = rig
        .authority
        .peer_grant(&rig.remote.node_id, scope.token())
        .await
        .unwrap();
    rig.peers.advance_network_generation("test-handover").await;
    assert_eq!(grant.validate(), Err(E::NetworkChanged));
    let current = rig
        .authority
        .peer_grant(&rig.remote.node_id, scope.token())
        .await
        .unwrap();
    rig.shutdown.send(true).unwrap();
    assert_eq!(current.validate(), Err(E::RuntimeClosed));
}

#[tokio::test]
async fn room_revocation_and_reauthorization_never_revive_old_membership() {
    let (rig, _) = pair().await;
    let scope = ScopeOwner::new();
    let grant = rig
        .authority
        .peer_grant(&rig.remote.node_id, scope.token())
        .await
        .unwrap();
    rig.room.invalidate();
    rig.renew(Instant::now(), 30);
    assert_eq!(grant.validate(), Err(E::RoomRevoked));
    assert!(rig
        .authority
        .peer_grant(&rig.remote.node_id, scope.token())
        .await
        .is_ok());
}

#[tokio::test]
async fn healthy_lease_renewal_keeps_connection_but_not_an_expired_queued_frame() {
    let (rig, _) = pair().await;
    rig.renew(Instant::now(), 1);
    let scope = ScopeOwner::new();
    let grant = rig
        .authority
        .peer_grant(&rig.remote.node_id, scope.token())
        .await
        .unwrap();
    let old_frame = grant.frame_permit().unwrap();
    rig.renew(Instant::now(), 30);
    sleep(Duration::from_millis(1100)).await;
    assert!(grant.validate().is_ok());
    assert!(grant.frame_permit().is_ok());
    assert_eq!(old_frame.validate(), Err(E::RoomLeaseExpired));
}

#[tokio::test]
async fn reauthorization_after_lease_expiry_cannot_resume_an_old_connection() {
    let (rig, _) = pair().await;
    rig.renew(Instant::now(), 1);
    let scope = ScopeOwner::new();
    let grant = rig
        .authority
        .peer_grant(&rig.remote.node_id, scope.token())
        .await
        .unwrap();
    sleep(Duration::from_millis(1100)).await;
    rig.renew(Instant::now(), 30);
    assert_eq!(grant.validate(), Err(E::RoomRevoked));
}

#[tokio::test]
async fn a_new_remote_registration_invalidates_the_old_incarnation() {
    let (rig, _) = pair().await;
    let scope = ScopeOwner::new();
    let old = rig
        .authority
        .peer_grant(&rig.remote.node_id, scope.token())
        .await
        .unwrap();
    let mut reincarnated = rig.remote.clone();
    reincarnated.registration_seq += 1;
    rig.peers.add_peer(&reincarnated).await;
    assert_eq!(old.validate(), Err(E::PeerChanged));
}

#[tokio::test]
async fn closing_the_runtime_watch_owner_is_not_mistaken_for_running() {
    let (rig, _) = pair().await;
    let scope = ScopeOwner::new();
    let grant = rig
        .authority
        .peer_grant(&rig.remote.node_id, scope.token())
        .await
        .unwrap();
    drop(rig.shutdown);
    assert_eq!(grant.validate(), Err(E::RuntimeClosed));
}

#[tokio::test]
async fn unrelated_room_member_changes_preserve_connections_but_fence_old_frames() {
    let (rig, _) = pair().await;
    let scope = ScopeOwner::new();
    let grant = rig
        .authority
        .peer_grant(&rig.remote.node_id, scope.token())
        .await
        .unwrap();
    let original_frame = grant.frame_permit().unwrap();
    assert!(rig.room.replace(
        &rig.config.network.virtual_ip,
        [
            (rig.remote.node_id.clone(), rig.remote.virtual_ip.clone()),
            ("other-member".into(), "10.21.7.3".into())
        ],
        Instant::now(),
        30
    ));
    assert!(
        grant.validate().is_ok(),
        "joining another player revoked an unchanged peer"
    );
    assert_eq!(original_frame.validate(), Err(E::RoomSnapshotChanged));
    let joined_frame = grant.frame_permit().unwrap();
    rig.renew(Instant::now(), 30);
    assert!(
        grant.validate().is_ok(),
        "leaving another player revoked an unchanged peer"
    );
    assert_eq!(joined_frame.validate(), Err(E::RoomSnapshotChanged));
    assert!(grant.frame_permit().is_ok());
    // Removing THIS member, in contrast, permanently retires its identity.
    assert!(rig.room.replace(
        &rig.config.network.virtual_ip,
        [("other-member".into(), "10.21.7.3".into())],
        Instant::now(),
        30
    ));
    rig.renew(Instant::now(), 30);
    assert_eq!(grant.validate(), Err(E::RoomRevoked));
}

#[tokio::test]
async fn application_identity_is_a_read_only_snapshot_of_the_resolved_runtime_config() {
    let (rig, _) = pair().await;
    assert_eq!(rig.authority.local_peer_id(), rig.config.node.node_id);
    assert_eq!(rig.authority.conservative_packet_budget(), 1168);
    for reason in [
        E::UnknownPeer,
        E::RoomRevoked,
        E::SessionChanged,
        E::NetworkChanged,
        E::Contended,
    ] {
        assert_eq!(reason.code(), reason.to_string());
    }
}

#[tokio::test]
async fn peer_view_is_secret_free_authorized_and_fenced_after_projection() {
    let (rig, _) = pair().await;
    let mut scope = ScopeOwner::new();
    let grant = rig
        .authority
        .peer_grant(&rig.remote.node_id, scope.token())
        .await
        .unwrap();
    let view = grant.peer_view().unwrap();
    assert_eq!(
        view.network_generation,
        rig.authority.network_generation().to_string()
    );
    assert!(view.direct_budget_ready.is_none());
    assert!(view.mtu.is_none());
    let encoded = serde_json::to_value(&view).unwrap();
    for forbidden in [
        "endpoint",
        "public_key",
        "token",
        "credential",
        "packet",
        "device_name",
    ] {
        assert!(!encoded.as_object().unwrap().contains_key(forbidden));
    }
    scope.revoke();
    assert_eq!(grant.peer_view().unwrap_err(), E::ScopeClosed);
    let scope = ScopeOwner::new();
    let grant = rig
        .authority
        .peer_grant(&rig.remote.node_id, scope.token())
        .await
        .unwrap();
    rig.peers.advance_network_generation("view-test").await;
    assert_eq!(grant.peer_view().unwrap_err(), E::NetworkChanged);
}
