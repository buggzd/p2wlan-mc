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

Each endpoint has at most 32 sockets, 32 KiB receive/send buffers per socket, and 128 queued IP packets per direction. The raw-IP receive cap is 1280 bytes. A standalone TCP stack defaults to the same transmit ceiling and supports an explicit 576–1280-byte packetization ceiling without changing its receive cap. TCP inactivity timeout is 15 seconds and keep-alive is 5 seconds. Packets and connections over budget return typed failures or apply backpressure; existing work is not evicted to admit new work.

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

Each IP frame retains its original room lease. A healthy room renewal or an unrelated member joining/leaving can keep the TCP connection eligible, but cannot extend a queued frame's expired lease or replace its original roster snapshot. Per-member lifetime identities are maintained under the original room owner's snapshot lock, not in a separate authorization roster. Revocation, reauthorization after expiry, peer removal/rejoining, remote registration changes, runtime shutdown, and network handover invalidate old capabilities. Inbound admission retains the actual authenticated crypto instance before post-decrypt awaits and both inbound queues; an admitted packet cannot be freshly reauthorized. Receive-only crypto overlap is accepted only while the original transport still retains that instance. Crypto-owner contention retains the original already-decrypted envelope; it does not acquire a fresh grant, lease or session. Contention waits and capacity reservation share one 250 ms operation deadline, with no lock held during waiting. An unknown or retired authenticated instance is rejected, and the original envelope is revalidated immediately before each queue commit and local write.

The captured application fence accompanies the original room-send permit through routing and encryption to the original UDP/Relay final-writer checks. Regression tests exercise the actual upstream UDP writer, including revocation after encryption, bounded queues, retained scope identities, and a real 128 KB bidirectional TCP stream through authorized dataplanes, Noise sessions, and upstream UDP receive/decrypt workers. These tests do not establish a complete Control/daemon or Relay lifecycle, Minecraft login, public NAT, or installable Mod support.

## TCP receive-buffer admission

The gateway crate provides checked IPv4/TCP wire metadata and a bounded admission ledger for unread stream bytes. It validates IP/TCP checksums, rejects fragmented input, handles 32-bit TCP sequence wrap, and conservatively preserves all receive capabilities across reordering, overlap, retransmission, and partial reads. Each connection retains at most 128 admission spans within the bridge's 32 KiB receive window. Capacity exhaustion never evicts prior admissions; completed reads release their metadata.

Read chunks are limited to 8 KiB and retain their original authenticated crypto instance, room lease, and application scope identities. The protected chunk exposes no raw byte slice: each local TCP write revalidates those capabilities immediately before the socket syscall. A failed coverage check poisons the admission ledger because bytes already removed from the TCP stack must not be newly authorized. Binding receive admission to a connection adds its scopes without acquiring a fresh lease or crypto instance.

The component tests use actual upstream room/peer/Noise capabilities and real local TCP sockets, including revocation between readiness and the final write. This admission component is not yet a complete TCP gateway, Agent, or Minecraft adapter.

## Scoped local TCP connection bridge

`ConnectionBridge` binds one local socket to one non-reusable virtual socket identity. It retains at most one 8 KiB chunk in each direction, rechecks connection/world grants when attaching delayed connect results and transferring buffered bytes, and preserves TCP half-close. An already attached local destination cannot be replaced. Application-byte inactivity is bounded to 120 seconds, separate from TCP keep-alives and peer-loss detection.

The connection test exchanges 128 KB in both directions between real local sockets through the original encrypted UDP receive workers and user-space TCP stack. It also checks delayed attachment after scope closure and foreign-stack identity rejection. The gateway below seals raw stack output before removing or replacing a flow. The connection bridge alone is not the networking Agent.

## Native service and join-proxy gateway

`TcpGateway` is the single owner of hosted-service registrations, loopback join proxies, the user-space TCP stack, and local connection bridges. It keeps at most eight registrations, 32 virtual TCP sockets (including service listeners), 32 accepted local handshakes/active bridges combined, and 32 bounded target-connect tasks. Accepted hosted streams each receive an independent bridge; the service re-arms its listener only when socket capacity exists. Service virtual ports and registration IDs are not reused within the process; exhaustion is explicit.

Local endpoints use a 40-byte prelude: an eight-byte protocol marker and a 32-byte CSPRNG capability. Join proxies read exactly this prelude before admitting game bytes. Hosted targets must be loopback world guards using the corresponding capability, not bare Minecraft ports. Prelude and target-connect deadlines are two seconds. Hosted targets must acknowledge readiness before the native connection bridge attaches; that acknowledgement is consumed locally and never becomes a game byte. Closing a registration cancels its handshakes/connect tasks and revokes its bridges; stale IDs cannot stop replacements. Peer revocation closes the associated join proxy. Runtime/network changes or packet-port owner loss close the gateway.

Raw stack output is sealed with the current flow's original fenced grant before flow removal. A 128-frame backpressure queue retains those envelopes for at most 250 ms and never refreshes authorization on retry. Receive contention retains the original envelope in a single additional packet-port slot with the same 250 ms total deadline. Status exposes bounded resource counts, a saturating failure count, and typed failure reasons rather than interpreting logs.

