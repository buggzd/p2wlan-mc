# P2WLAN Minecraft integration

This directory is an independent, experimental integration project. It does not alter the upstream Cargo workspace or existing P2WLAN client/server sources. The adapter is pinned to Fabric/Minecraft 1.21.1; successful remote Minecraft login, integrated-world hosting and sustained gameplay have not yet been accepted.

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

Each hosted registration also receives a fresh 256-bit remote service identity. `host` returns its canonical 64-character lowercase hexadecimal `service` value; `proxy` requires that exact value in addition to peer and port. It is selection metadata, not a replacement for room/peer authorization. Across the encrypted virtual TCP stream, the proxy sends a separate fixed 40-byte service-selection prelude. The host validates it before opening any local world-guard connection and sends a distinct 40-byte readiness response only after that immutable guard admits the target. Both peers consume those bytes locally; they never reach Minecraft. The total remote handshake deadline is five seconds, with at most 40 retained one-byte input fragments per existing flow. Original authenticated receive envelopes survive target-connect awaits and are revalidated at each actual local socket operation and delayed attachment. Transient validation contention retries only those exact envelopes within the existing deadlines. A completed local target may be retained in its existing flow until the five-second remote handshake deadline; no new connection, grant, readiness read or game delivery is replayed. Status exposes the bounded ready-target count. Registration/process replacement creates a new identity, so a fresh peer grant and a reused port cannot authorize an old world selection. Callers can obtain these selection identities through the one-shot discovery operation below; manually supplied identities remain subject to the same mandatory handshake.

Raw stack output is sealed with the current flow's original fenced grant before flow removal. A 128-frame backpressure queue retains those envelopes for at most 250 ms and never refreshes authorization on retry. Receive contention retains the original envelope in a single additional packet-port slot with the same 250 ms total deadline. Status exposes bounded resource counts, a saturating failure count, and typed failure reasons rather than interpreting logs.

The gateway tests carry three concurrent, capability-guarded local streams through the original encrypted UDP workers, including fragmented preludes, coalesced game bytes, bidirectional payloads, and half-close. They also verify registration/handshake capacity, shutdown, peer and port-owner revocation, stale proxy backlog rejection, and rejection of old world metadata before any target accept. The target guards in the native gateway tests are fixtures. The Java guard below is separately tested; the managed account/room entry is described below. Minecraft login/world integration remains unavailable.

## One-shot service discovery

`discover` IPC accepts exactly `runtime` and `peer`. It queries one currently authorized room peer over the existing encrypted packet port, using virtual IPv4/UDP port 1023; it opens no system UDP listener and creates no TCP connection or world-guard admission. The reply contains that peer's request-time hosted-registration snapshot as at most eight `{port, service}` entries in ascending port order. Join-proxy registrations, local capabilities, addresses, credentials and account details are not included.

Each gateway permits one in-flight query and one response, with a fresh 128-bit random ticket and a two-second total Agent deadline including peer admission. The fixed packet format fits in one bounded datagram, validates lengths/counts/checksums, and rejects duplicate ports/identities. No background scan or discovery cache is created. Loss recovery sends this read-only query at most three times, 500 ms apart, retaining its exact original nonce, permission envelope, scope and two-second deadline. Each transmission is newly encrypted by the original daemon; encrypted frames and game delivery are never replayed, and no fresh peer grant is acquired for a retry. Queries use a revocable scope through final native send, retain the original authenticated reply envelope, and revalidate it before publication. Expiry, cancellation, parent/runtime shutdown and network replacement invalidate the captured query. Stable reasons distinguish busy, timeout and stale queries; status exposes the current query count and bounded, saturating typed gateway-failure counts.

The Agent keeps driving existing TCP flows during its discovery wait. A discovered entry is selection metadata, not live availability or a new authorization grant. Creating a join proxy reacquires the original daemon's peer authorization and still requires the exact remote service identity; stale entries cannot bypass the world-selection handshake. The complete Control/daemon traffic fixture queries the real encrypted catalog and verifies it changes on hosted-world replacement before attempting stale selection.

## Authorized room-member selection

`room_peers` accepts exactly `runtime` and returns that runtime plus `view` containing a decimal-string `network_generation` and a sorted `peers` array. This is a request-time projection of the original daemon's leased room authorization, not a second roster or online-state owner. It includes authorized offline members; membership alone does not prove discovery, connection or game readiness. Discovery and proxy registration separately acquire and validate the original peer grants.

