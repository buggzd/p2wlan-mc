use std::collections::HashMap;
use std::net::{Ipv4Addr, SocketAddrV4};
use std::time::Duration;

use smoltcp::iface::{Config, Interface, SocketHandle, SocketSet};
use smoltcp::socket::tcp;
use smoltcp::time::{Duration as StackDuration, Instant};
use smoltcp::wire::{HardwareAddress, IpAddress, IpCidr};
use thiserror::Error;

use crate::device::PacketDevice;
use crate::PacketError;

pub const MAX_CONNECTIONS: usize = 32;
pub const BUFFER_BYTES: usize = 32 * 1024;

/// Never exposes a reusable smoltcp slot as connection identity.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub struct ConnectionId(u64);

#[derive(Debug, Error, Eq, PartialEq)]
pub enum StackError {
    #[error("tcp_capacity")]
    Capacity,
    #[error("tcp_invalid_endpoint")]
    InvalidEndpoint,
    #[error("tcp_stale_connection")]
    StaleConnection,
    #[error("tcp_would_block")]
    WouldBlock,
    #[error("tcp_closed")]
    Closed,
    #[error("tcp_identity_exhausted")]
    IdentityExhausted,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ReadResult {
    Data(usize),
    WouldBlock,
    Eof,
}

/// Single owner of the user-space TCP state, sockets, and bounded IP queues.
pub struct TcpStack {
    interface: Interface,
    device: PacketDevice,
    sockets: SocketSet<'static>,
    connections: HashMap<ConnectionId, SocketHandle>,
    next_id: u64,
    next_port: u16,
    address: Ipv4Addr,
}

impl TcpStack {
    pub fn new(address: Ipv4Addr, random_seed: u64) -> Result<Self, StackError> {
        if address.is_unspecified() || address.is_multicast() || address.is_broadcast() {
            return Err(StackError::InvalidEndpoint);
        }
        let mut device = PacketDevice::default();
        let mut config = Config::new(HardwareAddress::Ip);
        config.random_seed = random_seed;
        let mut interface = Interface::new(config, &mut device, Instant::ZERO);
        interface.update_ip_addrs(|addresses| {
            // This is an isolated IP endpoint, not a router. The surrounding
            // P2WLAN dataplane remains the authority for destination admission.
            let _ = addresses.push(IpCidr::new(IpAddress::Ipv4(address), 0));
        });
        Ok(Self {
            interface,
            device,
            sockets: SocketSet::new(vec![]),
            connections: HashMap::new(),
            next_id: 1,
            next_port: 49152,
            address,
        })
    }

    pub fn address(&self) -> Ipv4Addr {
        self.address
    }

    pub fn connection_count(&self) -> usize {
        self.connections.len()
    }

    fn allocate(&mut self) -> Result<(ConnectionId, SocketHandle), StackError> {
        if self.connections.len() >= MAX_CONNECTIONS {
            return Err(StackError::Capacity);
        }
        let id = ConnectionId(self.next_id);
        self.next_id = self
            .next_id
            .checked_add(1)
            .ok_or(StackError::IdentityExhausted)?;
        let mut socket = tcp::Socket::new(
            tcp::SocketBuffer::new(vec![0; BUFFER_BYTES]),
            tcp::SocketBuffer::new(vec![0; BUFFER_BYTES]),
        );
        socket.set_timeout(Some(StackDuration::from_secs(15)));
        socket.set_keep_alive(Some(StackDuration::from_secs(5)));
        socket.set_nagle_enabled(false);
        socket.set_congestion_control(tcp::CongestionControl::Reno);
        let handle = self.sockets.add(socket);
        self.connections.insert(id, handle);
        Ok((id, handle))
    }

    pub fn listen(&mut self, port: u16) -> Result<ConnectionId, StackError> {
        if port == 0 {
            return Err(StackError::InvalidEndpoint);
        }
        let (id, handle) = self.allocate()?;
        if self
            .sockets
            .get_mut::<tcp::Socket>(handle)
            .listen((self.address, port))
            .is_err()
        {
            self.remove(id)?;
            return Err(StackError::InvalidEndpoint);
        }
        Ok(id)
    }

