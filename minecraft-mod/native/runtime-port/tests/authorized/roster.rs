use super::support::pair;
use p2wlan_mc_runtime_port::{AdmissionError, ScopeOwner, MAX_ROOM_PEERS};
use std::time::{Duration, Instant};

#[tokio::test]
async fn roster_reads_the_actual_room_owner_and_rejects_stale_lifetimes() {
    let (rig, _) = pair().await;
    let mut scope = ScopeOwner::new();
    let view = rig.authority.authorized_peers(&scope.token()).unwrap();
    assert_eq!(
        view.peers.as_slice(),
        std::slice::from_ref(&rig.remote.node_id)
    );
    assert_eq!(
        view.network_generation,
        rig.authority.network_generation().to_string()
    );
    assert!(!view.peers.contains(&rig.config.node.node_id));
    scope.revoke();
    assert_eq!(
        rig.authority.authorized_peers(&scope.token()).unwrap_err(),
        AdmissionError::ScopeClosed
    );
    let scope = ScopeOwner::new();
    rig.renew(Instant::now() - Duration::from_secs(2), 3);
    tokio::time::sleep(Duration::from_millis(1100)).await;
    assert_eq!(
        rig.authority.authorized_peers(&scope.token()).unwrap_err(),
        AdmissionError::RoomLeaseExpired
    );
    rig.renew(Instant::now(), 30);
    assert!(rig.authority.authorized_peers(&scope.token()).is_ok());
    rig.room.invalidate();
    assert_eq!(
        rig.authority.authorized_peers(&scope.token()).unwrap_err(),
        AdmissionError::RoomRevoked
    );
    rig.renew(Instant::now(), 30);
    rig.shutdown.send_replace(true);
    assert_eq!(
        rig.authority.authorized_peers(&scope.token()).unwrap_err(),
        AdmissionError::RuntimeClosed
    );
    // The returned owned copy is not changed or revived by any later update.
    assert_eq!(view.peers, [rig.remote.node_id]);
}

#[tokio::test]
async fn roster_is_complete_sorted_and_bounded_without_partial_results() {
    let (rig, _) = pair().await;
    let scope = ScopeOwner::new();
    for count in [0, MAX_ROOM_PEERS, MAX_ROOM_PEERS + 1] {
        assert!(rig.room.replace(
            &rig.config.network.virtual_ip,
            (0..count)
                .rev()
                .map(|n| (format!("peer-{n:03}"), format!("10.21.7.{}", n + 2))),
            Instant::now(),
            30
        ));
        let result = rig.authority.authorized_peers(&scope.token());
        if count <= MAX_ROOM_PEERS {
            let peers = result.unwrap().peers;
            assert_eq!(peers.len(), count);
            assert!(peers.windows(2).all(|pair| pair[0] < pair[1]));
        } else {
            assert_eq!(result.unwrap_err(), AdmissionError::RosterCapacity);
        }
    }
    for id in ["x".repeat(257), "bad\npeer".to_string()] {
        assert!(rig.room.replace(
            &rig.config.network.virtual_ip,
            [(id, rig.remote.virtual_ip.clone())],
            Instant::now(),
            30
        ));
        assert_eq!(
            rig.authority.authorized_peers(&scope.token()).unwrap_err(),
            AdmissionError::RosterInvalid
        );
    }
    assert!(rig.room.replace(
        "10.21.7.3",
        [("peer".into(), "10.21.7.2".into())],
        Instant::now(),
        30
    ));
    assert_eq!(
        rig.authority.authorized_peers(&scope.token()).unwrap_err(),
        AdmissionError::RoomRevoked
    );
}