The projection contains at most 128 peer IDs, each at most 256 UTF-8 bytes and without control characters. An oversized roster fails completely with `ROOM_PEER_CAPACITY`; invalid IDs fail with `ROOM_PEER_INVALID`. A contended room owner, revoked/expired authorization, changed network, stopped runtime or revoked gateway scope fails explicitly. No background enumeration, partial result, independent roster cache or network wait is introduced. Local node IDs, device names, endpoints, account data, virtual addresses and credentials are not returned by this operation. Runtime-port tests exercise the real room owner, capacity, ordering, expiry, revocation and lifetime fences; subprocess tests also verify IPC argument/runtime rejection.

The common Java `RoomPeers` and `ServiceCatalog` parsers retain the native reply owner and exact runtime selection. They enforce complete field sets, unsigned 64-bit generation strings, bounded valid peer IDs, native UTF-8 ordering, at most eight sorted unique service ports/identities, and immutable lists. These are selection snapshots, not permission, online state or continuously updated caches; callers must recheck process/runtime identity on the destination thread before using them. Synthetic Java catalog tests do not establish live discovery or game compatibility.

## Java join-registration lifetime

`JoinProxy` owns one game-join intent and its exact native `proxy` registration. It retains the selected process/runtime, peer, virtual port and remote service identity; native admission still acquires the original authorization. One JVM slot and one daemon worker are allowed, with no work queue or mutation replay. Native requests have a five-second deadline; parent/runtime revocation is checked every 100 ms. Cleanup uses the exact registration and fresh local attempt identity. Uncertain cleanup closes/reaps the captured helper; a known unsent or runtime-replaced cleanup cannot terminate the replacement runtime. Capacity is released only after known cleanup or actual child reaping.

`ready()` yields a validated, redacted loopback capability only. It does **not** prove a remote server, Minecraft login, byte delivery or an active world. The caller must revalidate `isCurrent()` before opening a connection or releasing game bytes, and revoke the owner on cancellation/disconnect. Actual subprocess fixtures test capacity, late registration after cancellation, parent/process exit, malformed replies, cleanup failure and runtime replacement. They are not Minecraft or live proxy acceptance tests.

## Fabric join-preface transport component

The Fabric adapter's `ProxyPrelude` component wraps one Netty initializer/socket. It validates the exact loopback proxy target, writes the 40-byte local capability before installing any Vanilla initializer/codecs or emitting their channel-active event, and then forwards every received byte as game data. Native join proxies send no local readiness acknowledgement; this component must not consume an imagined acknowledgement from the Minecraft stream. A two-second total deadline starts at handler installation and covers connect/prelude, including sockets that never become active. One 100 ms lifecycle timer closes an idle retired attempt. Both timers are cancelled on close/removal. No game-byte buffer, work queue, reconnect or capability replay is added.

The component fences inbound/outbound pipeline delivery against its attempt owner and closes on out-of-order traffic, wrong target, expiry or revocation. Actual Netty/socket tests check delayed initialization, ordering, timeout, cancellation, late write completion, target mismatch, revoked buffer release and a 128 KB round trip. The component is **not yet wired to Minecraft's connection screen/initializer**; these tests do not establish game joining, loader startup, online authentication or remote world ticking.

## Scoped Minecraft client bootstrap hook

The client-only, required `ClientConnectionMixin` redirects the exact Fabric 1.21.1 `ClientConnection.connect(InetSocketAddress, boolean, ClientConnection)` initializer installation. `ScopedClientConnect` captures one exact connection identity and loopback capability in a synchronous thread-local scope before asynchronous channel initialization. The hook installs `ProxyPrelude` only for this explicit operation; ordinary Vanilla connections and queries keep their original initializer. Missing transformation, nested/repeated consumption, wrong connection/target and retired attempts fail with stable `JOIN_BOOTSTRAP_*` codes. No address-matching registry, background work queue or transport retry is added.

The client configuration is explicitly client-only in Fabric metadata and has its own required refmap. Archive verification checks both configurations, their exact game-method mappings and class versions. Bootstrap admission tests use fake marker identities, not Minecraft transformation; they do not prove real client startup or gameplay. The game connection screen has not yet been connected to this hook.

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

Actual-child tests verify owner uniqueness, out-of-order request handling, typed command rejection, duplicate/foreign-owner termination, stdin EOF, heartbeat expiry and bounded shutdown. These process-only tests do not prove playable Minecraft integration.

