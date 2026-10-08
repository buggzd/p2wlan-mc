use p2pnet_tun::{Error, VirtualInterface};
use p2wlan_mc_runtime_port::{Config, PacketInterface};

#[tokio::test]
async fn bounded_packet_port_has_no_os_interface_and_closes_with_its_owner() {
    let mut config = Config::generate_default("https://control.example.com", "room-test").unwrap();
    assert!(PacketInterface::pair(&config).is_err());
    config.network.mtu = 1280;
    let (mut port, mut endpoint) = PacketInterface::pair(&config).unwrap();
    assert_eq!(port.name(), "p2wlan-mc");
    assert_eq!(port.mtu(), 1280);
    assert_eq!(port.address(), endpoint.address);
    let packet = vec![0x45; 40];
    endpoint.outgoing.send(packet.clone()).await.unwrap();
    let mut buffer = [0; 1280];
    assert_eq!(port.read(&mut buffer).await.unwrap(), packet.len());
    assert_eq!(&buffer[..packet.len()], packet);
    port.write(&packet).await.unwrap();
    assert_eq!(endpoint.incoming.recv().await.unwrap(), packet);
    assert!(matches!(
        port.write(&[0; 1281]).await,
        Err(Error::PacketTooShort(..))
    ));
    drop(endpoint);
    assert!(!port.is_up());
    assert!(matches!(
        port.read(&mut buffer).await,
        Err(Error::DeviceClosed)
    ));
}
