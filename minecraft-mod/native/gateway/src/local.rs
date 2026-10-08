use crate::GatewayError;
use p2wlan_mc_runtime_port::PeerGrant;
use std::net::{Ipv4Addr, SocketAddrV4};
use std::time::{Duration, Instant};
use subtle::ConstantTimeEq;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::time::timeout;

pub const LOCAL_HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(2);
pub const LOCAL_PRELUDE_BYTES: usize = 40;
const MAGIC: [u8; 8] = *b"P2MC\0\0\0\x01";

/// A local endpoint capability, never a P2WLAN session or room credential.
/// Deliberately has no Debug/Display implementation to keep secrets out of logs.
#[derive(Clone)]
pub struct LocalKey([u8; 32]);
impl LocalKey {
    pub fn generate() -> Result<Self, GatewayError> {
        let mut bytes = [0; 32];
        getrandom::getrandom(&mut bytes).map_err(|_| GatewayError::Entropy)?;
        Ok(Self(bytes))
    }
    pub fn from_bytes(bytes: [u8; 32]) -> Self {
        Self(bytes)
    }
    pub fn prelude(&self) -> [u8; LOCAL_PRELUDE_BYTES] {
        let mut bytes = [0; LOCAL_PRELUDE_BYTES];
        bytes[..MAGIC.len()].copy_from_slice(&MAGIC);
        bytes[MAGIC.len()..].copy_from_slice(&self.0);
        bytes
    }
}

/// A Java-owned world guard, not an unguarded Minecraft listening port.
#[derive(Clone)]
pub struct LocalTarget {
    address: SocketAddrV4,
    key: LocalKey,
}
impl LocalTarget {
    pub fn new(address: SocketAddrV4, key: LocalKey) -> Result<Self, GatewayError> {
        if *address.ip() != Ipv4Addr::LOCALHOST || address.port() == 0 {
            return Err(GatewayError::WrongTarget);
        }
        Ok(Self { address, key })
    }
    pub fn address(&self) -> SocketAddrV4 {
        self.address
    }
    pub(crate) async fn connect(self, grant: PeerGrant) -> Result<TcpStream, GatewayError> {
        timeout(LOCAL_HANDSHAKE_TIMEOUT, async {
            grant.validate()?;
            let mut socket = TcpStream::connect(self.address)
                .await
                .map_err(GatewayError::Io)?;
            // A late connect cannot inherit a replacement world. The guard also
            // rejects this old key if the OS reused its loopback port.
            grant.validate()?;
            socket
                .write_all(&self.key.prelude())
                .await
                .map_err(GatewayError::Io)?;
            let mut ready = [0u8; 1];
            socket
                .read_exact(&mut ready)
                .await
                .map_err(GatewayError::Io)?;
            grant.validate()?;
            if ready != [1] {
                return Err(GatewayError::LocalKeyMismatch);
            }
            Ok(socket)
        })
        .await
        .map_err(|_| GatewayError::LocalTimeout)?
    }
}

pub(crate) struct LocalPrelude {
    bytes: [u8; LOCAL_PRELUDE_BYTES],
    used: usize,
    deadline: Instant,
}
impl LocalPrelude {
    pub(crate) fn new(now: Instant) -> Self {
        Self {
            bytes: [0; LOCAL_PRELUDE_BYTES],
            used: 0,
            deadline: now + LOCAL_HANDSHAKE_TIMEOUT,
        }
    }
    /// Reads exactly the prelude; it cannot consume a following game handshake.
    pub(crate) fn poll(
        &mut self,
        socket: &TcpStream,
        key: &LocalKey,
        now: Instant,
    ) -> Result<bool, GatewayError> {
        if now >= self.deadline {
            return Err(GatewayError::LocalTimeout);
        }
        if self.used != LOCAL_PRELUDE_BYTES {
            match socket.try_read(&mut self.bytes[self.used..]) {
                Ok(0) => return Err(GatewayError::Closed),
                Ok(count) => self.used += count,
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => return Ok(false),
                Err(error) => return Err(GatewayError::Io(error)),
            }
        }
        if self.used != LOCAL_PRELUDE_BYTES {
            return Ok(false);
        }
        if !bool::from(self.bytes.ct_eq(&key.prelude())) {
            return Err(GatewayError::LocalKeyMismatch);
        }
        Ok(true)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::net::TcpListener;
    #[tokio::test]
    async fn nonce_deadline_is_total_and_never_consumes_following_game_bytes() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let mut client = TcpStream::connect(listener.local_addr().unwrap())
            .await
            .unwrap();
        let (mut server, _) = listener.accept().await.unwrap();
        let key = LocalKey::generate().unwrap();
        let start = Instant::now();
        let mut prelude = LocalPrelude::new(start);
        client.write_all(&key.prelude()[..3]).await.unwrap();
        server.readable().await.unwrap();
        assert!(!prelude.poll(&server, &key, start).unwrap());
        let mut rest = key.prelude()[3..].to_vec();
        rest.extend_from_slice(b"game-handshake");
        client.write_all(&rest).await.unwrap();
        timeout(Duration::from_secs(1), async {
            loop {
                server.readable().await.unwrap();
                if prelude.poll(&server, &key, start).unwrap() {
                    break;
                }
            }
        })
        .await
        .unwrap();
        let mut game = [0; 14];
        server.read_exact(&mut game).await.unwrap();
        assert_eq!(&game, b"game-handshake");
        let mut late = LocalPrelude::new(start);
        assert!(matches!(
            late.poll(&server, &key, start + LOCAL_HANDSHAKE_TIMEOUT),
            Err(GatewayError::LocalTimeout)
        ));
    }
}