## Managed account and room profiles

Launch the native Agent with `--profile-dir DIRECTORY` to enable `login`, `register`, `logout`, `rooms`, `create_room`, `join_room`, `leave_room` and `connect_room` IPC operations. Login/register take `server`, `identifier` and `password`; credentials travel only in private IPC, never command arguments. Account/room operations require an idle runtime. Connecting accepts a room ID or eight-digit code from the authenticated account's own room list. Managed profiles reject the raw `start` operation.

The REST client accepts a root HTTPS origin, or HTTP only on loopback. It disables ambient proxies and redirects, uses a one-second connect deadline and 2.5-second total request deadline, and limits each response body to 1 MiB. The whole account command has a three-second deadline. Room lists contain at most 64 projected records; IPC exposes only room ID/code/name/CIDR/role, not membership arrays, passwords or credentials. Profile publication and runtime start revalidate the parent lifetime after HTTP awaits.

Unix profiles require a same-user directory and regular single-link files with no group/other access; new directories/files are created with `0700`/`0600` permissions. An exclusive lock prevents concurrent profile owners. Reads/publication stay relative to a pinned directory descriptor; directory or lock replacement fails closed. Account loading rejects unknown fields/versions, unsafe origins and oversized files. Logout atomically replaces account state with `null` without silently deleting retained room identities. Windows/non-Unix profile persistence returns `PROFILE_UNSUPPORTED_PLATFORM` pending private ACL implementation; it does not write credentials with unverified permissions.

Room node identities are retained separately for each Control origin, account ID and room ID. The profile retains at most 32 room identities with no implicit eviction and limits directory enumeration to 256 entries. Device names are explicitly `Minecraft Mod`, not the host name. The account JWT and the daemon-owned room configuration are separate: room reconnect reuses its node identity while the original daemon owns registration credentials and boot incarnation. Configurations and keys must not be shared or committed.

The integration fixture builds against the unchanged Go Control and runs actual native child processes in a temporary private directory. It verifies account/room operations, distinct virtual addresses for two managed daemons, fenced stop/reconnect, retained room identities across process replacement, EOF cleanup while connected, and leave/logout. The default mode does not exercise application traffic. With `--traffic`, it additionally waits up to 15 seconds for both original committed business paths (including Direct budget readiness), then transfers three concurrent, capability-guarded 128 KiB TCP streams in both directions, checks half-close, rejects an old service after replacement, and rejects old world-selection metadata paired with a fresh proxy and the replacement port before any target admission. The target is a bounded loopback fixture, not a Minecraft server. No Relay service is launched or forced; this does not verify Relay fallback, Minecraft login/world lifecycle, public NAT or a loader/version matrix.

With Rust and the Go toolchain required by `server/go.mod`, from the repository root:

```sh
python3 minecraft-mod/tools/native.py build -p p2wlan-mc-agent --locked
mkdir -p minecraft-mod/native/target/server
go -C server build -mod=readonly -o ../minecraft-mod/native/target/server/p2wlan-control .
python3 minecraft-mod/tools/control_smoke.py --control minecraft-mod/native/target/server/p2wlan-control --agent minecraft-mod/native/target/debug/p2wlan-mc-agent
python3 minecraft-mod/tools/control_smoke.py --control minecraft-mod/native/target/server/p2wlan-control --agent minecraft-mod/native/target/debug/p2wlan-mc-agent --traffic
```

The fixture uses isolated loopback Control/database state, excludes inherited Control/Relay credentials and ambient proxy settings, discards child logs, generates disposable fixture credentials, enforces readiness/exit deadlines and removes temporary files. Required executables must exist; missing inputs fail rather than skipping integration checks.

## Native resource package

A native resource package is built for the current POSIX host, not downloaded at runtime. From the repository root:

```sh
python3 minecraft-mod/tools/native_bundle.py --offline
```

Release packaging requires a clean checkout. For an explicitly labelled development package, add `--development`. The producer selects Cargo's reported release artifact, runs its bounded `--build-info` command and checks its exact protocol, version, source commit, native-input digest and compiled platform before publication. The manifest binds those fields to the executable's SHA-256 and size; the copied resource bytes are checked again. A concurrent source change, ambiguous artifact or failed verification aborts packaging.

Compilation remaps repository/home source prefixes to generic build paths; a bounded scan rejects executable bytes that still contain those absolute build prefixes.

