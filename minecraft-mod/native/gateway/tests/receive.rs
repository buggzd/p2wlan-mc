// Reuse real upstream room/peer/Noise fixtures rather than inventing a second
// authorization model. Unused fixture helpers belong only to this test module.
#[allow(dead_code)]
#[path = "../../runtime-port/tests/authorized/support.rs"]
mod support;

use p2wlan_mc_gateway::{
    GatewayError, RangeAdmission as R, ReceiveWindow, TcpFrame, CHUNK_BYTES, MAX_RECEIVE_SPANS,
    RECEIVE_WINDOW,
};
use p2wlan_mc_runtime_port::{
    AdmissionError as A, ApplicationAuthority, FramePermit, PeerGrant, ScopeOwner,
};
use std::net::SocketAddrV4;
use std::time::{Duration, Instant};
use tokio::io::AsyncReadExt;
use tokio::net::{TcpListener, TcpStream};
use tokio::time::{sleep, timeout};

struct Fixture {
    local: support::Rig,
    _plane: support::Plane,
    permit: FramePermit,
    connection: PeerGrant,
    world: ScopeOwner,
    _lifetime: ScopeOwner,
}
impl Fixture {
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
                    return packet.permit().clone();
                }
                sleep(Duration::from_millis(1)).await;
            }
        })
        .await
        .unwrap();
        let world = ScopeOwner::new();
        let lifetime = ScopeOwner::new();
        let connection = local
            .authority
            .peer_grant(&local.remote.node_id, world.token())
            .await
            .unwrap()
            .with_scope(lifetime.token())
            .unwrap();
        Self {
            local,
            _plane: local_plane,
            permit,
            connection,
            world,
            _lifetime: lifetime,
        }
    }
    fn window(&self, sequence: u32) -> ReceiveWindow {
        ReceiveWindow::new(self.connection.clone(), sequence).unwrap()
    }
}

async fn sockets() -> (TcpStream, TcpStream) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let client = TcpStream::connect(listener.local_addr().unwrap())
        .await
        .unwrap();
    let (server, _) = listener.accept().await.unwrap();
    (client, server)
}

#[tokio::test]
async fn reordering_overlap_duplicates_and_partial_reads_keep_original_admission() {
    let f = Fixture::new().await;
    let mut window = f.window(100);
    assert_eq!(window.record(108, 8, &f.permit).unwrap(), R::Recorded);
    assert_eq!(window.record(104, 4, &f.permit).unwrap(), R::Recorded);
    assert_eq!(window.record(100, 8, &f.permit).unwrap(), R::Recorded);
    assert_eq!(window.record(103, 4, &f.permit).unwrap(), R::Recorded);
    assert_eq!(window.retained_spans(), 1);
    let mut first = window.seal(b"abcdefgh".to_vec()).unwrap();
    assert_eq!(window.retained_spans(), 1);
    let mut second = window.seal(b"ijklmnop".to_vec()).unwrap();
    assert_eq!(window.retained_spans(), 0);
    let (writer, mut reader) = sockets().await;
    timeout(Duration::from_secs(1), writer.writable())
        .await
        .unwrap()
        .unwrap();
    assert_eq!(first.try_write(&writer).unwrap(), 8);
    assert_eq!(second.try_write(&writer).unwrap(), 8);
    let mut data = [0; 16];
    timeout(Duration::from_secs(1), reader.read_exact(&mut data))
        .await
        .unwrap()
        .unwrap();
    assert_eq!(&data, b"abcdefghijklmnop");
    assert!(first.is_empty());
}

