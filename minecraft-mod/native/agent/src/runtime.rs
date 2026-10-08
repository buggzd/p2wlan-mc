use crate::{AgentError, Identity};
use p2wlan_mc_gateway::{DiscoveredService, GatewayStatus, TcpGateway};
use p2wlan_mc_runtime_port::{
    Config, Daemon, DaemonError, FencedPacketEndpoint, PacketInterface, ScopeToken,
};
use std::time::{Duration, Instant};
use tokio::sync::{oneshot, watch};
use tokio::task::JoinHandle;
use tokio::time::timeout;

const START_TIMEOUT: Duration = Duration::from_secs(15);
const STOP_TIMEOUT: Duration = Duration::from_secs(2);

/// Owns exactly one original daemon and its packet-port/gateway lifecycle.
/// It does not copy peer/path/session authority into the Agent.
pub struct ManagedRuntime {
    pub id: Identity,
    parent: ScopeToken,
    started: Instant,
    port: oneshot::Receiver<FencedPacketEndpoint>,
    shutdown: watch::Sender<bool>,
    task: Option<JoinHandle<Result<(), DaemonError>>>,
    pub gateway: Option<TcpGateway>,
    pub address: Option<String>,
}
impl ManagedRuntime {
    pub fn start(mut config: Config, parent: ScopeToken) -> Result<Self, AgentError> {
        if !parent.is_live() {
            return Err(AgentError::Protocol);
        }
        if config.network.manual
            || !config.network.network_id.starts_with("room-")
            || config.control.auth_token.is_empty()
        {
            return Err(AgentError::InvalidConfig);
        }
        // This backend has no system interface, routes, DNS or diagnostics
        // listener. User-supplied OS integration settings cannot re-enable them.
        config.network.mtu = 1280;
        config.diagnostics.enabled = false;
        config.port_mappings.clear();
        config.dns.enabled = false;
        let id = Identity::generate()?;
        let mut daemon = Daemon::new(config);
        let shutdown = daemon.shutdown_sender();
        let (sender, port) = oneshot::channel();
        let task = tokio::spawn(async move {
            daemon
                .run_with_authorized_packet_factory(Box::new(move |context| {
                    let (interface, endpoint) = PacketInterface::authorized_pair(context)?;
                    sender.send(endpoint).map_err(|_| {
                        DaemonError::Network("mod_packet_port_handoff_closed".into())
                    })?;
                    Ok(interface)
                }))
                .await
        });
        Ok(Self {
            id,
            parent,
            started: Instant::now(),
            port,
            shutdown,
            task: Some(task),
            gateway: None,
            address: None,
        })
    }
    pub fn tick(&mut self) -> Result<Option<GatewayStatus>, AgentError> {
        if self.task.as_ref().is_none_or(JoinHandle::is_finished) {
            return Err(AgentError::RuntimeExited);
        }
        if self.gateway.is_none() {
            match self.port.try_recv() {
                Ok(endpoint) => {
                    self.address = Some(endpoint.authority().address().to_string());
                    let mut seed = [0; 8];
                    getrandom::getrandom(&mut seed).map_err(|_| AgentError::Entropy)?;
                    self.gateway = Some(
                        TcpGateway::with_lifetime(
                            endpoint,
                            u64::from_le_bytes(seed),
                            self.parent.clone(),
                        )
                        .map_err(|_| AgentError::RuntimeFailed)?,
                    );
                }
                Err(oneshot::error::TryRecvError::Empty)
                    if self.started.elapsed() < START_TIMEOUT =>
                {
                    return Ok(None)
                }
                Err(_) => return Err(AgentError::StartupTimeout),
            }
        }
        self.gateway
            .as_mut()
            .ok_or(AgentError::RuntimeNotReady)?
            .tick()
            .map(Some)
            .map_err(|_| AgentError::RuntimeFailed)
    }
    pub async fn discover(&mut self, peer: &str) -> Result<Vec<DiscoveredService>, AgentError> {
        let deadline = tokio::time::Instant::now() + p2wlan_mc_gateway::DISCOVERY_TIMEOUT;
        let ticket = tokio::time::timeout_at(
            deadline,
            self.gateway
                .as_mut()
                .ok_or(AgentError::RuntimeNotReady)?
                .discover(peer),
        )
        .await
        .map_err(|_| AgentError::DiscoveryTimeout)?
        .map_err(crate::State::gateway_error)?;
        let result = async {
            loop {
                if tokio::time::Instant::now() >= deadline {
                    return Err(AgentError::DiscoveryTimeout);
                }
                if !self.parent.is_live() {
                    return Err(AgentError::Protocol);
                }
                self.tick()?;
                let result = self
                    .gateway
                    .as_mut()
                    .ok_or(AgentError::RuntimeNotReady)?
                    .poll_discovery(ticket);
                match result {
                    Ok(Some(services)) => {
                        if tokio::time::Instant::now() >= deadline {
                            return Err(AgentError::DiscoveryTimeout);
                        }
                        return Ok(services);
                    }
                    Ok(None)
                    | Err(p2wlan_mc_gateway::GatewayError::Admission(
                        p2wlan_mc_runtime_port::AdmissionError::Contended,
                    )) => {}
                    Err(error) => return Err(crate::State::gateway_error(error)),
                }
                tokio::time::sleep(Duration::from_millis(1)).await;
            }
        };
        let result = tokio::time::timeout_at(deadline, result)
            .await
            .unwrap_or(Err(AgentError::DiscoveryTimeout));
        // Drop the exact query scope on every failure; never renew it or start a new query.
        if let Some(gateway) = self.gateway.as_mut() {
            let _ = gateway.cancel_discovery(ticket);
        }
        result
    }
    pub async fn stop(&mut self) -> Result<(), AgentError> {
        self.gateway = None; // Revoke application scopes before awaiting daemon cleanup.
        self.shutdown.send_replace(true);
        let Some(mut task) = self.task.take() else {
            return Ok(());
        };
        match timeout(STOP_TIMEOUT, &mut task).await {
            Ok(Ok(Ok(()))) => Ok(()),
            Ok(_) => Err(AgentError::RuntimeFailed),
            Err(_) => {
                task.abort();
                Err(AgentError::StopTimeout)
            }
        }
    }
}
impl Drop for ManagedRuntime {
    fn drop(&mut self) {
        self.gateway = None;
        self.shutdown.send_replace(true);
        if let Some(task) = self.task.take() {
            task.abort();
        }
    }
}
