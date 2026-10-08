#[allow(dead_code)]
#[path = "../../../runtime-port/tests/authorized/support.rs"]
mod authorized;
pub use authorized::*;
use p2pnet_daemon::dataplane::OutboundPacket;
use p2pnet_daemon::transport::WireGuardTransport;
use p2pnet_daemon::udp::UdpTransport;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::mpsc;
use tokio::task::JoinSet;
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
pub async fn network(
    local: &Rig,
    plane: &mut Plane,
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
pub async fn sockets() -> (TcpStream, TcpStream) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let left = TcpStream::connect(listener.local_addr().unwrap())
        .await
        .unwrap();
    let (right, _) = listener.accept().await.unwrap();
    (left, right)
}
