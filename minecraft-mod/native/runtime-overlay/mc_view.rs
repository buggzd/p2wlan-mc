//! Bounded, secret-free projections of the original owners, not send permits.
use super::{AdmissionError, ApplicationAuthority, PeerGrant, ScopeToken};
use crate::dplpmtud::{DplpmtudSnapshot, DplpmtudState};
use crate::peer::NetworkPath;
use crate::udp::UdpTransport;
use serde::Serialize;
use tokio::sync::watch;

/// Diagnostic identities are exact decimal strings, not rounded JSON numbers.
/// A path label is never evidence that application bytes reached their target.
#[derive(Debug, Serialize)]
pub struct PeerView {
    pub network_generation: String,
    pub peer_session: String,
    pub remote_candidate_epoch: Option<String>,
    pub path_revision: Option<String>,
    pub udp_publication_owner: Option<String>,
    pub online: bool,
    pub path: Option<&'static str>,
    pub direct_validation_owned: bool,
    pub direct_validation_request_bound: bool,
    pub direct_pair_present: bool,
    pub direct_pair_local_bound: bool,
    pub direct_pair_epoch_current: bool,
    pub direct_pair_local_matches_primary: bool,
    pub direct_pair_local_matches_ipv6: bool,
    pub direct_outer_ipv6: bool,
    pub direct_validation_epoch_current: bool,
    pub direct_budget_ready: Option<bool>,
    pub mtu: Option<MtuView>,
    pub metrics: Option<crate::peer::PeerMetrics>,
}
#[derive(Debug, Serialize)]
pub struct MtuView {
    pub network_generation: Option<String>,
    pub peer_session: Option<String>,
    pub remote_candidate_epoch: Option<String>,
    pub state: &'static str,
    pub revision: String,
    pub base_confirmed: bool,
    pub overlay_budget: Option<u32>,
    pub probes: u64,
    pub successes: u64,
    pub timeouts: u64,
    pub send_failures: u64,
    pub live_worker: bool,
}
impl From<DplpmtudSnapshot> for MtuView {
    fn from(value: DplpmtudSnapshot) -> Self {
        let identity = value.path_identity;
        Self {
            network_generation: identity
                .as_ref()
                .map(|id| id.network_generation.to_string()),
            peer_session: identity
                .as_ref()
                .map(|id| id.peer_session_generation.to_string()),
            remote_candidate_epoch: identity
                .as_ref()
                .map(|id| id.remote_candidate_epoch.to_string()),
            state: match value.state {
                DplpmtudState::Disabled => "disabled",
                DplpmtudState::Unsupported => "unsupported",
                DplpmtudState::Base => "base",
                DplpmtudState::Searching => "searching",
                DplpmtudState::SearchComplete => "search_complete",
                DplpmtudState::Error => "error",
            },
            revision: value.revision.to_string(),
            base_confirmed: value.base_confirmed,
            overlay_budget: value.overlay_payload_budget,
            probes: value.probe_count.min(i64::MAX as u64),
            successes: value.success_count.min(i64::MAX as u64),
            timeouts: value.timeout_count.min(i64::MAX as u64),
            send_failures: value.send_failure_count.min(i64::MAX as u64),
            live_worker: value.live_worker,
        }
    }
}
/// Selection metadata only. Discovery and joining must acquire fresh grants.
#[derive(Debug, Serialize)]
pub struct AuthorizedPeers {
    pub network_generation: String,
    pub peers: Vec<String>,
}
impl ApplicationAuthority {
    /// Bounded request-time view from the room owner, not a peer cache. This
    /// includes authorized offline members and makes no readiness assertion.
    pub fn authorized_peers(&self, scope: &ScopeToken) -> Result<AuthorizedPeers, AdmissionError> {
        if !self.is_running() {
            return Err(AdmissionError::RuntimeClosed);
        }
        if !scope.is_live() {
            return Err(AdmissionError::ScopeClosed);
        }
        let generation = self.network_generation();
        let peers = self.room.mc_authorized_peers(&self.local_ip.to_string())?;
        if !self.is_running() {
            return Err(AdmissionError::RuntimeClosed);
        }
        if !scope.is_live() {
            return Err(AdmissionError::ScopeClosed);
        }
        if self.network_generation() != generation {
            return Err(AdmissionError::NetworkChanged);
        }
        Ok(AuthorizedPeers {
            network_generation: generation.to_string(),
            peers,
        })
    }
    /// Borrow the same daemon's UDP publication owner; never copy its state.
    pub(crate) fn with_udp_owner(mut self, owner: watch::Receiver<Option<UdpTransport>>) -> Self {
        self.udp_owner = Some(owner);
        self
    }
}
impl PeerGrant {
    /// Read one authorized peer. No all-peer map, diagnostics cache, network
    /// I/O or await is introduced. Views cannot authorize side effects.
    pub fn peer_view(&self) -> Result<PeerView, AdmissionError> {
        self.validate()?;
        let committed = self
            .authority
            .peers
            .committed_business_path_snapshot_sync(&self.id);
        let epoch = committed.as_ref().and_then(|path| path.epoch);
        if epoch.is_some_and(|epoch| epoch.network_generation != self.generation) {
            return Err(AdmissionError::NetworkChanged);
        }
        if epoch.is_some_and(|epoch| epoch.peer_session_generation != self.peer_session) {
            return Err(AdmissionError::PeerChanged);
        }
        let udp = match self.authority.udp_owner.as_ref() {
            Some(owner) => owner.borrow().clone(),
            None => None,
        };
        let validation = committed.as_ref().and_then(|path| match path.active {
            crate::peer::ActiveBusinessPath::Direct(validation) => Some(validation),
            _ => None,
        });
        let pair = self
            .authority
            .peers
            .direct_commit_pair_snapshot_sync(&self.id);
        let mtu = udp
            .as_ref()
            .and_then(|udp| udp.dplpmtud_runtime().snapshot_for_peer(&self.id));
        let result = PeerView {
            network_generation: self.generation.to_string(),
            peer_session: self.peer_session.value().to_string(),
            remote_candidate_epoch: epoch.map(|epoch| epoch.remote_candidate_epoch.to_string()),
            path_revision: pair
                .and_then(|pair| pair.path_revision)
                .map(|revision| revision.to_string()),
            udp_publication_owner: udp
                .as_ref()
                .map(|udp| udp.inbound_publication_owner().to_string()),
            online: committed
                .as_ref()
                .is_some_and(|path| path.is_online_in_generation(self.generation)),
            path: committed
                .as_ref()
                .and_then(|path| path.active_path())
                .map(|path| match path {
                    NetworkPath::Direct => "direct",
                    NetworkPath::Relay => "relay",
                }),
            direct_validation_owned: validation
                .is_some_and(|validation| validation.owner_token.is_some()),
            direct_validation_request_bound: validation
                .is_some_and(|validation| validation.request_id.is_some()),
            direct_pair_present: pair.is_some(),
            direct_pair_local_bound: pair.is_some_and(|pair| pair.local_endpoint.is_some()),
            direct_pair_epoch_current: pair.is_some_and(|pair| {
                epoch.is_some_and(|epoch| {
                    pair.generation == epoch.network_generation
                        && pair.remote_candidate_epoch == epoch.remote_candidate_epoch
                        && pair.peer_session_generation == epoch.peer_session_generation
                })
            }),
            direct_pair_local_matches_primary: pair.is_some_and(|pair| {
                udp.as_ref()
                    .is_some_and(|udp| pair.local_endpoint == udp.local_addr().ok())
            }),
            direct_pair_local_matches_ipv6: pair.is_some_and(|pair| {
                udp.as_ref().is_some_and(|udp| {
                    udp.ipv6_socket()
                        .is_some_and(|socket| pair.local_endpoint == socket.local_addr().ok())
                })
            }),
            direct_outer_ipv6: pair.is_some_and(|pair| pair.remote_endpoint.is_ipv6()),
            direct_validation_epoch_current: validation
                .is_some_and(|validation| Some(validation.epoch) == epoch),
            direct_budget_ready: udp
                .as_ref()
                .map(|udp| udp.direct_business_budget_ready_for_peer(&self.id)),
            metrics: committed.as_ref().and_then(|path| {
                self.authority
                    .peers
                    .mc_peer_metrics(&self.id, &path.virtual_ip, path.active_path())
            }),
            mtu: mtu.map(MtuView::from),
        };
        // Recheck identity after projections; consumers must not use this view
        // as authorization for sends, path promotion or registration.
        self.validate()?;
        Ok(result)
    }
}
