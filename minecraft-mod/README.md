# P2WLAN Minecraft integration

This directory is an independent project. It does not alter the upstream Cargo workspace or existing P2WLAN client/server sources.

## Native TCP endpoint

The native bridge provides a user-space IPv4/TCP endpoint over bounded raw-IP queues. It creates no TUN device and installs no system routes. A single owner drives time, packet input/output, and socket state. Connection IDs are monotonic identities rather than reusable socket slots.

From the repository root:

```sh
python3 minecraft-mod/tools/native.py test
python3 minecraft-mod/tools/native.py fmt --all -- --check
python3 minecraft-mod/tools/native.py clippy --workspace --all-targets -- -D warnings
```

Each endpoint has at most 32 sockets, 32 KiB receive/send buffers per socket, and 128 queued IP packets per direction. The initial IP MTU is 1280 bytes. TCP inactivity timeout is 15 seconds and keep-alive is 5 seconds. Packets and connections over budget return typed failures or apply backpressure; existing work is not evicted to admit new work.

The bridge tests exercise real user-space TCP, half-close, resource limits, timeout, and recovery from loss, duplication, and reordering. This component alone is not an installable Mod.

## Common Java protocol

The common Java module contains no Minecraft or loader classes. It implements 64 KiB length-prefixed, strict UTF-8 JSON IPC, with duplicate-key, nesting, element-count, and numeric bounds. Native-owned status snapshots are copied and admitted only for the current process owner and increasing revision. Late cleanup from an old owner cannot close a new session.

Java 21 is required to run the project build; the common module is compiled to Java 17 bytecode. The Gradle 8.14 wrapper verifies its fixed distribution checksum. From the repository root:

```sh
python3 minecraft-mod/tools/java.py test
```

The protocol test is an explicit executable test harness, not a zero-test Gradle success. It verifies malformed/partial frames, Unicode, capacity limits, snapshot immutability, and stale-owner/revision rejection.

## Upstream runtime packet port

The native build generates an adapted runtime under the ignored `native/target/upstream-runtime/` directory. It reuses this checkout's upstream daemon and networking crates without modifying their source or manifests. Exact source anchors are checked before generation; incompatible upstream changes fail the build rather than silently selecting another code path. Generated metadata records upstream source, Rust adapter, and build-transform hashes.

The packet port substitutes bounded user-space IP channels for the daemon's system interface. Only this backend skips OS route installation; the normal daemon entry point retains its original system-interface behavior. The port requires the bridge's 1280-byte MTU and applies a 250 ms write deadline. The formatting wrapper selects only Mod-owned workspace packages and explicitly formats the tracked Rust overlays without following their module paths, including when called with `fmt --all`.

```sh
python3 -m unittest discover -s minecraft-mod/tests -p 'test_*.py'
python3 minecraft-mod/tools/native.py test --workspace
```

The runtime-port integration test carries a real 128 KB bidirectional TCP stream through upstream dataplanes, the upstream Noise handshake/encrypted transport, and loopback UDP. It does not yet verify the full Control/daemon lifecycle, Direct/Relay selection, Minecraft login, or public NAT. The raw packet port alone is not a production-authorized Minecraft service.

## Authorized application packet port

The authorized backend admits IPv4 packets using immutable capabilities over the original daemon's peer lifecycle, network generation, room membership, and transport-session owners. Grants belong to one exact runtime authority and one exact packet port, even if peer IDs and addresses coincide. Callers can attach non-cloneable scope owners to application lifetimes; closing those owners revokes queued work. Dropping the packet interface automatically revokes its port scope. A grant holds at most four distinct scope identities; additional identities fail closed.

Each IP frame retains its original room lease. A healthy room renewal or an unrelated member joining/leaving can keep the TCP connection eligible, but cannot extend a queued frame's expired lease or replace its original roster snapshot. Per-member lifetime identities are maintained under the original room owner's snapshot lock, not in a separate authorization roster. Revocation, reauthorization after expiry, peer removal/rejoining, remote registration changes, runtime shutdown, and network handover invalidate old capabilities. Inbound admission retains the actual authenticated crypto instance before post-decrypt awaits and both inbound queues; an admitted packet cannot be freshly reauthorized. Receive-only crypto overlap is accepted only while the original transport still retains that instance. Capacity reservations have a 250 ms deadline and are revalidated before committing packets.