Generated executable resources remain under the ignored `java/native-bundle/build/` directory. The Java resource subproject refuses to build an empty resource jar. The currently recognised packaging targets are macOS/Linux on aarch64/x86_64; target recognition is not evidence of device verification or an installable Minecraft Mod. Windows is not a packaging target while private-profile ACL support is absent.

The common Java `BundledAgent` entry point selects only the exact host resource, rejects missing/duplicate/non-local resources, validates the strict bounded manifest, and rejects development packages unless explicitly allowed by its caller. It extracts at most two private installations per JVM, with 0700 directories, 0500 executables, SHA-256/length checks and a 15-second extraction/verification deadline. There is no shared executable cache or permissive filesystem fallback. Startup belongs off the game thread. `close()` is nonblocking; `released()` completes only after the exact child has exited and owned-file cleanup has succeeded. Failed or replaced-file cleanup is typed and retains its capacity slot rather than permitting unbounded leftovers.

The supported filesystem boundary is POSIX isolation from other OS users plus identity-checked, non-recursive cleanup. Java's path-based launch does **not** protect against malicious code running under the same OS user, a modified JVM/classloader, or another malicious Mod. A package checksum is integrity metadata, not a signature. File/directory identity, owner, permission and hard-link checks detect observable replacement; they are not an atomic descriptor-based execution guarantee. Windows and filesystems lacking the required POSIX/Unix attributes fail closed.

The package integration check uses the actual extracted release-mode executable. Generate an explicitly labelled development resource and run:

```sh
python3 minecraft-mod/tools/native_bundle.py --development --offline
python3 minecraft-mod/tools/java.py bundle-test
```

This checks bundle validation, private extraction, replacement/symlink/hard-link rejection, exact subprocess readiness/IPC, immediate-close races and cleanup. It does not start Minecraft or prove loader compatibility.

## Fabric adapter bootstrap

The explicit Fabric adapter targets **Minecraft 1.21.1**, with Fabric Loader 0.16.14, Fabric API 0.116.7+1.21.1, Yarn 1.21.1+build.3 and Loom 1.10.5 as its pinned build inputs. Its loader metadata accepts 1.21.1 only; it does not claim support for other game versions, NeoForge or Forge. Loader/version references are confined to `java/adapters/fabric-1.21.1/`; common Java and native networking remain game-independent.

From a clean repository checkout, generate the host resource and build the adapter:

```sh
python3 minecraft-mod/tools/native_bundle.py --offline
python3 minecraft-mod/tools/java.py fabric-build
```

The explicit adapter build remaps its jar, embeds the common and native-resource jars, and checks the exact loader entrypoints, Java 21 adapter/Java 17 common bytecode, absence of game/loader references in common bytecode, source-bound native manifests and executable hashes. Empty, stale, mismatched or duplicate native resources fail the artifact check. The checker emits public metadata binding the final jar SHA-256, source commit, embedded version and native verification targets; archive verification is not Minecraft gameplay acceptance. Common Java tests do not configure Loom or fetch Minecraft. A single-host resource package does not provide executable coverage for other operating systems or architectures.

For explicitly labelled development resources and a development build:

```sh
python3 minecraft-mod/tools/native_bundle.py --development --offline
python3 minecraft-mod/tools/java.py fabric-build -PdevelopmentNative
python3 minecraft-mod/tools/java.py fabric-server -PdevelopmentNative
python3 minecraft-mod/tools/java.py fabric-client -PdevelopmentNative
```

`fabric-server` uses Loom's development server; it does not accept or rewrite Minecraft's EULA. Game/account terms must be accepted by the user before a playable server can run. The development property permits development manifests in the build check and forwards the same explicit opt-in to Loom's development JVM. A normally installed adapter rejects development-native resources by default.

The common loader entrypoint contains no client initialization. Dedicated-server lifecycle events start the native helper off the game thread and close it when the server stops. On a client, the helper lifetime follows the application rather than individual integrated worlds. The shared `AgentLifecycle` is single-use: one startup worker, one 40-second startup deadline, no retry/work queue, immediate revocation on close, and identity checks before an asynchronous client reference can be used. Late startup completion closes its own child rather than publishing a revoked owner.

