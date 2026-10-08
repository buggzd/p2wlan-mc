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

The native build generates an adapted runtime under the ignored `native/target/upstream-runtime/` directory. It reuses this checkout's upstream daemon and networking crates without modifying their source or manifests. Exact source anchors are checked before generation; incompatible upstream changes fail the build rather than silently selecting another code path. Generated metadata records source hashes and the adapter hash.

The packet port substitutes bounded user-space IP channels for the daemon's system interface. Only this backend skips OS route installation; the normal daemon entry point retains its original system-interface behavior. The port requires the bridge's 1280-byte MTU and applies a 250 ms write deadline. The formatting wrapper selects only Mod-owned workspace packages, including when called with `fmt --all`.

```sh
python3 -m unittest discover -s minecraft-mod/tests -p 'test_*.py'
python3 minecraft-mod/tools/native.py test --workspace
```

The runtime-port integration test carries a real 128 KB bidirectional TCP stream through upstream dataplanes, the upstream Noise handshake/encrypted transport, and loopback UDP. It does not yet verify the full Control/daemon lifecycle, Direct/Relay selection, Minecraft login, or public NAT. The raw packet port alone is not a production-authorized Minecraft service.

## Native IPC codec

The native protocol crate applies the same object, depth, container, node, Unicode, number-token, and decimal-scale limits as the common Java codec. Numeric IDs and decimal values are retained without floating-point rounding. Idle reads are owned by the caller's session lifetime; a partially received frame has a total two-second deadline. Cancelling a read retains its header/body progress and original deadline. Cancelling or failing a write permanently poisons that writer, preventing a second request from being appended to a partial frame.

```sh
python3 minecraft-mod/tools/native.py test -p p2wlan-mc-protocol
python3 minecraft-mod/tools/java.py interop
```

The interop command builds a native codec test peer and exchanges actual framed messages with Java, including Unicode, large payloads, exact integers/decimals, and EOF. The peer is a conformance example, not the networking Agent.