The captured application fence accompanies the original room-send permit through routing and encryption to the original UDP/Relay final-writer checks. Regression tests exercise the actual upstream UDP writer, including revocation after encryption, bounded queues, retained scope identities, and a real 128 KB bidirectional TCP stream through authorized dataplanes, Noise sessions, and upstream UDP receive/decrypt workers. These tests do not establish a complete Control/daemon or Relay lifecycle, Minecraft login, public NAT, or installable Mod support.

## TCP receive-buffer admission

The gateway crate provides checked IPv4/TCP wire metadata and a bounded admission ledger for unread stream bytes. It validates IP/TCP checksums, rejects fragmented input, handles 32-bit TCP sequence wrap, and conservatively preserves all receive capabilities across reordering, overlap, retransmission, and partial reads. Each connection retains at most 128 admission spans within the bridge's 32 KiB receive window. Capacity exhaustion never evicts prior admissions; completed reads release their metadata.

Read chunks are limited to 8 KiB and retain their original authenticated crypto instance, room lease, and application scope identities. The protected chunk exposes no raw byte slice: each local TCP write revalidates those capabilities immediately before the socket syscall. A failed coverage check poisons the admission ledger because bytes already removed from the TCP stack must not be newly authorized. Binding receive admission to a connection adds its scopes without acquiring a fresh lease or crypto instance.

The component tests use actual upstream room/peer/Noise capabilities and real local TCP sockets, including revocation between readiness and the final write. This admission component is not yet a complete TCP gateway, Agent, or Minecraft adapter.

## Native IPC codec

The native protocol crate applies the same object, depth, container, node, Unicode, number-token, and decimal-scale limits as the common Java codec. Numeric IDs and decimal values are retained without floating-point rounding. Idle reads are owned by the caller's session lifetime; a partially received frame has a total two-second deadline. Cancelling a read retains its header/body progress and original deadline. Cancelling or failing a write permanently poisons that writer, preventing a second request from being appended to a partial frame.

```sh
python3 minecraft-mod/tools/native.py test -p p2wlan-mc-protocol
python3 minecraft-mod/tools/java.py interop
```

The interop command builds native conformance peers and exchanges actual framed messages with Java, including Unicode, large payloads, exact integers/decimals, and EOF. These peers are test fixtures, not the networking Agent.

## Common Java subprocess client

`AgentClient` owns one explicitly launched child process. It negotiates IPC version and native owner, correlates monotonic request IDs, keeps at most 32 pending requests and 32 queued frames, and sends a two-second heartbeat. Handshake/request timeouts default to five seconds and may be selected between 50 ms and 30 seconds. A timeout, damaged stream, wrong owner, unknown/duplicate reply, or unexpected exit terminates that exact child and fails outstanding work with stable reason codes. Requests with uncertain delivery are not replayed.

Command completions retain their native owner and request ID in immutable `AgentReply` values. Consumers must marshal callbacks onto their own game thread and check `isCurrent` there before producing a side effect; process identity does not replace a world/service identity check. Status snapshots use the same native-owner/increasing-revision fence. No lock crosses pipe I/O or future completion. The watchdog, reader, writer, and exit watcher are bounded by that child lifecycle; stderr is discarded rather than parsed as state or copied into game logs.

```sh
python3 minecraft-mod/tools/java.py test
python3 minecraft-mod/tools/java.py interop
```

The subprocess tests use actual native fault-fixture processes to verify concurrency, queue bounds, timeout, malformed/partial replies, stale snapshots/owners, immutable completions, stderr pressure, process replacement, and child/thread cleanup. They do not provide Control login, room management, the networking Agent executable, or Minecraft integration.