The dedicated-server `/p2wlan status` command requires permission level 4. The client command is `/p2wlan-client status`. They show helper process/IPC readiness and stable startup/cleanup reason codes, **not** overlay-room connection or Minecraft-world readiness. The client exposes a P2WLAN button in the title, multiplayer and pause menus; bare `/p2wlan-client` opens the same entry. An idle helper shows account/membership controls; an active `starting`/`ready` runtime shows room controls. The screens support explicit login/register, saved-account room membership, bounded paging, refresh, create/join/leave membership and logout. Password fields mask rendering and narration, block copy/cut and clear widget retention after submission/removal/resize; Java strings are not securely zeroisable. No Minecraft/Microsoft credential is required by these screens.

A shared completion gate permits at most one native request, local intent or queued game-thread presentation. Process/view/runtime identities are rechecked at admission, the queued IPC write and the actual presentation boundary. Closing a screen discards late presentation without replaying or claiming to undo a submitted mutation. Buttons derive eligibility from native snapshots, not a second account/runtime state machine. Membership operations do **not** connect the overlay runtime; `Connect selected` is a separate intent.

The runtime screen requests a complete, bounded projection of at most 128 room members. Membership is not game readiness or a send grant. Selecting one member issues one bounded discovery request and displays at most eight opaque services, with no background scan or independent availability cache. Selecting a service opens the prototype join/cancel flow. Joining is refused while a local/remote world or another unreleased attempt is active. Native reauthorizes the selected runtime, peer and exact remote-world identity when creating the local proxy.

The join lifetime owns one native proxy, one real Minecraft connection and two one-shot completion cells, with no retry/executor queue. The required client Mixin scopes the original synchronous bootstrap to that exact connection object and loopback address; ordinary Vanilla bootstraps remain unchanged. A 40-byte capability precedes all game codecs, with a two-second total connect/prefix deadline. Native join proxies send no local ACK, so every returned byte is Minecraft data. Login begins on the game thread only after a fenced handoff, retaining Vanilla login/authentication, configuration, compression, encryption and resource-pack consent. Minecraft session credentials are not replaced. Only login/configuration connections not already owned by Vanilla's play tick are ticked by the adapter. Cancel, channel close, runtime replacement and shutdown revoke the exact local intent; a replacement cannot start until native release and channel closure. A reachable, compiled prototype is not gameplay acceptance. Real-client UI layout and full login/configuration/disconnect transitions remain unverified.


## Guarded local world publication

The Fabric adapter retains one exact dedicated/integrated server lifetime and revokes its world guard **before** server stop/replacement. A publication has one worker and one JVM capacity slot, with no queued setup or automatic retry. A revoked, delayed native `host` reply is retained only for exact registration cleanup, never published for a replacement world. Local cleanup failure holds the capacity slot; uncertain native cleanup terminates that exact helper rather than replaying a mutation. Queued world IPC requests recheck their lifetime immediately before the pipe write.

The adapter uses Minecraft's existing codecs, rate limiter, handshake handler and authoritative game-connection list, with an additional ephemeral **127.0.0.1** listener. It does not call the integrated-server open-to-LAN operation or advertise a LAN port. Existing dedicated-server listeners/configuration are unchanged: server operators remain responsible for restricting any separately configured public game port. Minecraft sees a loopback source, not the remote player's physical/virtual IP; IP-based bans or other Mods that rely on source IP need separate consideration. No forwarded-IP identity spoofing is performed.

The Minecraft-facing listener itself consumes a fresh 40-byte local capability **before** initializing Vanilla codecs/connections. The outer `WorldGuard` authenticates that target and consumes its one-byte readiness acknowledgement before acknowledging native readiness; neither local prelude/acknowledgement reaches Minecraft. Unauthenticated local users cannot bypass the outer guard by connecting directly to this listener. A single Mod-owned NIO event loop drives accept/child registration, so unauthenticated local connects cannot build an unbounded registration queue on another game event loop. At most 32 children, 40 prelude bytes per child and one two-second timer per child are retained; existing Minecraft packet/player internals remain Minecraft-owned. Target revocation fences inbound/outbound game bytes and closes owned channels. Target cleanup waits at most two seconds each for children/listener closure and three seconds for the sole event loop to terminate; failed cleanup does not release the publication slot. This still does not defend against malicious same-OS-user code or another malicious Mod.