#[tokio::test]
async fn sequence_wrap_obsolete_retransmissions_and_receive_window_bounds_are_explicit() {
    let f = Fixture::new().await;
    let start = u32::MAX - 3;
    let mut window = f.window(start);
    window.record(start.wrapping_add(4), 4, &f.permit).unwrap();
    window.record(start, 4, &f.permit).unwrap();
    assert!(window.seal(vec![1; 8]).unwrap().validate().is_ok());
    assert_eq!(window.record(start, 4, &f.permit).unwrap(), R::Obsolete);
    // A partly consumed retransmission retains only its unread tail.
    assert_eq!(window.record(3, 4, &f.permit).unwrap(), R::Recorded);
    assert!(window.seal(vec![2; 3]).unwrap().validate().is_ok());
    assert_eq!(
        window
            .record(7u32.wrapping_add(RECEIVE_WINDOW as u32), 1, &f.permit)
            .unwrap(),
        R::OutsideWindow
    );
    assert_eq!(window.record(7, 0, &f.permit).unwrap(), R::Empty);
    assert!(matches!(
        window.record(7, RECEIVE_WINDOW + 1, &f.permit),
        Err(GatewayError::Capacity)
    ));
}

#[tokio::test]
async fn metadata_capacity_never_evicts_existing_queued_admissions() {
    let f = Fixture::new().await;
    let mut window = f.window(10);
    for index in 0..MAX_RECEIVE_SPANS {
        window.record(10 + index as u32 * 2, 1, &f.permit).unwrap();
    }
    assert_eq!(window.retained_spans(), MAX_RECEIVE_SPANS);
    assert!(matches!(
        window.record(1000, 1, &f.permit),
        Err(GatewayError::Capacity)
    ));
    assert_eq!(window.retained_spans(), MAX_RECEIVE_SPANS);
    assert!(window.seal(vec![1]).is_ok());
    assert_eq!(window.retained_spans(), MAX_RECEIVE_SPANS - 1);
    assert_eq!(window.record(1000, 1, &f.permit).unwrap(), R::Recorded);
}

#[tokio::test]
async fn missing_or_oversized_read_poisoning_prevents_new_authorization_of_consumed_bytes() {
    let f = Fixture::new().await;
    let mut window = f.window(0);
    window.record(1, 4, &f.permit).unwrap();
    assert!(matches!(
        window.seal(vec![1; 5]),
        Err(GatewayError::MissingAdmission)
    ));
    assert!(matches!(
        window.record(0, 1, &f.permit),
        Err(GatewayError::Poisoned)
    ));
    assert!(matches!(window.seal(vec![1]), Err(GatewayError::Poisoned)));
    let mut oversized = f.window(0);
    oversized.record(0, CHUNK_BYTES + 1, &f.permit).unwrap();
    assert!(matches!(
        oversized.seal(vec![1; CHUNK_BYTES + 1]),
        Err(GatewayError::Capacity)
    ));
}

#[tokio::test]
async fn world_closure_after_socket_readiness_is_rechecked_at_actual_write() {
    let mut f = Fixture::new().await;
    let mut window = f.window(1);
    window.record(1, 9, &f.permit).unwrap();
    let mut pending = window.seal(b"game-data".to_vec()).unwrap();
    let (writer, mut reader) = sockets().await;
    timeout(Duration::from_secs(1), writer.writable())
        .await
        .unwrap()
        .unwrap();
    f.world.revoke();
    assert!(matches!(
        pending.try_write(&writer),
        Err(GatewayError::Admission(A::ScopeClosed))
    ));
    assert_eq!(pending.remaining(), 9);
    let mut data = [0; 32];
    assert!(timeout(Duration::from_millis(50), reader.read(&mut data))
        .await
        .is_err());
}

#[tokio::test]
async fn original_crypto_instance_and_room_snapshot_survive_stream_buffering() {
    let f = Fixture::new().await;
    let mut window = f.window(1);
    window.record(1, 4, &f.permit).unwrap();
    let mut pending = window.seal(vec![1; 4]).unwrap();
    f.local
        .transport
        .remove_session(&f.local.remote.node_id)
        .await;
    let (writer, _) = sockets().await;
    assert!(matches!(
        pending.try_write(&writer),
        Err(GatewayError::Admission(A::SessionChanged))
    ));
    let f = Fixture::new().await;
    let mut window = f.window(1);
    window.record(1, 4, &f.permit).unwrap();
    let pending = window.seal(vec![1; 4]).unwrap();
    f.local.room.invalidate();
    f.local.renew(Instant::now(), 30);
    assert!(matches!(
        pending.validate(),
        Err(GatewayError::Admission(A::RoomRevoked))
    ));
}

