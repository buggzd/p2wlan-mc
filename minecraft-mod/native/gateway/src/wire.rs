use crate::GatewayError;
use smoltcp::wire::{IpAddress, IpProtocol, Ipv4Packet, TcpPacket};
use std::net::SocketAddrV4;

/// Checked immutable wire metadata; the TCP socket remains the state owner.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct TcpFrame {
    pub source: SocketAddrV4,
    pub destination: SocketAddrV4,
    pub sequence: u32,
    pub acknowledgement: Option<u32>,
    pub payload_sequence: u32,
    pub payload_bytes: usize,
    pub syn: bool,
    pub fin: bool,
    pub reset: bool,
}
impl TcpFrame {
    pub fn parse(bytes: &[u8]) -> Result<Self, GatewayError> {
        if bytes.len() > p2wlan_mc_bridge::PACKET_MTU {
            return Err(GatewayError::Capacity);
        }
        let ip = Ipv4Packet::new_checked(bytes).map_err(|_| GatewayError::InvalidPacket)?;
        if ip.version() != 4 || ip.total_len() as usize != bytes.len() {
            return Err(GatewayError::InvalidPacket);
        }
        if ip.more_frags() || ip.frag_offset() != 0 || ip.next_header() != IpProtocol::Tcp {
            return Err(GatewayError::UnsupportedPacket);
        }
        if !ip.verify_checksum() {
            return Err(GatewayError::BadChecksum);
        }
        let tcp = TcpPacket::new_checked(ip.payload()).map_err(|_| GatewayError::InvalidPacket)?;
        if !tcp.verify_checksum(
            &IpAddress::Ipv4(ip.src_addr()),
            &IpAddress::Ipv4(ip.dst_addr()),
        ) {
            return Err(GatewayError::BadChecksum);
        }
        Ok(Self {
            source: SocketAddrV4::new(ip.src_addr(), tcp.src_port()),
            destination: SocketAddrV4::new(ip.dst_addr(), tcp.dst_port()),
            sequence: tcp.seq_number().0 as u32,
            acknowledgement: tcp.ack().then(|| tcp.ack_number().0 as u32),
            payload_sequence: (tcp.seq_number().0 as u32).wrapping_add(u32::from(tcp.syn())),
            payload_bytes: tcp.payload().len(),
            syn: tcp.syn(),
            fin: tcp.fin(),
            reset: tcp.rst(),
        })
    }
}