The gateway tests carry three concurrent, capability-guarded local streams through the original encrypted UDP workers, including fragmented preludes, coalesced game bytes, bidirectional payloads, and half-close. They also verify registration/handshake capacity, shutdown, peer and port-owner revocation, and stale proxy backlog rejection. The target guards in the native gateway tests are fixtures. The Java guard below is separately tested; the managed account/room entry is described below. Fabric lifecycle/UI and Minecraft login/world integration remain unavailable.

## Java world guard

`WorldGuard` owns one immutable world identity and capability-protected loopback endpoint. A single nonblocking selector thread forwards at most 32 accepted connections, with two 8 KiB buffers per connection. The guard reads exactly the 40-byte capability prelude, connects only to a validated loopback game target, and sends a one-byte readiness acknowledgement only after that target connects. Handshake/target readiness has a two-second total deadline; stream-byte inactivity is bounded to 120 seconds. Partial writes retain their buffers and TCP half-close is preserved.

The Minecraft adapter must revoke the guard on its game thread before closing or replacing the world. Revocation is nonblocking, wakes the selector, and is checked immediately before actual local socket I/O. `close` additionally waits up to two seconds for thread/resource termination. Immutable status snapshots identify their world owner; they are not a copy of native peer/path/session state. Local capabilities are redacted from string representations and are sent only through private IPC, never process arguments or game payloads.

```sh
python3 minecraft-mod/tools/java.py test
python3 minecraft-mod/tools/java.py interop
```

The tests exercise actual local sockets, fragmented/coalesced preludes, bidirectional payloads, half-close, capacity, deadlines, and target failure. They deliberately reuse a stopped guard's loopback port and verify that its old capability cannot connect to the replacement world. The interop command additionally exchanges a 128 KB TCP stream between the native wire peer and Java guard. This proves the guard/loopback contract, not Minecraft startup/login or a supported loader/version matrix.

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

The subprocess tests use actual native fault-fixture processes to verify concurrency, queue bounds, timeout, malformed/partial replies, stale snapshots/owners, immutable completions, stderr pressure, process replacement, and child/thread cleanup. They do not prove Control login, room management, the networking Agent lifecycle, or Minecraft integration.

## Native Agent entry and runtime supervisor

The independent `p2wlan-mc-agent` executable uses the framed IPC protocol and CSPRNG process/runtime owners. It accepts `ping`, `status`, `peer_status`, `start`, `stop`, `host`, `proxy`, `close_registration`, and `shutdown` operations. `start` consumes a private IPC configuration for an authenticated, managed `room-` network and returns a fenced runtime ID while startup proceeds; it is not an account-login operation. OS interface/routing integration remains disabled by the authorized userspace backend, and this entry disables daemon diagnostics listeners, DNS and port mappings.

The Agent owns one original daemon and one gateway. Startup is bounded to 15 seconds and daemon stop to two seconds. Host/proxy/stop commands must identify that exact runtime; registration closure also checks the caller's immutable scope/world tag. Binding metadata is bounded by and revalidated against the gateway's authoritative registrations. The stdin producer owns a revocable lifetime token that survives through final upstream packet writes: EOF or invalid input invalidates old application grants even while cleanup is pending. No extra fifth scope is required. A 32-request queue has a 250 ms admission deadline, the idle heartbeat limit is six seconds, and the native runtime uses two async worker threads with at most eight blocking workers.

Runtime status projects `peer_id` from the original daemon's authenticated, resolved startup configuration, not the pre-registration ID cached in a room profile. Application-admission failures retain their stable native reason codes; `gateway_failure` projects the gateway owner's latest failure without exposing endpoints or payloads. The gateway's TCP transmit ceiling comes from the upstream DPLPMTUD conservative UDP baseline minus its WireGuard framing overhead (currently 1168 bytes), capped by the raw-IP receive limit. It does not independently estimate paths or raise the transmit ceiling when path probing discovers a larger MTU. Kernel PMTU feedback is not consumed by this userspace endpoint: the adapter counts it as `LOCAL_MTU_FEEDBACK_UNSUPPORTED` without claiming delivery or terminating the daemon. Status exposes this count as `unsupported_local_feedback`; generic interface writes cannot acquire a fresh peer/world grant. Authenticated game receive still requires the original encrypted ingress envelope.

`phase: ready` means the packet interface and gateway exist, not that a peer's business path or game target is reachable. Status projects fixed, typed packet-port counters through `admission_drops`. `peer_status` requires the current runtime and one authorized peer ID; it reads the original committed path, peer/network identities, authoritative UDP publication and one DPLPMTUD snapshot without maintaining a roster or path cache. Its projections exclude endpoints, keys, credentials and payloads. Diagnostic identities use exact decimal strings. Views are not send permits: actual forwarding still revalidates the original capabilities.