The required, exact-version server Mixin is checked during bootstrap; absence fails before an unguarded bind. Its explicitly scoped bind reuses Vanilla's bind/stop ordering under the existing channel-list monitor, with a two-second wait limit. The Mod owns and closes its extra listener rather than accumulating closed entries in Vanilla's listener list. Ordinary Vanilla binds retain their original behavior. Full-game bind/stop competition and integrated/dedicated world behavior have not yet been accepted. A required client-only IntegratedServer Mixin derives remote/pause semantics from the exact current `HOSTED` publication and its world/runtime fence. It does not maintain another hosting flag, change cheats/default game mode, announce a LAN port, or alter ordinary Vanilla LAN publication. Integrated pause/remote behavior still requires real-world acceptance.

In an integrated world, the runtime screen exposes explicit host/unhost intents without requiring cheats. One server-thread task is retained per world, with a three-second deadline. An expired result keeps its capacity until the queued runnable actually drains; the late runnable cannot mutate a replacement world/runtime or begin a host intent after its view expires. Closing a view after the effect has already begun does not undo that world-owned intent. Host receives the originally selected runtime rather than reacquiring a newer one. `Stop room` revokes local publication before requesting native stop, including a runtime still `starting`. A stop acknowledgement waits for a matching native status transition before returning to account controls; late presentation rejects another active runtime. None of these actions silently accept terms or automatically publish a world.

Operator/console commands (permission level 4):

- `/p2wlan connect ROOM`: connect one room ID/eight-digit code from the already saved P2WLAN account's own membership. This starts the native runtime only; no Minecraft world is published automatically.
- `/p2wlan runtime`: report native `idle`/`starting`/`ready`, not game-login readiness.
- `/p2wlan host`: explicitly publish the current Minecraft server after the native runtime is ready. A failed attempt returns a stable code, with no retry.
- `/p2wlan world`: report local publication status. `HOSTED` means the local guarded target and native registration were created, **not** that a remote player logged in.
- `/p2wlan disconnect`: revoke the local world before requesting native runtime stop, retaining account/membership. A failed stop reports that local revocation already occurred; it is not silently undone.
- `/p2wlan unhost`: immediately revoke the local world capability and perform bounded cleanup off the game thread. This does not change room membership or claim to roll back an already delivered native mutation.

The subprocess/world tests check capacity, native-owner/runtime fencing, cancellation during setup, cleanup of late host replies and malformed registration failure. Protected-target socket tests check that both local capability handshakes disappear before game bytes. Fabric transport tests use actual Netty pipelines with fragmented/coalesced input, virtual-time expiry, capacity, revocation and delayed Vanilla initialization, plus a real 128,000-byte TCP exchange between the Java guard and a protected Netty echo target. The echo target is not Minecraft. They are **not** Minecraft world/login acceptance. A safe dedicated-server account setup workflow remains unavailable. Remote Minecraft authentication/login, sustained gameplay, integrated hosting/pause, cancellation during actual configuration and world-replacement acceptance remain incomplete. No EULA/account term is accepted by any build/test entry point.


## Isolated real-client hook verification

The test-only `clientFixture` source set, Mod and Loom run are enabled only by the dedicated fixture tool. They are excluded from ordinary builds/runs; the production archive checker rejects fixture classes. From the repository root:

```sh
python3 minecraft-mod/tools/client_hooks.py --offline
```

A release-native fixture run requires a clean checkout. During development, explicitly use:

```sh
python3 minecraft-mod/tools/client_hooks.py --offline --development
```

The tool serializes native packaging before Loom verification/startup, creates a fresh private client run directory and reads only a bounded, exact-schema, run-bound result—not log strings or Gradle exit alone. One process group and diagnostic reader have a 240-second execution deadline; retained diagnostics are limited to 1 MiB with explicit truncation. Native helper cleanup is checked before completion. macOS/Linux POSIX hosts are admitted by the tool; Linux display/device execution is not yet verified.

The fixture starts the actual pinned Minecraft/Fabric client and executes 55 checks against required Mixin transformations, the exact connection/address scope, a capability prefix followed by real Vanilla handshake/LoginHello encoding, an ordinary unwrapped Vanilla bootstrap, cancellation and retired-owner cleanup that cannot close a replacement channel. Its bounded loopback receiver sends no ACK and performs no Minecraft authentication. It creates no world/server, joins no remote game, accepts no EULA/account terms and does not establish gameplay or integrated-pause acceptance. Two-end Minecraft/world tests, forced Relay/public NAT, Windows, other loaders/versions and independent security review remain outside this fixture.
