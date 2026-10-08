use std::collections::VecDeque;

use smoltcp::phy::{Device, DeviceCapabilities, Medium, RxToken, TxToken};
use smoltcp::time::Instant;
use thiserror::Error;

// Conservative IPv4/TCP budget shared by direct and relay. No OS MTU changes.
pub const PACKET_MTU: usize = 1280;
pub const PACKET_CAPACITY: usize = 128;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum PacketError {
    #[error("packet_too_large")]
    TooLarge,
    #[error("packet_invalid_ipv4")]
    Invalid,
    #[error("packet_queue_full")]
    QueueFull,
}

pub(crate) struct PacketDevice {
    transmit_mtu: usize,
    incoming: VecDeque<Vec<u8>>,
    outgoing: VecDeque<Vec<u8>>,
}

impl Default for PacketDevice {
    fn default() -> Self {
        Self::new(PACKET_MTU)
    }
}
impl PacketDevice {
    pub(crate) fn new(transmit_mtu: usize) -> Self {
        Self {
            transmit_mtu,
            incoming: VecDeque::new(),
            outgoing: VecDeque::new(),
        }
    }
    pub(crate) fn ingest(&mut self, packet: Vec<u8>) -> Result<(), PacketError> {
        if packet.len() > PACKET_MTU {
            return Err(PacketError::TooLarge);
        }
        if packet.len() < 20 || packet[0] >> 4 != 4 {
            return Err(PacketError::Invalid);
        }
        if self.incoming.len() >= PACKET_CAPACITY {
            return Err(PacketError::QueueFull);
        }
        self.incoming.push_back(packet);
        Ok(())
    }

    pub(crate) fn clear(&mut self) {
        self.incoming.clear();
        self.outgoing.clear();
    }

    pub(crate) fn pop_outgoing(&mut self) -> Option<Vec<u8>> {
        self.outgoing.pop_front()
    }
}

pub(crate) struct Receive(Vec<u8>);
pub(crate) struct Transmit<'a>(&'a mut VecDeque<Vec<u8>>);

impl RxToken for Receive {
    fn consume<R, F>(self, f: F) -> R
    where
        F: FnOnce(&[u8]) -> R,
    {
        f(&self.0)
    }
}

impl TxToken for Transmit<'_> {
    fn consume<R, F>(self, len: usize, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        let mut packet = vec![0; len];
        let result = f(&mut packet);
        // Interface transmission is serialized and tokens are only issued
        // when capacity exists. No eviction, silent drop, or second writer.
        self.0.push_back(packet);
        result
    }
}

impl Device for PacketDevice {
    type RxToken<'a> = Receive;
    type TxToken<'a> = Transmit<'a>;

    fn receive(&mut self, _: Instant) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        if self.outgoing.len() >= PACKET_CAPACITY {
            return None;
        }
        self.incoming
            .pop_front()
            .map(|packet| (Receive(packet), Transmit(&mut self.outgoing)))
    }

    fn transmit(&mut self, _: Instant) -> Option<Self::TxToken<'_>> {
        (self.outgoing.len() < PACKET_CAPACITY).then_some(Transmit(&mut self.outgoing))
    }

    fn capabilities(&self) -> DeviceCapabilities {
        let mut caps = DeviceCapabilities::default();
        caps.medium = Medium::Ip;
        caps.max_transmission_unit = self.transmit_mtu;
        caps.max_burst_size = Some(32);
        caps
    }
}
