//! Stable entry to the build-only upstream packet-port adapter.
pub use p2pnet_daemon::mc_embed::{
    AdmissionError, ApplicationAuthority, AuthorizedPacketFactory, AuthorizedPeers, FencedPacket,
    FencedPacketEndpoint, FramePermit, MtuView, PacketContext, PacketEndpoint, PacketFactory,
    PacketGate, PacketInterface, PeerGrant, PeerView, ScopeOwner, ScopeToken, MAX_ROOM_PEERS,
    MAX_SCOPES,
};
pub use p2pnet_daemon::{Config, Daemon, DaemonError};
