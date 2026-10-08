//! Stable entry to the build-only upstream packet-port adapter.
pub use p2pnet_daemon::mc_embed::{
    AdmissionError, ApplicationAuthority, AuthorizedPacketFactory, FencedPacket,
    FencedPacketEndpoint, FramePermit, PacketContext, PacketEndpoint, PacketFactory, PacketGate,
    PacketInterface, PeerGrant, ScopeOwner, ScopeToken, MAX_SCOPES,
};
pub use p2pnet_daemon::{Config, Daemon, DaemonError};