Inner application IPv4 can travel through outer UDP IPv4 or IPv6. The build-only MTU adapter resolves the original committed remote endpoint's address family using the upstream endpoint-aware socket resolver. Exact local socket, validation owner, network/session/candidate epoch and publication checks remain with the original runtime; the adapter does not bypass business-budget confirmation or infer a new path.

Encrypted control payloads remain with the original transport's rekey, Direct validation, DPLPMTUD, Relay probe and path-commit handlers. Some of these use valid IPv4 envelopes; IP parsing alone is not a business/control classifier. Only non-control application IP packets acquire the extra Mod receive capability before post-decrypt awaits. The build transform checks the upstream control-family contract and fails closed if it changes.

Request IDs may arrive out of order, but may not repeat. Non-evicting ID ranges compact adjacent IDs and are bounded to 128 disjoint intervals; capacity failure terminates the IPC session rather than allowing replay. Replies and increasing-revision status events retain the native owner. Configuration, credentials and local capabilities are never written into replies as diagnostics or used to drive control flow through logs.

```sh
python3 minecraft-mod/tools/native.py build -p p2wlan-mc-agent --locked
python3 minecraft-mod/tools/native.py test -p p2wlan-mc-agent
```

Actual-child tests verify owner uniqueness, out-of-order request handling, typed command rejection, duplicate/foreign-owner termination, stdin EOF, heartbeat expiry and bounded shutdown. Service discovery, native packaging/extraction and Minecraft adapters remain unavailable; this executable is not yet an installable Mod.

## Managed account and room profiles

Launch the native Agent with `--profile-dir DIRECTORY` to enable `login`, `register`, `logout`, `rooms`, `create_room`, `join_room`, `leave_room` and `connect_room` IPC operations. Login/register take `server`, `identifier` and `password`; credentials travel only in private IPC, never command arguments. Account/room operations require an idle runtime. Connecting accepts a room ID or eight-digit code from the authenticated account's own room list. Managed profiles reject the raw `start` operation.

The REST client accepts a root HTTPS origin, or HTTP only on loopback. It disables ambient proxies and redirects, uses a one-second connect deadline and 2.5-second total request deadline, and limits each response body to 1 MiB. The whole account command has a three-second deadline. Room lists contain at most 64 projected records; IPC exposes only room ID/code/name/CIDR/role, not membership arrays, passwords or credentials. Profile publication and runtime start revalidate the parent lifetime after HTTP awaits.

Unix profiles require a same-user directory and regular single-link files with no group/other access; new directories/files are created with `0700`/`0600` permissions. An exclusive lock prevents concurrent profile owners. Reads/publication stay relative to a pinned directory descriptor; directory or lock replacement fails closed. Account loading rejects unknown fields/versions, unsafe origins and oversized files. Logout atomically replaces account state with `null` without silently deleting retained room identities. Windows/non-Unix profile persistence returns `PROFILE_UNSUPPORTED_PLATFORM` pending private ACL implementation; it does not write credentials with unverified permissions.

Room node identities are retained separately for each Control origin, account ID and room ID. The profile retains at most 32 room identities with no implicit eviction and limits directory enumeration to 256 entries. Device names are explicitly `Minecraft Mod`, not the host name. The account JWT and the daemon-owned room configuration are separate: room reconnect reuses its node identity while the original daemon owns registration credentials and boot incarnation. Configurations and keys must not be shared or committed.

The integration fixture builds against the unchanged Go Control and runs actual native child processes in a temporary private directory. It verifies account/room operations, distinct virtual addresses for two managed daemons, fenced stop/reconnect, retained room identities across process replacement, EOF cleanup while connected, and leave/logout. The default mode does not exercise application traffic. With `--traffic`, it additionally waits up to 15 seconds for both original committed business paths (including Direct budget readiness), then transfers three concurrent, capability-guarded 128 KiB TCP streams in both directions, checks half-close and rejects an old service after replacement. The target is a bounded loopback fixture, not a Minecraft server. No Relay service is launched or forced; this does not verify Relay fallback, Minecraft login/world lifecycle, public NAT or a loader/version matrix.

With Rust and the Go toolchain required by `server/go.mod`, from the repository root:

```sh
python3 minecraft-mod/tools/native.py build -p p2wlan-mc-agent --locked
mkdir -p minecraft-mod/native/target/server
go -C server build -mod=readonly -o ../minecraft-mod/native/target/server/p2wlan-control .
python3 minecraft-mod/tools/control_smoke.py --control minecraft-mod/native/target/server/p2wlan-control --agent minecraft-mod/native/target/debug/p2wlan-mc-agent
python3 minecraft-mod/tools/control_smoke.py --control minecraft-mod/native/target/server/p2wlan-control --agent minecraft-mod/native/target/debug/p2wlan-mc-agent --traffic
```

The fixture uses isolated loopback Control/database state, excludes inherited Control/Relay credentials and ambient proxy settings, discards child logs, generates disposable fixture credentials, enforces readiness/exit deadlines and removes temporary files. Required executables must exist; missing inputs fail rather than skipping integration checks.