#[tokio::test]
async fn receive_binding_rejects_foreign_authority_and_outbound_only_leases() {
    let f = Fixture::new().await;
    let foreign = ApplicationAuthority::new(
        &f.local.config,
        f.local.peers.clone(),
        f.local.room.clone(),
        f.local.transport.clone(),
        f.local.shutdown.subscribe(),
    )
    .unwrap();
    let foreign_connection = foreign
        .peer_grant(&f.local.remote.node_id, f.world.token())
        .await
        .unwrap();
    assert_eq!(
        f.permit
            .bind_received_connection(&foreign_connection)
            .unwrap_err(),
        A::AuthorityMismatch
    );
    assert_eq!(
        f.connection
            .frame_permit()
            .unwrap()
            .bind_received_connection(&f.connection)
            .unwrap_err(),
        A::SessionMissing
    );
}

#[test]
fn real_stack_packets_have_checked_tcp_sequence_metadata() {
    let mut stack = p2wlan_mc_bridge::TcpStack::new("10.21.7.1".parse().unwrap(), 1).unwrap();
    stack
        .connect(SocketAddrV4::new("10.21.7.2".parse().unwrap(), 25565))
        .unwrap();
    stack.poll(Duration::ZERO);
    let bytes = stack.take_packet().unwrap();
    let parsed = TcpFrame::parse(&bytes).unwrap();
    assert!(parsed.syn && !parsed.fin && !parsed.reset);
    assert_eq!(parsed.source.ip().to_string(), "10.21.7.1");
    assert_eq!(parsed.destination.port(), 25565);
    assert_eq!(parsed.payload_sequence, parsed.sequence.wrapping_add(1));
    assert_eq!(parsed.payload_bytes, 0);
    assert_eq!(parsed.acknowledgement, None);
    let mut bad = bytes.clone();
    bad[8] ^= 1;
    assert!(matches!(
        TcpFrame::parse(&bad),
        Err(GatewayError::BadChecksum)
    ));
    let mut bad = bytes.clone();
    let last = bad.len() - 1;
    bad[last] ^= 1;
    assert!(matches!(
        TcpFrame::parse(&bad),
        Err(GatewayError::BadChecksum)
    ));
    assert!(matches!(
        TcpFrame::parse(&bytes[..19]),
        Err(GatewayError::InvalidPacket)
    ));
    assert!(matches!(
        TcpFrame::parse(&vec![0; 1281]),
        Err(GatewayError::Capacity)
    ));
}

#[tokio::test]
async fn already_bound_bytes_cannot_be_rebound_into_a_replacement_world() {
    let mut f = Fixture::new().await;
    let bound = f.permit.bind_received_connection(&f.connection).unwrap();
    f.world.revoke();
    let replacement = ScopeOwner::new();
    let current = f
        .local
        .authority
        .peer_grant(&f.local.remote.node_id, replacement.token())
        .await
        .unwrap();
    assert_eq!(
        bound.bind_received_connection(&current).unwrap_err(),
        A::ScopeClosed
    );
}

#[tokio::test]
async fn completed_reads_release_metadata_during_long_lived_streams() {
    let f = Fixture::new().await;
    let mut window = f.window(0);
    for index in 0..2000 {
        assert_eq!(
            window.record(index * 64, 64, &f.permit).unwrap(),
            R::Recorded
        );
        assert!(window.seal(vec![1; 64]).unwrap().validate().is_ok());
        assert_eq!(window.retained_spans(), 0);
    }
}
