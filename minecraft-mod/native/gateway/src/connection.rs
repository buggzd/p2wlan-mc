use crate::{GatewayError, RangeAdmission, ReceiveWindow, ReceivedBytes, TcpFrame, CHUNK_BYTES};
use p2wlan_mc_bridge::{ConnectionId, ReadResult, StackError, TcpStack};
use p2wlan_mc_runtime_port::{AdmissionError, FencedPacket, PeerGrant, ScopeOwner};
use std::net::{Shutdown, SocketAddrV4};
use std::time::{Duration, Instant};
use tokio::net::TcpStream;

// Application silence is not TCP peer loss: a healthy stack sends its own
// keep-alives. Keep this longer than normal game heartbeat intervals while
// retaining a finite deadline for abandoned/half-closed local sockets.
pub const BRIDGE_IDLE_TIMEOUT: Duration = Duration::from_secs(120);
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct BridgeProgress {
    pub moved_bytes: usize,
    pub complete: bool,
}

struct TransmitBytes {
    bytes: Vec<u8>,
    offset: usize,
    grant: PeerGrant,
}

/// One local socket and its fixed virtual socket identity. This owns only
/// application buffers/lifetime; the borrowed stack remains the sole TCP owner.
/// The supervisor must seal generated IP output before replacing/removing a
/// flow, then retain this grant through the existing authorized packet port.
pub struct ConnectionBridge {
    id: ConnectionId,
    local_endpoint: SocketAddrV4,
    grant: PeerGrant,
    _lifetime: ScopeOwner,
    socket: Option<TcpStream>,
    receive: Option<ReceiveWindow>,
    received: Option<ReceivedBytes>,
    transmit: Option<TransmitBytes>,
    local_eof: bool,
    virtual_eof: bool,
    shutdown_sent: bool,
    last_progress: Instant,
}
impl ConnectionBridge {
    pub fn new(
        stack: &mut TcpStack,
        id: ConnectionId,
        grant: PeerGrant,
    ) -> Result<Self, GatewayError> {
        let local_endpoint = stack.local(id)?.ok_or(GatewayError::WrongTarget)?;
        grant.validate()?;
        let lifetime = ScopeOwner::new();
        let grant = grant.with_scope(lifetime.token())?;
        Ok(Self {
            id,
            local_endpoint,
            grant,
            _lifetime: lifetime,
            socket: None,
            receive: None,
            received: None,
            transmit: None,
            local_eof: false,
            virtual_eof: false,
            shutdown_sent: false,
            last_progress: Instant::now(),
        })
    }
    pub fn grant(&self) -> &PeerGrant {
        &self.grant
    }
    /// A connect task must return the exact snapshot it captured before await.
    /// A socket cannot replace an already attached destination.
    pub fn attach_local(
        &mut self,
        socket: TcpStream,
        expected: &PeerGrant,
    ) -> Result<(), GatewayError> {
        self.grant.validate()?;
        if self.socket.is_some() || expected != &self.grant {
            return Err(GatewayError::WrongTarget);
        }
        expected.validate()?;
        socket.set_nodelay(true).map_err(GatewayError::Io)?;
        self.socket = Some(socket);
        self.last_progress = Instant::now();
        Ok(())
    }
    pub fn receive_packet(
        &mut self,
        stack: &mut TcpStack,
        packet: FencedPacket,
        elapsed: Duration,
    ) -> Result<RangeAdmission, GatewayError> {
        let permit = packet.permit().bind_received_connection(&self.grant)?;
        let bytes = packet.into_bytes()?;
        let frame = TcpFrame::parse(&bytes)?;
        if stack.local(self.id)? != Some(self.local_endpoint)
            || frame.destination != self.local_endpoint
            || *frame.source.ip() != self.grant.address()
        {
            return Err(GatewayError::WrongTarget);
        }
        let before = stack.remote(self.id)?;
        if before.is_some_and(|remote| remote != frame.source) {
            return Err(GatewayError::WrongTarget);
        }
        let result = match self.receive.as_mut() {
            Some(window) => window.record(frame.payload_sequence, frame.payload_bytes, &permit)?,
            None if frame.syn => RangeAdmission::Empty,
            None => return Err(GatewayError::MissingAdmission),
        };
        if result == RangeAdmission::OutsideWindow {
            return Ok(result);
        }
        stack.ingest(bytes)?;
        stack.poll(elapsed);
        if self.receive.is_none()
            && frame.syn
            && stack.remote(self.id)? == Some(frame.source)
            && (before.is_none() || stack.established(self.id)?)
        {
            let mut window = ReceiveWindow::new(self.grant.clone(), frame.payload_sequence)?;
            let admitted = window.record(frame.payload_sequence, frame.payload_bytes, &permit)?;
            self.receive = Some(window);
            return Ok(admitted);
        }
        Ok(result)
    }
    pub fn step(&mut self, stack: &mut TcpStack) -> Result<BridgeProgress, GatewayError> {
        self.grant.validate()?;
        if stack.is_closed(self.id)? {
            if self.local_eof
                && self.virtual_eof
                && self.transmit.is_none()
                && self.received.is_none()
            {
                return Ok(BridgeProgress {
                    moved_bytes: 0,
                    complete: true,
                });
            }
            return Err(GatewayError::Closed);
        }
        if stack.local(self.id)? != Some(self.local_endpoint) {
            return Err(GatewayError::WrongTarget);
        }
        if self.last_progress.elapsed() >= BRIDGE_IDLE_TIMEOUT {
            return Err(GatewayError::IdleTimeout);
        }
        if self.socket.is_none() {
            return Ok(BridgeProgress {
                moved_bytes: 0,
                complete: false,
            });
        }
        let mut moved = self.drive_transmit(stack)?;
        moved += self.drive_receive(stack)?;
        if moved != 0 {
            self.last_progress = Instant::now();
        }
        let complete = self.local_eof
            && self.virtual_eof
            && self.transmit.is_none()
            && self.received.is_none()
            && stack.is_closed(self.id)?;
        Ok(BridgeProgress {
            moved_bytes: moved,
            complete,
        })
    }
    fn drive_transmit(&mut self, stack: &mut TcpStack) -> Result<usize, GatewayError> {
        let mut moved = 0;
        if self.transmit.is_none() && !self.local_eof {
            self.grant.validate()?;
            let socket = self.socket.as_ref().ok_or(GatewayError::WrongTarget)?;
            let mut bytes = vec![0; CHUNK_BYTES];
            match socket.try_read(&mut bytes) {
                Ok(0) => {
                    self.local_eof = true;
                }
                Ok(count) => {
                    bytes.truncate(count);
                    moved += count;
                    self.transmit = Some(TransmitBytes {
                        bytes,
                        offset: 0,
                        grant: self.grant.clone(),
                    });
                }
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {}
                Err(error) => return Err(GatewayError::Io(error)),
            }
        }
        if stack.remote(self.id)?.is_none() {
            return Ok(moved);
        }
        if let Some(pending) = self.transmit.as_mut() {
            pending.grant.validate()?;
            match stack.write(self.id, &pending.bytes[pending.offset..]) {
                Ok(count) => {
                    pending.offset += count;
                    moved += count;
                }
                Err(StackError::WouldBlock) => {}
                Err(error) => return Err(error.into()),
            }
            if pending.offset == pending.bytes.len() {
                self.transmit = None;
            }
        }
        if self.local_eof && self.transmit.is_none() {
            stack.close_write(self.id)?;
        }
        Ok(moved)
    }
    fn drive_receive(&mut self, stack: &mut TcpStack) -> Result<usize, GatewayError> {
        let mut moved = 0;
        if self.received.is_none() && !self.virtual_eof && self.receive.is_some() {
            let mut bytes = vec![0; CHUNK_BYTES];
            match stack.read(self.id, &mut bytes)? {
                ReadResult::Data(count) => {
                    bytes.truncate(count);
                    moved += count;
                    let window = self
                        .receive
                        .as_mut()
                        .ok_or(GatewayError::MissingAdmission)?;
                    self.received = Some(window.seal(bytes)?);
                }
                ReadResult::Eof => self.virtual_eof = true,
                ReadResult::WouldBlock => {}
            }
        }
        let socket = self.socket.as_ref().ok_or(GatewayError::WrongTarget)?;
        if let Some(pending) = self.received.as_mut() {
            match pending.try_write(socket) {
                Ok(count) => moved += count,
                Err(
                    GatewayError::WouldBlock | GatewayError::Admission(AdmissionError::Contended),
                ) => {}
                Err(error) => return Err(error),
            }
            if pending.is_empty() {
                self.received = None;
            }
        }
        if self.virtual_eof && self.received.is_none() && !self.shutdown_sent {
            self.grant.validate()?;
            socket2::SockRef::from(socket)
                .shutdown(Shutdown::Write)
                .map_err(GatewayError::Io)?;
            self.shutdown_sent = true;
        }
        Ok(moved)
    }
}
