use std::net::{Ipv4Addr, SocketAddrV4};
use std::time::Duration;

use p2wlan_mc_bridge::{
    PacketError, ReadResult, StackError, TcpStack, MAX_CONNECTIONS, PACKET_CAPACITY,
};

fn transfer(a: &mut TcpStack, b: &mut TcpStack) {
    while let Some(packet) = a.take_packet() {
        b.ingest(packet).unwrap();
    }
}

#[test]
fn real_tcp_bidirectional_payload_and_half_close() {
    let mut client = TcpStack::new(Ipv4Addr::new(10, 221, 0, 1), 1).unwrap();
    let mut host = TcpStack::new(Ipv4Addr::new(10, 221, 0, 2), 2).unwrap();
    let server = host.listen(25565).unwrap();
    let connection = client
        .connect(SocketAddrV4::new(host.address(), 25565))
        .unwrap();
    let payload: Vec<u8> = (0..180_000).map(|n| (n % 251) as u8).collect();
    let mut sent = 0;
    let mut received = Vec::new();
    let mut echoed = Vec::new();
    let mut pending_echo = Vec::new();
    let mut fin_sent = false;
    let mut server_fin = false;
    let mut eof = false;
    for tick in 0..4000 {
        let now = Duration::from_millis(tick * 5);
        client.poll(now);
        host.poll(now);
        transfer(&mut client, &mut host);
        transfer(&mut host, &mut client);
        if sent < payload.len() {
            match client.write(connection, &payload[sent..]) {
                Ok(count) => sent += count,
                Err(StackError::WouldBlock) => {}
                error => panic!("unexpected write {error:?}"),
            }
        } else if !fin_sent {
            client.close_write(connection).unwrap();
            fin_sent = true;
        }
        let mut buf = [0; 8192];
        match host.read(server, &mut buf).unwrap() {
            ReadResult::Data(count) => {
                received.extend_from_slice(&buf[..count]);
                pending_echo.extend_from_slice(&buf[..count]);
            }
            ReadResult::Eof if pending_echo.is_empty() && !server_fin => {
                host.close_write(server).unwrap();
                server_fin = true;
            }
            _ => {}
        }
        if !pending_echo.is_empty() {
            match host.write(server, &pending_echo) {
                Ok(count) => {
                    pending_echo.drain(..count);
                }
                Err(StackError::WouldBlock) => {}
                error => panic!("unexpected echo {error:?}"),
            }
        }
        match client.read(connection, &mut buf).unwrap() {
            ReadResult::Data(count) => echoed.extend_from_slice(&buf[..count]),
            ReadResult::Eof => {
                eof = true;
                break;
            }
            _ => {}
        }
    }
    assert!(eof, "both FIN directions must complete");
    assert_eq!(received, payload);
    assert_eq!(echoed, payload);
    assert_eq!(
        host.remote(server).unwrap().unwrap().ip(),
        &client.address()
    );
}

#[test]
fn bounds_and_stale_ids_fail_without_eviction() {
    let mut stack = TcpStack::new(Ipv4Addr::new(10, 221, 0, 1), 3).unwrap();
    let ids: Vec<_> = (0..MAX_CONNECTIONS)
        .map(|_| stack.listen(25565).unwrap())
        .collect();
    assert_eq!(stack.listen(25565), Err(StackError::Capacity));
    stack.remove(ids[0]).unwrap();
    let next = stack.listen(25565).unwrap();
    assert_ne!(next, ids[0]);
    assert_eq!(
        stack.read(ids[0], &mut [0; 1]),
        Err(StackError::StaleConnection)
    );
    assert_eq!(stack.connection_count(), MAX_CONNECTIONS);
    let packet = {
        let mut p = vec![0; 20];
        p[0] = 0x45;
        p
    };
    for _ in 0..PACKET_CAPACITY {
        stack.ingest(packet.clone()).unwrap();
    }
    assert_eq!(stack.ingest(packet), Err(PacketError::QueueFull));
    assert_eq!(stack.ingest(vec![0; 1281]), Err(PacketError::TooLarge));
    assert_eq!(stack.ingest(vec![0; 19]), Err(PacketError::Invalid));
}

#[test]
fn invalid_endpoints_are_typed_failures() {
    assert!(matches!(
        TcpStack::new(Ipv4Addr::UNSPECIFIED, 1),
        Err(StackError::InvalidEndpoint)
    ));
    let mut stack = TcpStack::new(Ipv4Addr::new(10, 221, 0, 1), 1).unwrap();
    assert_eq!(stack.listen(0), Err(StackError::InvalidEndpoint));
    assert_eq!(
        stack.connect(SocketAddrV4::new(stack.address(), 25565)),
        Err(StackError::InvalidEndpoint)
    );
}

#[test]
fn tcp_recovers_loss_duplication_and_reordering_without_corrupting_stream() {
    let mut client = TcpStack::new(Ipv4Addr::new(10, 221, 0, 1), 11).unwrap();
    let mut host = TcpStack::new(Ipv4Addr::new(10, 221, 0, 2), 22).unwrap();
    let server = host.listen(25565).unwrap();
    let connection = client
        .connect(SocketAddrV4::new(host.address(), 25565))
        .unwrap();
    let payload: Vec<u8> = (0..90_000).map(|n| (n % 239) as u8).collect();
    let mut sent = 0;
    let mut received = Vec::new();
    let mut datagrams = 0;
    let mut loss_exercised = 0;
    let mut duplicates_exercised = 0;
    for tick in 0..6000 {
        let now = Duration::from_millis(tick * 5);
        client.poll(now);
        host.poll(now);
        let mut batch = Vec::new();
        while let Some(packet) = client.take_packet() {
            datagrams += 1;
            if datagrams % 11 == 0 {
                loss_exercised += 1;
                continue;
            }
            if datagrams % 7 == 0 {
                batch.push(packet.clone());
                duplicates_exercised += 1;
            }
            batch.push(packet);
        }
        for packet in batch.into_iter().rev() {
            host.ingest(packet).unwrap();
        }
        transfer(&mut host, &mut client);
        if sent < payload.len() {
            match client.write(connection, &payload[sent..]) {
                Ok(count) => sent += count,
                Err(StackError::WouldBlock) => {}
                error => panic!("unexpected impaired write {error:?}"),
            }
        }
        let mut buf = [0; 4096];
        if let ReadResult::Data(count) = host.read(server, &mut buf).unwrap() {
            received.extend_from_slice(&buf[..count]);
        }
        if received.len() == payload.len() {
            break;
        }
    }
    assert!(loss_exercised > 0 && duplicates_exercised > 0);
    assert_eq!(sent, payload.len());
    assert_eq!(received, payload);
}

#[test]
fn established_connection_times_out_when_peer_disappears() {
    let mut client = TcpStack::new(Ipv4Addr::new(10, 221, 0, 1), 11).unwrap();
    let mut host = TcpStack::new(Ipv4Addr::new(10, 221, 0, 2), 22).unwrap();
    let server = host.listen(25565).unwrap();
    let connection = client
        .connect(SocketAddrV4::new(host.address(), 25565))
        .unwrap();
    for tick in 0..100 {
        let now = Duration::from_millis(tick * 5);
        client.poll(now);
        host.poll(now);
        transfer(&mut client, &mut host);
        transfer(&mut host, &mut client);
    }
    assert!(client.established(connection).unwrap());
    assert!(host.established(server).unwrap());
    for tick in 100..4100 {
        client.poll(Duration::from_millis(tick * 5));
        while client.take_packet().is_some() {}
    }
    assert!(client.is_closed(connection).unwrap());
}