    pub fn connect(&mut self, remote: SocketAddrV4) -> Result<ConnectionId, StackError> {
        if remote.port() == 0
            || remote.ip().is_unspecified()
            || remote.ip().is_multicast()
            || remote.ip().is_broadcast()
            || *remote.ip() == self.address
        {
            return Err(StackError::InvalidEndpoint);
        }
        // Scan only the finite ephemeral range; never overwrite a live tuple.
        let mut port = self.next_port;
        for _ in 0..16384 {
            let occupied = self.connections.values().any(|handle| {
                self.sockets
                    .get::<tcp::Socket>(*handle)
                    .local_endpoint()
                    .is_some_and(|endpoint| endpoint.port == port)
            });
            if !occupied {
                break;
            }
            port = if port == u16::MAX { 49152 } else { port + 1 };
        }
        self.next_port = if port == u16::MAX { 49152 } else { port + 1 };
        let (id, handle) = self.allocate()?;
        if self
            .sockets
            .get_mut::<tcp::Socket>(handle)
            .connect(
                self.interface.context(),
                (*remote.ip(), remote.port()),
                (self.address, port),
            )
            .is_err()
        {
            self.remove(id)?;
            return Err(StackError::InvalidEndpoint);
        }
        Ok(id)
    }

    fn socket(&mut self, id: ConnectionId) -> Result<&mut tcp::Socket<'static>, StackError> {
        let handle = *self
            .connections
            .get(&id)
            .ok_or(StackError::StaleConnection)?;
        Ok(self.sockets.get_mut::<tcp::Socket>(handle))
    }

    pub fn established(&mut self, id: ConnectionId) -> Result<bool, StackError> {
        Ok(self.socket(id)?.state() == tcp::State::Established)
    }

    pub fn is_closed(&mut self, id: ConnectionId) -> Result<bool, StackError> {
        Ok(self.socket(id)?.state() == tcp::State::Closed)
    }

    pub fn remote(&mut self, id: ConnectionId) -> Result<Option<SocketAddrV4>, StackError> {
        Ok(self.socket(id)?.remote_endpoint().map(|endpoint| {
            let IpAddress::Ipv4(address) = endpoint.addr;
            SocketAddrV4::new(address, endpoint.port)
        }))
    }

    pub fn read(&mut self, id: ConnectionId, bytes: &mut [u8]) -> Result<ReadResult, StackError> {
        let socket = self.socket(id)?;
        if socket.can_recv() {
            socket
                .recv_slice(bytes)
                .map(ReadResult::Data)
                .map_err(|_| StackError::Closed)
        } else if !socket.may_recv()
            && !matches!(
                socket.state(),
                tcp::State::Listen | tcp::State::SynSent | tcp::State::SynReceived
            )
        {
            Ok(ReadResult::Eof)
        } else {
            Ok(ReadResult::WouldBlock)
        }
    }

    pub fn write(&mut self, id: ConnectionId, bytes: &[u8]) -> Result<usize, StackError> {
        let socket = self.socket(id)?;
        if socket.can_send() {
            socket.send_slice(bytes).map_err(|_| StackError::Closed)
        } else if socket.may_send()
            || matches!(
                socket.state(),
                tcp::State::SynSent | tcp::State::SynReceived
            )
        {
            Err(StackError::WouldBlock)
        } else {
            Err(StackError::Closed)
        }
    }

    pub fn close_write(&mut self, id: ConnectionId) -> Result<(), StackError> {
        self.socket(id)?.close();
        Ok(())
    }

    pub fn remove(&mut self, id: ConnectionId) -> Result<(), StackError> {
        let handle = self
            .connections
            .remove(&id)
            .ok_or(StackError::StaleConnection)?;
        self.sockets.remove(handle);
        Ok(())
    }

    pub fn ingest(&mut self, packet: Vec<u8>) -> Result<(), PacketError> {
        self.device.ingest(packet)
    }

    pub fn take_packet(&mut self) -> Option<Vec<u8>> {
        self.device.pop_outgoing()
    }

    pub fn poll(&mut self, elapsed: Duration) {
        let millis = elapsed.as_millis().min(i64::MAX as u128) as i64;
        self.interface.poll(
            Instant::from_millis(millis),
            &mut self.device,
            &mut self.sockets,
        );
    }
}
