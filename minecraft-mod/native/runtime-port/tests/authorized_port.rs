//! Original room/peer/crypto owners plus real final UDP sends. These are not
//! substitute predicates, unencrypted byte pipes, or Minecraft acceptance tests.
#[path = "authorized/admission.rs"]
mod admission;
#[path = "authorized/queues.rs"]
mod queues;
#[path = "authorized/roster.rs"]
mod roster;
#[path = "authorized/support.rs"]
mod support;
#[path = "authorized/tcp.rs"]
mod tcp;
