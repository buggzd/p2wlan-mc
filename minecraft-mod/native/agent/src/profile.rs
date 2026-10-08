use crate::{account::AccountApi, private::PrivateDirectory, AgentError};
use p2wlan_mc_runtime_port::Config;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::path::Path;

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Session {
    version: u32,
    user_id: String,
    server: String,
    token: String,
}
/// Only account credentials/configuration are owned here. Runtime credentials,
/// registration revisions and peer/path/session state remain daemon-owned.
pub struct Profile {
    directory: PrivateDirectory,
    session: Option<Session>,
}
impl Profile {
    pub fn open(path: &Path) -> Result<Self, AgentError> {
        let directory = PrivateDirectory::open(path)?;
        let session = directory
            .read("account.json")?
            .map(|bytes| {
                serde_json::from_slice::<Option<Session>>(&bytes)
                    .map_err(|_| AgentError::ProfileInvalid)
            })
            .transpose()?
            .flatten();
        if session.as_ref().is_some_and(|session| {
            session.version != 1
                || session.user_id.is_empty()
                || session.user_id.len() > 128
                || AccountApi::server(&session.server).as_deref() != Ok(session.server.as_str())
                || session.token.is_empty()
                || session.token.len() > 8192
        }) {
            return Err(AgentError::ProfileInvalid);
        }
        Ok(Self { directory, session })
    }
    pub fn status(&self) -> serde_json::Value {
        match &self.session {
            Some(session) => {
                serde_json::json!({"authenticated":true,"server":session.server,"user_id":session.user_id})
            }
            None => serde_json::json!({"authenticated":false}),
        }
    }
    pub fn credentials(&self) -> Result<(&str, &str, &str), AgentError> {
        let session = self.session.as_ref().ok_or(AgentError::AuthRequired)?;
        Ok((&session.server, &session.token, &session.user_id))
    }
    pub fn login(
        &mut self,
        server: String,
        user_id: String,
        token: String,
    ) -> Result<(), AgentError> {
        if user_id.is_empty() || user_id.len() > 128 || token.is_empty() || token.len() > 8192 {
            return Err(AgentError::ControlResponse);
        }
        let server = AccountApi::server(&server)?;
        let session = Session {
            version: 1,
            user_id,
            server,
            token,
        };
        let bytes = serde_json::to_vec(&session).map_err(|_| AgentError::ProfileInvalid)?;
        self.directory.write("account.json", &bytes)?;
        self.session = Some(session);
        Ok(())
    }
    pub fn logout(&mut self) -> Result<(), AgentError> {
        // Atomic empty account state; retained room identities are not silently
        // deleted/recreated and cannot authorize a connect without a user JWT.
        self.directory.write("account.json", b"null")?;
        self.session = None;
        Ok(())
    }
    pub fn room_config(&self, room: &str, cidr: &str) -> Result<Config, AgentError> {
        if !room.starts_with("room-")
            || room.len() > 128
            || !room
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
        {
            return Err(AgentError::InvalidArguments);
        }
        let (server, token, user) = self.credentials()?;
        let key = hex::encode(Sha256::digest(
            serde_json::to_vec(&[server, user, room]).map_err(|_| AgentError::ProfileInvalid)?,
        ));
        let name = format!("room-{key}.json");
        let existing = self.directory.read(&name)?;
        let mut config = match existing {
            Some(bytes) => {
                serde_json::from_slice::<Config>(&bytes).map_err(|_| AgentError::ProfileInvalid)?
            }
            None => {
                if self.directory.room_count()? >= 32 {
                    return Err(AgentError::ProfileCapacity);
                }
                Config::generate_default(server, room).map_err(|_| AgentError::ProfileInvalid)?
            }
        };
        if config.network.network_id != room || config.control.server_url != server {
            return Err(AgentError::ProfileInvalid);
        }
        config.node.device_name = "Minecraft Mod".into();
        config.network.manual = false;
        config.network.mtu = 1280;
        config.network.udp_bind = "0.0.0.0:0".into();
        config.network.virtual_ip.clear();
        config.network.cidr = cidr.into();
        config.network.netmask = "255.255.255.0".into();
        config.control.auth_token = token.into();
        config.control.device_credential.clear();
        config.control.credential_issued = false;
        config.control.registration_seq = None;
        config.config_path = Some(self.directory.path(&name)?);
        config.diagnostics.enabled = false;
        config.port_mappings.clear();
        config.dns.enabled = false;
        let bytes = serde_json::to_vec_pretty(&config).map_err(|_| AgentError::ProfileInvalid)?;
        self.directory.write(&name, &bytes)?;
        Ok(config)
    }
}
