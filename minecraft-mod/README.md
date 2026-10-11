# P2WLAN Minecraft technical reference

[项目介绍 / Project overview](../README.md) · [安装与联机指南 / User guide](docs/quickstart.md) · [文档导航 / Documentation](../docs/README.md)

This page documents implementation contracts and component-specific build/test entry points. Start with the user guide for Mod installation, accounts, rooms and game controls. Unless stated otherwise, commands run from the complete repository root.

This directory is an independent, experimental integration project. It does not alter the upstream Cargo workspace or existing P2WLAN client/server sources. The Fabric baseline is pinned to Minecraft 1.21.1; an opt-in NeoForge 1.21.1 adapter shares the same common and transport modules. An opt-in local fixture checks actual offline-mode client/dedicated-server play through the Mod. Platform and gameplay verification scope is summarized in [Project status](../README.md#项目状态); the sections below describe each component and its test entry points.

## Native TCP endpoint

The native bridge provides a user-space IPv4/TCP endpoint over bounded raw-IP queues. It creates no TUN device and installs no system routes. A single owner drives time, packet input/output, and socket state. Connection IDs are monotonic identities rather than reusable socket slots.

From the repository root:

```sh
python3 minecraft-mod/tools/native.py test
python3 minecraft-mod/tools/native.py fmt --all -- --check
python3 minecraft-mod/tools/native.py clippy --workspace --all-targets -- -D warnings
```

Each endpoint has at most 32 sockets, 32 KiB receive/send buffers per socket, and 128 queued IP packets per direction. The raw-IP receive cap is 1280 bytes. A standalone TCP stack defaults to the same transmit ceiling and supports an explicit 576–1280-byte packetization ceiling without changing its receive cap. TCP inactivity timeout is 15 seconds and keep-alive is 5 seconds. Packets and connections over budget return typed failures or apply backpressure; existing work is not evicted to admit new work.

The bridge tests exercise real user-space TCP, half-close, resource limits, timeout, and recovery from loss, duplication, and reordering.

## Common Java protocol

The common Java module contains no Minecraft or loader classes. It implements 64 KiB length-prefixed, strict UTF-8 JSON IPC, with duplicate-key, nesting, element-count, and numeric bounds. Native-owned status snapshots are copied and admitted only for the current process owner and increasing revision. Late cleanup from an old owner cannot close a new session.

Java 21 is required to run the project build; the common module is compiled to Java 17 bytecode. The Gradle 8.14 wrapper verifies its fixed distribution checksum. From the repository root:

```sh
python3 minecraft-mod/tools/java.py test
```

The protocol test runs an explicit executable test harness. It verifies malformed/partial frames, Unicode, capacity limits, snapshot immutability, and stale-owner/revision rejection.

## Shared Netty transport

`java/netty-transport` contains the loopback capability admission, scoped listener
binding and join-preface transport. It compiles to Java 17, imports no Minecraft
or loader class, and receives the game network object as an opaque identity plus
a version adapter's bind callback. A required adapter Mixin supplies the marker
interface; missing hooks fail before opening an unguarded listener. The protected
listener uses one event loop, at most 32 children and two-second admission/bind
deadlines. Revocation checks remain at the actual bind and pipeline boundaries.
Fabric's client/server Mixins delegate to this same implementation.

`python3 minecraft-mod/tools/java.py test` executes its explicit Netty/socket
harnesses as well as the common protocol tests. The Fabric archive check requires
one complete portable transport jar. Actual Minecraft Mixin transformation and
gameplay are checked by the corresponding game fixtures.

## Upstream runtime packet port

The native build generates an adapted runtime under the ignored `native/target/upstream-runtime/` directory. It reuses this checkout's upstream daemon and networking crates without modifying their source or manifests. Exact source anchors are checked before generation; incompatible upstream changes fail the build rather than silently selecting another code path. Generated metadata records upstream source, Rust adapter, and build-transform hashes. Required upstream compile-time Windows resources are copied byte-for-byte, checked against their exact include-site contract and included in the native-input digest; missing, linked, oversized or changed references fail before generation.

The packet port substitutes bounded user-space IP channels for the daemon's system interface. Only this backend skips OS route installation; the normal daemon entry point retains its original system-interface behavior. The port requires the bridge's 1280-byte MTU and applies a 250 ms write deadline. The formatting wrapper selects only Mod-owned workspace packages and explicitly formats the tracked Rust overlays without following their module paths, including when called with `fmt --all`.

```sh
python3 -m unittest discover -s minecraft-mod/tests -p 'test_*.py'
python3 minecraft-mod/tools/native.py test --workspace
```

The runtime-port integration test carries a real 128 KB bidirectional TCP stream through upstream dataplanes, the upstream Noise handshake/encrypted transport, and loopback UDP.

## Authorized application packet port

The authorized backend admits IPv4 packets using immutable capabilities over the original daemon's peer lifecycle, network generation, room membership, and transport-session owners. Grants belong to one exact runtime authority and one exact packet port, even if peer IDs and addresses coincide. Callers can attach non-cloneable scope owners to application lifetimes; closing those owners revokes queued work. Dropping the packet interface automatically revokes its port scope. A grant holds at most four distinct scope identities; additional identities fail closed.

Each IP frame retains its original room lease. A healthy room renewal or an unrelated member joining/leaving can keep the TCP connection eligible, but cannot extend a queued frame's expired lease or replace its original roster snapshot. Per-member lifetime identities are maintained under the original room owner's snapshot lock, not in a separate authorization roster. Revocation, reauthorization after expiry, peer removal/rejoining, remote registration changes, runtime shutdown, and network handover invalidate old capabilities. Inbound admission retains the actual authenticated crypto instance before post-decrypt awaits and both inbound queues; an admitted packet cannot be freshly reauthorized. Receive-only crypto overlap is accepted only while the original transport still retains that instance. Crypto-owner contention retains the original already-decrypted envelope; it does not acquire a fresh grant, lease or session. Contention waits and capacity reservation share one 250 ms operation deadline, with no lock held during waiting. An unknown or retired authenticated instance is rejected, and the original envelope is revalidated immediately before each queue commit and local write.

The captured application fence accompanies the original room-send permit through routing and encryption to the original UDP/Relay final-writer checks. Regression tests exercise the actual upstream UDP writer, including revocation after encryption, bounded queues, retained scope identities, and a real 128 KB bidirectional TCP stream through authorized dataplanes, Noise sessions, and upstream UDP receive/decrypt workers.

## TCP receive-buffer admission

The gateway crate provides checked IPv4/TCP wire metadata and a bounded admission ledger for unread stream bytes. It validates IP/TCP checksums, rejects fragmented input, handles 32-bit TCP sequence wrap, and conservatively preserves all receive capabilities across reordering, overlap, retransmission, and partial reads. Each connection retains at most 128 admission spans within the bridge's 32 KiB receive window. Capacity exhaustion never evicts prior admissions; completed reads release their metadata.

Read chunks are limited to 8 KiB and retain their original authenticated crypto instance, room lease, and application scope identities. The protected chunk exposes no raw byte slice: each local TCP write revalidates those capabilities immediately before the socket syscall. A failed coverage check poisons the admission ledger because bytes already removed from the TCP stack must not be newly authorized. Binding receive admission to a connection adds its scopes without acquiring a fresh lease or crypto instance.

The component tests use actual upstream room/peer/Noise capabilities and real local TCP sockets, including revocation between readiness and the final write.

## Scoped local TCP connection bridge

`ConnectionBridge` binds one local socket to one non-reusable virtual socket identity. It retains at most one 8 KiB chunk in each direction, rechecks connection/world grants when attaching delayed connect results and transferring buffered bytes, and preserves TCP half-close. An already attached local destination cannot be replaced. Application-byte inactivity is bounded to 120 seconds, separate from TCP keep-alives and peer-loss detection.

The connection test exchanges 128 KB in both directions between real local sockets through the original encrypted UDP receive workers and user-space TCP stack. It also checks delayed attachment after scope closure and foreign-stack identity rejection. The gateway below seals raw stack output before removing or replacing a flow.

## Native service and join-proxy gateway

`TcpGateway` is the single owner of hosted-service registrations, loopback join proxies, the user-space TCP stack, and local connection bridges. It keeps at most eight registrations, 32 virtual TCP sockets (including service listeners), 32 accepted local handshakes/active bridges combined, and 32 bounded target-connect tasks. Accepted hosted streams each receive an independent bridge; the service re-arms its listener only when socket capacity exists. Service virtual ports and registration IDs are not reused within the process; exhaustion is explicit.

Local endpoints use a 40-byte prelude: an eight-byte protocol marker and a 32-byte CSPRNG capability. Join proxies read exactly this prelude before admitting game bytes. Hosted targets must be loopback world guards using the corresponding capability, not bare Minecraft ports. Prelude and target-connect deadlines are two seconds. Hosted targets must acknowledge readiness before the native connection bridge attaches; that acknowledgement is consumed locally and never becomes a game byte. Closing a registration cancels its handshakes/connect tasks and revokes its bridges; stale IDs cannot stop replacements. Peer revocation closes the associated join proxy. Runtime/network changes or packet-port owner loss close the gateway.

Each hosted registration also receives a fresh 256-bit remote service identity. `host` returns its canonical 64-character lowercase hexadecimal `service` value; `proxy` requires that exact value in addition to peer and port. It is selection metadata, not a replacement for room/peer authorization. Across the encrypted virtual TCP stream, the proxy sends a separate fixed 40-byte service-selection prelude. The host validates it before opening any local world-guard connection and sends a distinct 40-byte readiness response only after that immutable guard admits the target. Both peers consume those bytes locally; they never reach Minecraft. The total remote handshake deadline is five seconds, with at most 40 retained one-byte input fragments per existing flow. Original authenticated receive envelopes survive target-connect awaits and are revalidated at each actual local socket operation and delayed attachment. Transient validation contention retries only those exact envelopes within the existing deadlines. A completed local target may be retained in its existing flow until the five-second remote handshake deadline; no new connection, grant, readiness read or game delivery is replayed. Status exposes the bounded ready-target count. Registration/process replacement creates a new identity, so a fresh peer grant and a reused port cannot authorize an old world selection. Callers can obtain these selection identities through the one-shot discovery operation below; manually supplied identities remain subject to the same mandatory handshake.

Raw stack output is sealed with the current flow's original fenced grant before flow removal. A 128-frame backpressure queue retains those envelopes for at most 250 ms and never refreshes authorization on retry. Receive contention retains the original envelope in a single additional packet-port slot with the same 250 ms total deadline. Status exposes bounded resource counts, a saturating failure count, and typed failure reasons rather than interpreting logs.

The gateway tests carry three concurrent, capability-guarded local streams through the original encrypted UDP workers, including fragmented preludes, coalesced game bytes, bidirectional payloads, and half-close. They also verify registration/handshake capacity, shutdown, peer and port-owner revocation, stale proxy backlog rejection, and rejection of old world metadata before any target accept. The target guards in the native gateway tests are fixtures. The Java guard below is separately tested; the managed account/room entry is described below.

## One-shot service discovery

`discover` IPC accepts exactly `runtime` and `peer`. It queries one currently authorized room peer over the existing encrypted packet port, using virtual IPv4/UDP port 1023; it opens no system UDP listener and creates no TCP connection or world-guard admission. The reply contains that peer's request-time hosted-registration snapshot as at most eight `{port, service}` entries in ascending port order. Join-proxy registrations, local capabilities, addresses, credentials and account details are not included.

Each gateway permits one in-flight query and one response, with a fresh 128-bit random ticket and a two-second total Agent deadline including peer admission. The fixed packet format fits in one bounded datagram, validates lengths/counts/checksums, and rejects duplicate ports/identities. No background scan or discovery cache is created. Loss recovery sends this read-only query at most three times, 500 ms apart, retaining its exact original nonce, permission envelope, scope and two-second deadline. Each transmission is newly encrypted by the original daemon; encrypted frames and game delivery are never replayed, and no fresh peer grant is acquired for a retry. Queries use a revocable scope through final native send, retain the original authenticated reply envelope, and revalidate it before publication. Expiry, cancellation, parent/runtime shutdown and network replacement invalidate the captured query. Stable reasons distinguish busy, timeout and stale queries; status exposes the current query count and bounded, saturating typed gateway-failure counts.

The Agent keeps driving existing TCP flows during its discovery wait. A discovered entry contains the peer’s request-time service-selection metadata. Creating a join proxy reacquires the original daemon's peer authorization and still requires the exact remote service identity; stale entries cannot bypass the world-selection handshake. The complete Control/daemon traffic fixture queries the real encrypted catalog and verifies it changes on hosted-world replacement before attempting stale selection.

## Authorized room-member selection

`room_peers` accepts exactly `runtime` and returns that runtime plus `view` containing a decimal-string `network_generation` and a sorted `peers` array. This request-time projection comes from the original daemon's leased room authorization and includes authorized offline members. Discovery and proxy registration separately acquire and validate the original peer grants.

The projection contains at most 128 peer IDs, each at most 256 UTF-8 bytes and without control characters. An oversized roster fails completely with `ROOM_PEER_CAPACITY`; invalid IDs fail with `ROOM_PEER_INVALID`. A contended room owner, revoked/expired authorization, changed network, stopped runtime or revoked gateway scope fails explicitly. No background enumeration, partial result, independent roster cache or network wait is introduced. Local node IDs, device names, endpoints, account data, virtual addresses and credentials are not returned by this operation. Runtime-port tests exercise the real room owner, capacity, ordering, expiry, revocation and lifetime fences; subprocess tests also verify IPC argument/runtime rejection.

The common Java `RoomPeers` and `ServiceCatalog` parsers retain the native reply owner and exact runtime selection. They enforce complete field sets, unsigned 64-bit generation strings, bounded valid peer IDs, native UTF-8 ordering, at most eight sorted unique service ports/identities, and immutable lists. Callers use these immutable selection snapshots after rechecking process/runtime identity on the destination thread. Java parser tests use synthetic catalogs to check these field and identity contracts.

## Java join-registration lifetime

`JoinProxy` owns one game-join intent and its exact native `proxy` registration. It retains the selected process/runtime, peer, virtual port and remote service identity; native admission still acquires the original authorization. One JVM slot and one daemon worker are allowed, with no work queue or mutation replay. Native requests have a five-second deadline; parent/runtime revocation is checked every 100 ms. Cleanup uses the exact registration and fresh local attempt identity. Uncertain cleanup closes/reaps the captured helper; a known unsent or runtime-replaced cleanup cannot terminate the replacement runtime. Capacity is released only after known cleanup or actual child reaping.

`ready()` yields the validated, redacted capability for the local loopback proxy. The caller must revalidate `isCurrent()` before opening a connection or releasing game bytes, and revoke the owner on cancellation/disconnect. Actual subprocess fixtures test capacity, late registration after cancellation, parent/process exit, malformed replies, cleanup failure and runtime replacement.

## Fabric join-preface transport component

The Fabric adapter's `ProxyPrelude` component wraps one Netty initializer/socket. It validates the exact loopback proxy target, writes the 40-byte local capability before installing any Vanilla initializer/codecs or emitting their channel-active event, and then forwards every received byte as game data. Native join proxies send no local readiness acknowledgement; this component must not consume an imagined acknowledgement from the Minecraft stream. A two-second total deadline starts at handler installation and covers connect/prelude, including sockets that never become active. One 100 ms lifecycle timer closes an idle retired attempt. Both timers are cancelled on close/removal. No game-byte buffer, work queue, reconnect or capability replay is added.

The component fences inbound/outbound pipeline delivery against its attempt owner and closes on out-of-order traffic, wrong target, expiry or revocation. Actual Netty/socket tests check delayed initialization, ordering, timeout, cancellation, late write completion, target mismatch, revoked buffer release and a 128 KB round trip. The scoped client bootstrap hook below installs this component for explicit Mod joins.

## Scoped Minecraft client bootstrap hook

The client-only, required `ClientConnectionMixin` redirects the exact Fabric 1.21.1 `ClientConnection.connect(InetSocketAddress, boolean, ClientConnection)` initializer installation. `ScopedClientConnect` captures one exact connection identity and loopback capability in a synchronous thread-local scope before asynchronous channel initialization. The hook installs `ProxyPrelude` only for this explicit operation; ordinary Vanilla connections and queries keep their original initializer. Missing transformation, nested/repeated consumption, wrong connection/target and retired attempts fail with stable `JOIN_BOOTSTRAP_*` codes. No address-matching registry, background work queue or transport retry is added.

The client configuration is explicitly client-only in Fabric metadata and has its own required refmap. Archive verification checks both configurations, their exact game-method mappings and class versions. Bootstrap admission tests use fake marker identities to exercise scope consumption and owner checks. The game connection screen uses this scope to admit protected joins; ordinary Vanilla connections do not gain a P2WLAN scope. The real-client hook and local gameplay fixtures below exercise their respective game integration paths.

## Java world guard

`WorldGuard` owns one immutable world identity and capability-protected loopback endpoint. A single nonblocking selector thread forwards at most 32 accepted connections, with two 8 KiB buffers per connection. The guard reads exactly the 40-byte capability prelude, connects only to a validated loopback game target, and sends a one-byte readiness acknowledgement only after that target connects. Handshake/target readiness has a two-second total deadline; stream-byte inactivity is bounded to 120 seconds. Partial writes retain their buffers and TCP half-close is preserved.

The Minecraft adapter must revoke the guard on its game thread before closing or replacing the world. Revocation is nonblocking, wakes the selector, and is checked immediately before actual local socket I/O. `close` additionally waits up to two seconds for thread/resource termination. Immutable status snapshots identify their world owner; they are not a copy of native peer/path/session state. Local capabilities are redacted from string representations and are sent only through private IPC, never process arguments or game payloads.

```sh
python3 minecraft-mod/tools/java.py test
python3 minecraft-mod/tools/java.py interop
```

The tests exercise actual local sockets, fragmented/coalesced preludes, bidirectional payloads, half-close, capacity, deadlines, and target failure. They deliberately reuse a stopped guard's loopback port and verify that its old capability cannot connect to the replacement world. The interop command additionally exchanges a 128 KB TCP stream between the native wire peer and Java guard. These tests verify the guard/loopback contract.

## Native IPC codec

The native protocol crate applies the same object, depth, container, node, Unicode, number-token, and decimal-scale limits as the common Java codec. Numeric IDs and decimal values are retained without floating-point rounding. Idle reads are owned by the caller's session lifetime; a partially received frame has a total two-second deadline. Cancelling a read retains its header/body progress and original deadline. Cancelling or failing a write permanently poisons that writer, preventing a second request from being appended to a partial frame.

```sh
python3 minecraft-mod/tools/native.py test -p p2wlan-mc-protocol
python3 minecraft-mod/tools/java.py interop
```

The interop command builds native conformance peers and exchanges actual framed messages with Java, including Unicode, large payloads, exact integers/decimals, and EOF. These conformance peers are codec test fixtures.

## Common Java subprocess client

`AgentClient` owns one explicitly launched child process. It negotiates IPC version and native owner, correlates monotonic request IDs, keeps at most 32 pending requests and 32 queued frames, and sends a two-second heartbeat. Handshake/request timeouts default to five seconds and may be selected between 50 ms and 30 seconds. A timeout, damaged stream, wrong owner, unknown/duplicate reply, or unexpected exit terminates that exact child and fails outstanding work with stable reason codes. Requests with uncertain delivery are not replayed.

Before a successful `hello`, an initialization failure can emit `{version, event: "startup_error", error}` with an optional exact-schema `diagnostic` object (`stage`, `operation`, `target`, nullable signed 32-bit `os_code`, nullable bounded `path_depth`) with a closed reason code and no owner, revision or account data. Java accepts it only before the handshake and reports `INITIALIZATION_FAILED / <reason>`; it never enables form actions or publishes a ready process. The native write retains the codec's two-second deadline with no retry. When a child exits without such a frame, the process diagnostic includes the actual unsigned hexadecimal exit status when available (`UNEXPECTED_EXIT / 0x…`). The diagnostic object's labels are closed allowlists; unknown labels, extra fields, fractional/out-of-range numbers, raw paths and arbitrary text fail decoding. Windows profile I/O captures the operation and numeric Win32 error at the failing call; APIs returning an error directly are not read via a later `GetLastError`. The exit watcher allows the protocol reader a handshake-timeout-bounded drain before publishing termination, so an immediate exit cannot overwrite a buffered startup error. The screen wraps the complete stable diagnostic instead of truncating its suffix. The diagnostic identifies the failed stage, operation and system error for startup troubleshooting.

Command completions retain their native owner and request ID in immutable `AgentReply` values. Consumers must marshal callbacks onto their own game thread and check `isCurrent` there before producing a side effect; process identity does not replace a world/service identity check. Status snapshots use the same native-owner/increasing-revision fence. No lock crosses pipe I/O or future completion. The watchdog, reader, writer, and exit watcher are bounded by that child lifecycle; stderr is discarded rather than parsed as state or copied into game logs.

```sh
python3 minecraft-mod/tools/java.py test
python3 minecraft-mod/tools/java.py interop
```

The subprocess tests use actual native fault-fixture processes to verify concurrency, queue bounds, timeout, malformed/partial replies, stale snapshots/owners, immutable completions, stderr pressure, process replacement, and child/thread cleanup.

## Local Mod startup diagnostics

The Fabric adapter supplies a game-root `logs/p2wlan-mod/` sink independent of the credential directory. Its always-available **诊断日志** screen displays initialization operation/category/OS error evidence, copies a redacted in-memory summary and opens the local log directory. These actions do not require an Agent, `hello`, account or room. See the [user diagnostic guide](docs/diagnostics.md).

`ModDiagnostics` owns one per-launch bounded worker and no network state. It retains 64 records of at most 1024 characters in memory, with a 128-record disk queue and explicit eviction/drop counters. `startup.log` rotates to one `startup.log.1` backup, each capped at 256 KiB; an independent file lock refuses concurrent writers. Disk failure is a fixed reason code and preserves the memory summary. Disk I/O and arbitrary future callbacks do not run under its memory/lifecycle lock. Closing drains the admitted bounded queue; late callbacks cannot append or replace the terminal diagnostic. No retries, support upload, raw stderr capture or string-driven connection decisions are added.

Only fixed labels, validated build identities, numeric OS/exit codes and coarse environment information enter this sink. It excludes credentials, user/peer IDs, Control origins, real file paths, request/reply payloads, exception messages and business bytes. Java resource/extraction failures remain fixed codes with the prior stage event; native profile syscall failures retain numeric evidence. The sink provides failure evidence for troubleshooting. The Java harness checks schema rejection, disk rotation, missing resources, memory fallback, write-lock failure, symlink refusal, overflow, privacy, closure and owner retirement. The real local game fixture also renders a failed-helper diagnostic screen and exercises its copy/return controls.

## Native Agent entry and runtime supervisor

The independent `p2wlan-mc-agent` executable uses the framed IPC protocol and CSPRNG process/runtime owners. It accepts `ping`, `status`, `peer_status`, `start`, `stop`, `host`, `proxy`, `close_registration`, and `shutdown` operations. `start` consumes a private IPC configuration for an authenticated, managed `room-` network and returns a fenced runtime ID while startup proceeds; it is not an account-login operation. OS interface/routing integration remains disabled by the authorized userspace backend, and this entry disables daemon diagnostics listeners, DNS and port mappings.

The Agent owns one original daemon and one gateway. Startup is bounded to 15 seconds and daemon stop to two seconds. Host/proxy/stop commands must identify that exact runtime; registration closure also checks the caller's immutable scope/world tag. Binding metadata is bounded by and revalidated against the gateway's authoritative registrations. The stdin producer owns a revocable lifetime token that survives through final upstream packet writes: EOF or invalid input invalidates old application grants even while cleanup is pending. No extra fifth scope is required. A 32-request queue has a 250 ms admission deadline, the idle heartbeat limit is six seconds, and the native runtime uses two async worker threads with at most eight blocking workers.

Runtime status projects `peer_id` from the original daemon's authenticated, resolved startup configuration, not the pre-registration ID cached in a room profile. Application-admission failures retain their stable native reason codes; `gateway_failure` projects the gateway owner's latest failure without exposing endpoints or payloads. The gateway's TCP transmit ceiling comes from the upstream DPLPMTUD conservative UDP baseline minus its WireGuard framing overhead (currently 1168 bytes), capped by the raw-IP receive limit. It does not independently estimate paths or raise the transmit ceiling when path probing discovers a larger MTU. Kernel PMTU feedback is not consumed by this userspace endpoint: the adapter counts it as `LOCAL_MTU_FEEDBACK_UNSUPPORTED` without claiming delivery or terminating the daemon. Status exposes this count as `unsupported_local_feedback`; generic interface writes cannot acquire a fresh peer/world grant. Authenticated game receive still requires the original encrypted ingress envelope.

`phase: ready` reports that the packet interface and gateway exist. Status projects fixed, typed packet-port counters through `admission_drops`. `peer_status` requires the current runtime and one authorized peer ID; it reads the original committed path, peer/network identities, authoritative UDP publication and one DPLPMTUD snapshot without maintaining a roster or path cache. Its projections exclude endpoints, keys, credentials and payloads. Diagnostic identities use exact decimal strings. Actual forwarding revalidates the original capabilities.

Inner application IPv4 can travel through outer UDP IPv4 or IPv6. The build-only MTU adapter resolves the original committed remote endpoint's address family using the upstream endpoint-aware socket resolver. Exact local socket, validation owner, network/session/candidate epoch and publication checks remain with the original runtime; the adapter does not bypass business-budget confirmation or infer a new path.

Encrypted control payloads remain with the original transport's rekey, Direct validation, DPLPMTUD, Relay probe and path-commit handlers. Some of these use valid IPv4 envelopes; IP parsing alone is not a business/control classifier. Only non-control application IP packets acquire the extra Mod receive capability before post-decrypt awaits. The build transform checks the upstream control-family contract and fails closed if it changes.

Request IDs may arrive out of order, but may not repeat. Non-evicting ID ranges compact adjacent IDs and are bounded to 128 disjoint intervals; capacity failure terminates the IPC session rather than allowing replay. Replies and increasing-revision status events retain the native owner. Configuration, credentials and local capabilities are never written into replies as diagnostics or used to drive control flow through logs.

```sh
python3 minecraft-mod/tools/native.py build -p p2wlan-mc-agent --locked
python3 minecraft-mod/tools/native.py test -p p2wlan-mc-agent
```

Actual-child tests verify owner uniqueness, out-of-order request handling, typed command rejection, duplicate/foreign-owner termination, stdin EOF, heartbeat expiry and bounded shutdown.

## Managed account and room profiles

Launch the native Agent with `--profile-dir DIRECTORY` to enable `login`, `register`, `logout`, `rooms`, `create_room`, `join_room`, `leave_room` and `connect_room` IPC operations. Login/register take `server`, `identifier` and `password`; between Java and the native Agent, credentials travel only in private IPC, never command arguments. Control requests use the selected HTTP/HTTPS transport. Account/room operations require an idle runtime. Connecting accepts a room ID or eight-digit code from the authenticated account's own room list. Managed profiles reject the raw `start` operation.

The account screen separates helper IPC readiness, the current native-owned saved-session projection, and the last operation's receipt. The saved-session projection reports local account state; Control checks authorization when handling account and room requests. Successful login/registration opens the room list automatically. A saved session bypasses the login form; stopping a room or reopening the UI preserves the native-owned session and Control origin without storing the password. Ambiguous registration errors warn against repeat registration, preserve the stable reason code, and never infer that no account was created. URL/identifier and room name/code inputs survive screen resize/back in memory; passwords are cleared on submission, reinitialization and removal. Room operations require a saved session; connecting requires a current room-list entry. The native API/account console retains explicit membership removal; the simplified GUI exposes connect/disconnect, not membership removal. Navigation and up to three feedback lines occupy a separate footer; the shared layout reserves their space at a 320 x 240 logical viewport.

The REST client accepts root HTTP and HTTPS origins. Plain HTTP is permitted, including non-loopback origins; it does not encrypt Control credentials or signaling and is vulnerable to interception/tampering. The client shows an orange risk banner while an HTTP origin is entered and throughout P2WLAN screens for a saved HTTP account; the full warning is also available by hovering the banner. HTTPS is recommended and certificate verification remains enabled. Warnings do not require confirmation and do not block HTTP requests. It disables ambient proxies and redirects, uses a one-second connect deadline and 2.5-second total request deadline, and limits each response body to 1 MiB. The whole account command has a three-second deadline. Room lists contain at most 64 projected records; IPC exposes only room ID/code/name/CIDR/role, not membership arrays, passwords or credentials. Profile publication and runtime start revalidate the parent lifetime after HTTP awaits.

Unix profiles require a same-user directory and regular single-link files with no group/other access; new directories/files are created with `0700`/`0600` permissions. An exclusive lock prevents concurrent profile owners. Reads/publication stay relative to a pinned directory descriptor; directory or lock replacement fails closed. Account loading rejects unknown fields/versions, unsafe origins and oversized files. Logout atomically replaces account state with `null` without silently deleting retained room identities. Windows profiles use a Mod-owned Win32 filesystem capability, keeping `unsafe` FFI outside the safe Agent. They require a fully-qualified fixed-drive path on an ACL-capable local filesystem; UNC/device/drive-relative paths, reparse points, hard-linked files and other-user owners are refused. New profile directories/files receive a protected current-user-only DACL at creation, before credential bytes are written. Existing permissions are verified, not silently widened or repaired. Profile and ancestor directory handles allow read/write sharing for unrelated child creation and replacement but refuse delete sharing. Every credential operation rechecks directory identities and reparse attributes; the private leaf also retains its non-deletable profile-lock child. Stop the helper before moving the game directory. An exclusive profile lock, file IDs and ACL checks fence reads and handle-based atomic replacement. Files remain bounded to 64 KiB, ancestry to 64 directories and enumeration to 256 entries. Other unsupported platforms still return `PROFILE_UNSUPPORTED_PLATFORM`. The isolation boundary is other OS users; same-user code and administrators are trusted. Real Windows filesystem execution remains unverified.

The Windows-only storage harness runs actual ACL/lock/hardlink/junction operations on a Windows host. With a Windows Rust host and the local dependencies installed, run from the repository root:

```sh
python3 minecraft-mod/tools/native.py test -p p2wlan-mc-private-windows --locked
```

Room node identities are retained separately for each Control origin, account ID and room ID. The profile retains at most 32 room identities with no implicit eviction and limits directory enumeration to 256 entries. Device names are explicitly `Minecraft Mod`, not the host name. The account JWT and the daemon-owned room configuration are separate: room reconnect reuses its node identity while the original daemon owns registration credentials and boot incarnation. Configurations and keys must not be shared or committed.

The integration fixture builds against the unchanged Go Control and runs actual native child processes in a temporary private directory. It verifies account/room operations, distinct virtual addresses for two managed daemons, fenced stop/reconnect, retained room identities across process replacement, EOF cleanup while connected, and leave/logout. The default mode does not exercise application traffic. With `--traffic`, it additionally waits up to 15 seconds for both original committed business paths (including Direct budget readiness), then transfers three concurrent, capability-guarded 128 KiB TCP streams in both directions, checks half-close, rejects an old service after replacement, and rejects old world-selection metadata paired with a fresh proxy and the replacement port before any target admission. The target is a bounded loopback fixture, and the test uses local Control and Direct UDP without a Relay service.

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

A native resource package is built from this checkout, not downloaded at runtime. The default build executes on the current host. From the repository root:

```sh
python3 minecraft-mod/tools/native_bundle.py --offline
```

Release packaging requires a clean checkout. Manifest schema 2 records the execution method as `native-build-info` or `wine-build-info`; unknown methods and a Wine-labelled non-Windows target are refused. For an explicitly labelled development package, add `--development`. The producer selects Cargo's reported release artifact, runs its bounded `--build-info` command and checks its exact protocol, version, source commit, native-input digest and compiled platform before publication. The manifest binds those fields to the executable's SHA-256 and size; the copied resource bytes are checked again. A concurrent source change, ambiguous artifact or failed verification aborts packaging.

Compilation remaps repository/home source prefixes to generic build paths; a bounded scan rejects executable bytes that still contain those absolute build prefixes.

Generated executable resources remain under the ignored `java/native-bundle/build/` directory. The Java resource subproject refuses to build an empty resource jar. The recognised native-resource targets are macOS/Linux on aarch64/x86_64 and Windows x86_64. Windows resources use `p2wlan-mc-agent.exe` consistently in the producer, Java selector and archive verifiers; other Windows architectures are refused. Foreign packaging is limited to an explicitly selected Windows x86-64 target executed by a trusted Wine runner; there is no compile-only publication or silent runtime substitution. Java extraction uses a platform-specific executable-isolation backend. Real-device verification by platform is listed in [Project status](../README.md#项目状态).

For a Windows x86-64 cross-build on macOS/Linux, install the Rust GNU target, `x86_64-w64-mingw32-gcc`/`x86_64-w64-mingw32-ar`, NASM required by the native dependencies, and a locally executable trusted Wine installation with an adjacent `wineserver`. Then, from a clean checkout:

```sh
rustup target add x86_64-pc-windows-gnu
WINE=/absolute/path/to/wine
python3 minecraft-mod/tools/native_bundle.py --target windows-x86_64 --wine "$WINE" --offline
```

The producer checks bounded AMD64 PE32+ console headers, runs the actual compiled image's exact `--build-info` branch under Wine, and verifies that its bytes did not change across execution. It uses one create-new private build prefix, disables Wine Mono/Gecko downloads and ambient DLL overrides, and stops/waits for only that prefix's Wine server before removing the generated tree. Execution is limited to 30 seconds, cleanup calls to five seconds each. Uncertain cleanup retains the prefix and refuses another prefix rather than accumulating background services; investigate and stop that exact owned instance before clearing it. Toolchains and runners are trusted build inputs, not remote executable inputs.

`wine-build-info` records execution of the compiled Windows image's build-identity branch in Wine. Mod/account archive verification and `P2WLAN-PACK.json` retain that execution method. Native-host packaging and development opt-in policy remain unchanged.

The common Java `BundledAgent` entry point selects only the exact host resource, rejects missing/duplicate/non-local resources, validates the strict bounded manifest, and rejects development packages unless explicitly allowed by its caller. It extracts at most two private installations per JVM, with SHA-256/length checks and a 15-second extraction/verification checkpoint deadline. POSIX installations use 0700 directories and 0500 executables. Windows x86-64 installations require fully qualified local fixed-drive NTFS paths, protected current-process-user-only DACLs, pinned directory ancestors and stable volume/file IDs. UNC/device/ADS paths, reparse points and hard-linked executable files are refused. The executable is changed from writable to read/execute/delete access before launch; its writer handle is closed and the same file identity is reacquired and checked before handing its path to the image loader. There is no shared executable cache or permissive filesystem fallback. Startup belongs off the game thread. `close()` is nonblocking; `released()` completes only after the exact child has exited and owned-file cleanup has succeeded. Failed or replaced-file cleanup is typed and retains its capacity slot rather than permitting unbounded leftovers.

The executable filesystem boundary is isolation from other OS users plus identity-checked, non-recursive cleanup. Windows cleanup uses the exact owned file handles; unknown directory contents are not enumerated or recursively removed. A known failure before directory creation releases capacity only after handle cleanup; an uncertain cleanup or created directory retains its slot. Java's path-based launch trusts same-user code, the JVM/classloader and other Mods. Package checksums verify byte integrity. File/directory identity, owner, permission and hard-link checks detect observable replacement; execution uses the checked path rather than an atomic descriptor-based launch. POSIX filesystems lacking the required POSIX/Unix attributes and Windows paths failing the local NTFS/identity/ACL checks fail closed.

The Windows API boundary uses JNA core 5.14.0, bundled in both the Fabric archive and independent account distribution. Archive verification requires one JNA payload and binds its `Native.class` and Windows x86-64 `jnidispatch.dll` bytes to the pinned dependency. JNA JNI bootstrap and any launcher-provided loaded library are part of the trusted JVM/classloader and native-dependency configuration. Use private temporary directories and trusted native-library overrides.

The default Java tests exercise the portable Windows path/ACL/file-identity decisions on any supported build host. On Windows, the native-bundle test also runs the NTFS harness instead of POSIX attribute tests. The explicit NTFS-only entry point fails on non-Windows hosts rather than treating a skip as success:

```sh
python3 minecraft-mod/tools/java.py windows-native-test --offline
```

This harness checks real file creation, ACL/identity validation, capacity, replacement/hard-link refusal and exact cleanup on its execution host. The packaged-agent and lifecycle harnesses below additionally exercise the host image loader, Agent IPC and owned-profile cleanup.

The package integration check uses the actual extracted release-mode executable. Generate an explicitly labelled development resource and run:

```sh
python3 minecraft-mod/tools/native_bundle.py --development --offline
python3 minecraft-mod/tools/java.py bundle-test
```

This checks bundle validation, private extraction, replacement/symlink/hard-link rejection, exact subprocess readiness/IPC, immediate-close races and cleanup.

## Dedicated-server account console

The independent `account-cli` distribution initializes the **P2WLAN Control account**, not a Microsoft/Minecraft account. It imports no game/loader classes, uses Java 17 bytecode and the same common IPC/lifecycle and verified native resource as the Mod. The project build still uses Java 21. It does not connect a room runtime, publish a world, change Minecraft authentication settings or accept any terms.

From a clean checkout, build the host resource and distribution:

```sh
python3 minecraft-mod/tools/native_bundle.py --offline
python3 minecraft-mod/tools/java.py account-build --offline
```

The installed distribution is `java/account-cli/build/install/p2wlan-account/`; the matching zip is `java/account-cli/build/distributions/p2wlan-account-0.1.0.zip`, both beneath this directory. Verification requires matching installed/zip bytes, Java 17 game-independent console/common classes, fixed dependencies, native executable hashes and current source binding. Native targets are limited to resources actually packaged on the build host, not inferred cross-platform coverage. For an explicitly labelled development bundle use `native_bundle.py --development --offline` and `java.py account-build -PdevelopmentNative --offline`.

Stop the Minecraft server first. Run as the same OS user that runs it, and pass that server's exact private-profile directory explicitly. For this Fabric adapter it is `SERVER_ROOT/config/p2wlan-private`. Do not copy profiles between servers/devices or scan another application's credentials. From the installed or extracted distribution root:

```sh
./bin/p2wlan-account status --profile-dir /absolute/path/to/server/config/p2wlan-private
./bin/p2wlan-account login --profile-dir /absolute/path/to/server/config/p2wlan-private
./bin/p2wlan-account rooms --profile-dir /absolute/path/to/server/config/p2wlan-private
./bin/p2wlan-account join-room --profile-dir /absolute/path/to/server/config/p2wlan-private
```

`register` creates a P2WLAN account; `create-room` creates a room; `logout` clears the saved account without deleting retained room identities. Login/register prompt for the root Control origin, identifier and password; for HTTP they print `WARNING HTTP_UNENCRYPTED` before identifier/password entry, without printing the URL. Successful HTTP login/register and saved-account status receipts repeat the warning. A failed warning-output write stops before credentials/helper launch. Room operations prompt for name/code and a required password of 8–72 UTF-8 bytes, matching the current Control password-based flow. Empty, short, overlong or malformed UTF-16 console input is rejected before helper launch; the native Agent validates byte length again before any room mutation request. The Mod does not expose an invite-token join flow. Passwords use the real Java console with hidden input. No password, token, identifier or Control URL option/environment variable is supported. Password operations require a terminal; there is no echoing stdin fallback. `status`, `rooms` and explicit `logout` need no interactive credential input.

Input is collected and validated before any helper starts. Fields use the native API's 256-byte UTF-8 limit; malformed surrogates, NUL/newlines, empty required fields and invalid eight-digit codes fail before launch. Returned console character arrays are cleared, and JSON argument references are discarded after encoding. Java strings and IPC buffers cannot be securely zeroized; terminal editing and hostile same-user code remain outside this boundary.

One lifecycle owns one helper and one request, with no mutation retry. Startup/request waits are at most 41/6 seconds (the underlying owner/client deadlines are 40/5 seconds); cleanup waits at most 20 seconds, including a bounded shutdown hook. Replies are checked against the exact lifecycle/native owner before capture. Output is a bounded historical command receipt, emitted after child/extraction cleanup, not a live room/path cache: status exposes authenticated/signed-out, and room operations expose only eight-digit codes. URLs, user IDs, JWTs, arbitrary room names and child stderr are not projected. An uncertain mutation emits `DELIVERY_UNKNOWN`; inspect `status`/`rooms` before deciding whether to retry. Profile-lock/privacy failures can terminate the native helper before hello and therefore surface as a generic typed startup/exit failure, not a diagnosis inferred from logs.

After setup, start the server normally and use the operator commands below to connect the chosen room and explicitly host it. The console does not bypass the native profile lock or become another writer of credentials/network state.

The opt-in console fixture uses the installed launchers and packaged native executable against the unchanged Go Control, disposable accounts and real POSIX terminals. It checks password masking/no echo, EOF/no-console rejection before helper launch, room/account persistence, same-profile exclusion and helper cleanup during an in-flight mutation. With the Go executable built as described above:

```sh
python3 minecraft-mod/tests/account_console_fixture.py --distribution minecraft-mod/java/account-cli/build/install/p2wlan-account --zip minecraft-mod/java/account-cli/build/distributions/p2wlan-account-0.1.0.zip --control minecraft-mod/native/target/server/p2wlan-control
```

A development distribution requires explicit `--development` on this fixture and admits development resources only for its child invocations. Diagnostic retention is capped at 64 KiB per child. Console/sequence execution deadlines are 72/300 seconds, followed by at most 23 seconds of graceful child-group cleanup and a three-second kill/reap wait; exact child groups and extracted files must be gone before success. No transcript, credentials or acceptance evidence is committed. The real terminal fixture is verified on macOS/aarch64; Linux terminal/device acceptance and Windows private-profile ACL/package support remain incomplete. The fixture covers the standalone account-console flow.

## Fabric adapter bootstrap

The explicit Fabric adapter targets **Minecraft 1.21.1**, with Fabric Loader 0.16.14, Fabric API 0.116.7+1.21.1, the official Minecraft 1.21.1 Mojang mappings and Loom 1.10.5 as its pinned build inputs. Its loader metadata accepts Fabric/Minecraft 1.21.1 only. Loader events stay in `java/adapters/fabric-1.21.1/`; Minecraft 1.21.1 client screens, game joins and display observers are shared under `java/game-1.21.1/`. Common Java and native networking remain game-independent.

From a clean repository checkout, generate the host resource and build the adapter:

```sh
python3 minecraft-mod/tools/native_bundle.py --offline
python3 minecraft-mod/tools/java.py fabric-build
```

The explicit adapter build remaps its jar, embeds the common and native-resource jars, and checks the exact loader entrypoints, Java 21 adapter/Java 17 common bytecode, absence of game/loader references in common bytecode, source-bound native manifests and executable hashes. Empty, stale, mismatched or duplicate native resources fail the artifact check. The checker emits public metadata binding the final jar SHA-256, source commit, embedded version and native verification targets; archive verification checks the packaged bytes and build identities. Common Java tests do not configure Loom or fetch Minecraft. A single-host resource package does not provide executable coverage for other operating systems or architectures.

For explicitly labelled development resources and a development build:

```sh
python3 minecraft-mod/tools/native_bundle.py --development --offline
python3 minecraft-mod/tools/java.py fabric-build -PdevelopmentNative
python3 minecraft-mod/tools/java.py fabric-server -PdevelopmentNative
python3 minecraft-mod/tools/java.py fabric-client -PdevelopmentNative
```

`fabric-server` uses Loom's development server; it does not accept or rewrite Minecraft's EULA. Game/account terms must be accepted by the user before a playable server can run. The development property permits development manifests in the build check and forwards the same explicit opt-in to Loom's development JVM. A normally installed adapter rejects development-native resources by default.

The common loader entrypoint contains no client initialization. Dedicated-server lifecycle events start the native helper off the game thread and close it when the server stops. On a client, the helper lifetime follows the application rather than individual integrated worlds. The shared `AgentLifecycle` is single-use: one startup worker, one 40-second startup deadline, no retry/work queue, immediate revocation on close, and identity checks before an asynchronous client reference can be used. Late startup completion closes its own child rather than publishing a revoked owner.

The dedicated-server `/p2wlan status` command requires permission level 4. The client command is `/p2wlan-client status`. They show helper process/IPC readiness and stable startup/cleanup reason codes. The client exposes a 20×20 pixel LAN-logo button beside existing controls in the title, multiplayer and pause menus; bare `/p2wlan-client` opens the same entry. An idle helper shows account/membership controls; an active `starting`/`ready` runtime shows room controls. The screens support explicit login/register, saved-account room membership, bounded paging, refresh, create/join membership and logout. Room create/join forms require a password of 8–72 UTF-8 bytes and reject invalid input locally before submitting a native request; passwords are not trimmed or normalized. Password fields mask rendering and narration, block copy/cut and clear widget retention after submission/removal/resize; Java strings are not securely zeroisable. No Minecraft/Microsoft credential is required by these screens.

A shared completion gate permits at most one native request, local intent or queued game-thread presentation. Process/view/runtime identities are rechecked at admission, the queued IPC write and the actual presentation boundary. Closing a screen discards late presentation without replaying or claiming to undo a submitted mutation. Buttons derive eligibility from native snapshots, not a second account/runtime state machine. Creating/joining membership does **not** connect the overlay runtime; clicking a room-list row is the separate connection intent. Reopening P2WLAN after stopping a room uses the saved native session and returns to the room list without requesting a password or Control URL again. Explicit sign-out clears the session; stopping a room does not.

The runtime screen maintains the Mod's virtual room network only. It shows four compact read-only cards (RTT, recently observed online devices, local virtual IP and path), with explicit room disconnect and return-to-game actions. Connecting a room does not join a world. Returning from a game or closing the P2WLAN UI leaves the room connected until explicitly disconnected. It does not create an OS TUN interface or modify system routes.

Open Minecraft's ordinary **Multiplayer** screen to select a game server. Current P2WLAN discoveries appear as transient LAN rows alongside unchanged saved servers and ordinary LAN discovery. The rows use the authenticated Control device label, or a generic Minecraft server label, plus room name and virtual address; opaque node/world capability IDs are not displayed. These labels are not Minecraft MOTDs. Rows are not written to `servers.dat` or broadcast to the OS LAN. Vanilla selection, double-click, Enter and the Join Server button start the fenced Mod join/cancel flow. Native reauthorizes the selected runtime, peer and exact remote-world identity when creating the local proxy; actual forwarding revalidates the original room and world capabilities. Joining remains refused while a local/remote world or another unreleased attempt is active.

The application-scoped display observer reads the original native owners through at most one read-only IPC request (five-second deadline) at a time and waits one second between observation cycles. It projects at most 16 peers from a complete native roster of at most 128 members, and 32 services total (at most eight per peer); capacity limits are reported as `VIEW_CAPACITY_16` / `SERVICE_CAPACITY_32`. Telemetry observations expire after five seconds and service catalogs after 30 seconds. Owner/runtime/generation/session changes, member removal and offline observations discard incompatible rows; stale room observations hide them. Discovery occurs only while the Multiplayer browser is open; closing it cancels its discovery lifetime and clears the catalog, without stopping the room. Replacement or shutdown cancels the sole pending read and clears all observations.

RTT and jitter come from the existing native committed path's health measurements, never HTTP/IPC timing. No extra probe or path selection is introduced. Missing, contended or old measurements display unknown/pending instead of fabricated zero milliseconds. The panel uses the current game peer when playing, otherwise the lowest measured RTT among recently observed online peers; the tooltip identifies the measured device. Online count includes the recently observed subset plus the local device, with at most 16 remote peers observed. Direct uses green, Relay blue (normal and usable), connecting yellow. Hover cards/server metrics for virtual IP, RTT, jitter, current confirmed Direct-path MTU (matching generation/session/candidate epoch) and cumulative send/receive byte counters. Path MTU is the original runtime's diagnostic measurement; the Mod TCP endpoint uses the transmit ceiling described above. The optional in-game overlay reports only the current P2WLAN game peer and hides with the HUD/debug screen. It is disabled by default. Required exact-version Multiplayer Mixins and their bytecode/refmaps are verified in the production archive; the client-only hooks never initialize on a dedicated server.

### Display settings and latency statistics

Every P2WLAN account/room screen has a **显示设置** (Display settings) control. This local-only page remains usable when the helper is unavailable. It controls the in-game HUD toggle, a four-corner placement selector and three detail tiers:

| Tier | Visible HUD measurements |
| --- | --- |
| 基础 / Basic | Latest RTT and Direct/Relay path |
| 详细 / Detailed | Basic plus Min/Avg/Max, native jitter and sample count |
| 高级 / Advanced | Detailed plus P50/P90/P95/P99, confirmed path MTU and cumulative byte counters |

**保存并返回** saves preferences to the game instance's `config/p2wlan-display.json`; **取消** discards edits. The bounded schema contains only rendering preferences, never an account, password, Control URL or network identity. Invalid/unreadable files fall back to defaults with a visible reason. Saves use a same-directory temporary file and atomic replacement where supported; failures are explicit and do not apply the new preferences. Logging out, stopping a room or closing a world does not change these preferences.

**延迟统计** (Latency statistics), reached from Display settings, shows every requested statistic even when the game HUD is disabled. It uses the same measured peer as the room panel, identifying that device and path. The window contains successful timed RTT samples from the last **60 seconds**, capped at the **256 newest samples per native path-health owner**. A capacity-limited window is marked explicitly. Direct and Relay histories are separate; lifecycle invalidation clears the corresponding history, and the first authoritative Direct data-plane RTT replaces candidate-probe history. Repeated Java polls/frames, equal-valued RTTs and untimed ingress do not create, merge or duplicate measurements. No extra network probe is introduced.

The Native owner records `Instant`-timed durations with microsecond precision; the UI displays milliseconds. The current RTT card displays the latest sample. Min/Max are extremes; Avg is the arithmetic mean (microsecond result truncated below one microsecond). For percentile `p`, sort the retained samples and take the **ceil(p × n / 100)**-th value, one-based (nearest-rank / inverse empirical CDF, no interpolation). A window of 100 samples therefore takes its 95th sorted value for P95. With fewer than 100 samples the P99 display includes a low-sample warning. The sample population contains successful timed P2WLAN path RTT measurements.

Native jitter is a smoothed absolute deviation from the previous RTT EWMA: `E = ceil((7 × previous_E + RTT_ms) / 8)`, `J = ceil((3 × previous_J + abs(RTT_ms - previous_E)) / 4)`. The first sample initializes `E` to that RTT and `J` to zero. Native values retain their existing whole-millisecond rounding. The latest RTT/jitter is unknown once the last **timed** sample is older than 15 seconds, even if untimed traffic continues. Aggregates are fenced read-only snapshots, refreshed by the existing observer with a five-second display TTL; game forwarding revalidates its original capabilities.

Menu insertion uses the existing controls' actual geometry, preserving tooltip, keyboard focus and narration. A fully occupied Vanilla row may reserve a 24-pixel slot in its navigation button. If another mod replaces/occupies all supported anchors, the integration does not guess a detached corner or overlap their controls; `/p2wlan-client` remains an entry point.


The join lifetime owns one native proxy, one real Minecraft connection and two one-shot completion cells, with no retry/executor queue. The required client Mixin scopes the original synchronous bootstrap to that exact connection object and loopback address; ordinary Vanilla bootstraps remain unchanged. A 40-byte capability precedes all game codecs, with a two-second total connect/prefix deadline. Native join proxies send no local ACK, so every returned byte is Minecraft data. Login begins on the game thread only after a fenced handoff, retaining Vanilla login/authentication, configuration, compression, encryption and resource-pack consent. Minecraft session credentials are not replaced. Only login/configuration connections not already owned by Vanilla's play tick are ticked by the adapter. Once handed to Vanilla play, the old owner never invokes its pre-play disconnect handler again; a normal Vanilla connect/transfer or unrelated play connection retires the old presentation intent. Cancel, channel close, runtime replacement and shutdown revoke the exact local intent; a replacement cannot start until native release and channel closure. The local gameplay fixture below checks the rendered UI and Vanilla login, configuration, play and disconnect flow on macOS/aarch64.


## Guarded local world publication

The shared `WorldSessions` owner retains one exact dedicated/integrated server lifetime and revokes its world guard **before** server stop/replacement. Fabric forwards start/tick/stop events and supplies its version-specific bind callback; queued intents and native publication use the same world identity. A publication has one worker and one JVM capacity slot, with no queued setup or automatic retry. A revoked, delayed native `host` reply is retained only for exact registration cleanup, never published for a replacement world. Local cleanup failure holds the capacity slot; uncertain native cleanup terminates that exact helper rather than replaying a mutation. Queued world IPC requests recheck their lifetime immediately before the pipe write.

The adapter uses Minecraft's existing codecs, rate limiter, handshake handler and authoritative game-connection list, with an additional ephemeral **127.0.0.1** listener. It does not call the integrated-server open-to-LAN operation or advertise a LAN port. Existing dedicated-server listeners/configuration are unchanged: server operators remain responsible for restricting any separately configured public game port. Minecraft sees a loopback source, not the remote player's physical/virtual IP; IP-based bans or other Mods that rely on source IP need separate consideration. No forwarded-IP identity spoofing is performed.

The Minecraft-facing listener itself consumes a fresh 40-byte local capability **before** initializing Vanilla codecs/connections. The outer `WorldGuard` authenticates that target and consumes its one-byte readiness acknowledgement before acknowledging native readiness; neither local prelude/acknowledgement reaches Minecraft. Unauthenticated local users cannot bypass the outer guard by connecting directly to this listener. A single Mod-owned NIO event loop drives accept/child registration, so unauthenticated local connects cannot build an unbounded registration queue on another game event loop. At most 32 children, 40 prelude bytes per child and one two-second timer per child are retained; existing Minecraft packet/player internals remain Minecraft-owned. Target revocation fences inbound/outbound game bytes and closes owned channels. Target cleanup waits at most two seconds each for children/listener closure and three seconds for the sole event loop to terminate; failed cleanup does not release the publication slot. Same-user code and other Mods remain inside the trusted local environment.

The required, exact-version server Mixin is checked during bootstrap; absence fails before an unguarded bind. Its explicitly scoped bind reuses Vanilla's bind/stop ordering under the existing channel-list monitor, with a two-second wait limit. The Mod owns and closes its extra listener rather than accumulating closed entries in Vanilla's listener list. Ordinary Vanilla binds retain their original behavior. Concurrent full-game bind/stop competition and integrated-world behavior remain unverified; the isolated local gameplay fixture below checks the dedicated-world path. A required client-only IntegratedServer Mixin derives remote/pause semantics from the exact current `HOSTED` publication and its world/runtime fence. It does not maintain another hosting flag, change cheats/default game mode, announce a LAN port, or alter ordinary Vanilla LAN publication. Integrated pause/remote behavior still requires real-world acceptance.

In an integrated world, the runtime screen exposes explicit host/unhost intents without requiring cheats. One server-thread task is retained per world, with a three-second deadline. An expired result keeps its capacity until the queued runnable actually drains; the late runnable cannot mutate a replacement world/runtime or begin a host intent after its view expires. Closing a view after the effect has already begun does not undo that world-owned intent. Host receives the originally selected runtime rather than reacquiring a newer one. `Stop room` revokes local publication before requesting native stop, including a runtime still `starting`. A stop acknowledgement waits for a matching native status transition before returning to account controls; late presentation rejects another active runtime. None of these actions silently accept terms or automatically publish a world.

Operator/console commands (permission level 4):

- `/p2wlan connect ROOM`: connect one room ID/eight-digit code from the already saved P2WLAN account's own membership. This starts the native runtime only; no Minecraft world is published automatically.
- `/p2wlan runtime`: report native `idle`/`starting`/`ready`.
- `/p2wlan host`: explicitly publish the current Minecraft server after the native runtime is ready. A failed attempt returns a stable code, with no retry.
- `/p2wlan world`: report local publication status. `HOSTED` reports creation of the local guarded target and native registration.
- `/p2wlan disconnect`: revoke the local world before requesting native runtime stop, retaining account/membership. A failed stop reports that local revocation already occurred; it is not silently undone.
- `/p2wlan unhost`: immediately revoke the local world capability and perform bounded cleanup off the game thread. This does not change room membership or claim to roll back an already delivered native mutation.

The subprocess/world tests check capacity, native-owner/runtime fencing, cancellation during setup, cleanup of late host replies and malformed registration failure. Protected-target socket tests check that both local capability handshakes disappear before game bytes. Fabric transport tests use actual Netty pipelines with fragmented/coalesced input, virtual-time expiry, capacity, revocation and delayed Vanilla initialization, plus a real 128,000-byte TCP exchange between the Java guard and a protected Netty echo target. The socket tests use a protected Netty echo target. Dedicated-server accounts can be initialized with the independent console above while the server is stopped. Cross-machine Minecraft authentication/login, long-running gameplay, integrated hosting/pause, cancellation during actual configuration and world-replacement acceptance remain incomplete. Ordinary builds and the hook/server-boot fixtures do not accept EULA/account terms; the separate local gameplay fixture below requires explicit consent.


## NeoForge adapter

The opt-in `java/adapters/neoforge-1.21.1/` adapter targets **Minecraft 1.21.1**,
**NeoForge 21.1.256**, **ModDevGradle 2.0.148** and **Java 21**, using the game's
Mojang-named API. It supplies loader events, operator/client commands and the
required server-connection Mixin. `WorldSessions`, Native IPC, account/runtime
ownership and the protected Netty transport are shared with Fabric. The same `java/game-1.21.1/` client implementation supplies account/room screens,
Multiplayer joins, display settings, diagnostics and integrated-world controls to
both loaders. Dedicated client/server gameplay is verified locally below;
integrated-world gameplay acceptance remains pending.

From a clean checkout with the fixed dependencies cached:

```sh
python3 minecraft-mod/tools/native_bundle.py --offline
python3 minecraft-mod/tools/java.py neoforge-build --offline
python3 minecraft-mod/tools/java.py neoforge-server --offline
python3 minecraft-mod/tools/java.py neoforge-client --offline
```

The JAR is
`minecraft-mod/java/adapters/neoforge-1.21.1/build/libs/p2wlan-neoforge-1.21.1-0.1.0.jar`.
The server run uses the adapter's normal development directory and does not
accept the EULA. Dedicated operators initialize accounts with the common account
console and explicitly connect/host using the commands above. Development native
resources require `native_bundle.py --development` and `-PdevelopmentNative` for
the Java build/run, as with Fabric.

The installed JAR contains exactly three JarJar components: common Java, portable
Netty transport and native resources. The resource module has the stable Java
module name `io.p2wlan.mc.nativebundle`. JNA 5.14.0 comes from the pinned game's
bootstrap layer; no second named JNA module is embedded. Development runs
explicitly add the portable libraries to ModDev's additional runtime classpath.
The adapter resolves the exact local native-resource module through its module
reader, or uses the development classloader. Typed Union/JarJar filesystem
backings are checked within eight levels before reading; ambiguous/remote
resources fail, and common manifest, executable, extraction and lifetime checks
remain unchanged. The portable default retains its local file/JAR policy.

Two create-new, disposable **offline-mode** dedicated-world fixture modes require
explicit EULA consent and use isolated build/cache/game directories:

```sh
python3 minecraft-mod/tests/neoforge_server_fixture.py --accept-eula --development --offline
python3 minecraft-mod/tests/neoforge_server_fixture.py --accept-eula --development --offline --installed
```

Installed mode copies only the checked production archive into the fresh `mods/`
directory, excludes the production source mod and disables the development
library fallback. Each mode requires an exact run/loader/installation-bound
32-check result: actual world startup, source-versus-JarJar module identities,
native helper IPC, protected Vanilla status, wrong-capability refusal, listener
revocation, ordinary Vanilla status and world/helper cleanup. This scope is
local dedicated-world/status verification, not client login or game traffic.
Both modes are verified on macOS/aarch64 with the pinned versions above.
The standalone `:neoforge:resourcePolicyTest` checks real Union/JarJar filesystems,
resource-only named-module access, ambiguity, remote refusal and the depth limit;
it runs as part of `neoforge-build` without starting Minecraft. Fixture classes
stay out of production archives.

## Shared Minecraft 1.21.1 client layer

`java/game-1.21.1/src/client/java` is compiled directly into both loader archives;
there is one physical implementation of the screens, fenced game-join lifetime,
read-only service observer, display/HUD settings and exact-version client Mixins.
It imports Minecraft APIs but no Fabric, NeoForge or Forge class. Loader adapters
bind the existing Native `AgentLifecycle` and `WorldSessions` owners once and
supply client events, widgets and render callbacks. Account/path/room authority
remains Native-owned; the binding delegates rather than retaining parallel state.

The real-client hook assertions and local-game widget/network assertions also
have one test-only implementation under this version directory. Loader test mods
supply event observations; production artifacts reject all fixture classes.
NeoForge executes the 61-assertion hook harness through:

```sh
python3 minecraft-mod/tests/neoforge_client_fixture.py --development --offline
```

The result verifies required client/server/IntegratedServer transformations,
capability-prefixed Vanilla login bytes, ordinary unwrapped bootstrap, cancellation,
retirement and exact helper cleanup. It runs without starting a test game server.
The two-JVM local gameplay fixture accepts an explicit loader:

```sh
python3 minecraft-mod/tools/local_game.py --loader neoforge --accept-eula --offline-mode --offline --development
python3 minecraft-mod/tools/local_game.py --loader fabric --accept-eula --offline-mode --offline --development
```

Both paths retain the shared 68 server / 65 client checks and real Vanilla
login, 20 ordered C2S/S2C exchanges, at least 18 seconds of play, room stop and
world/helper cleanup. The default loader remains Fabric. Verification currently
uses local macOS/aarch64 and Minecraft 1.21.1 with the pinned loader versions;
Windows and remote-machine execution remain separate verification targets.

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

The fixture starts the actual pinned Minecraft/Fabric client and executes 61 checks against required Mixin transformations, the exact connection/address scope, a capability prefix followed by real Vanilla handshake/LoginHello encoding, an ordinary unwrapped Vanilla bootstrap, the actual Vanilla-screen retirement policy, cancellation and retired-owner cleanup that cannot close a replacement channel. Its bounded loopback receiver captures bootstrap bytes without an ACK. The fixture runs a single client with that receiver; game terms and the two-JVM gameplay flow are handled by the separate local gameplay fixture.

## Isolated dedicated-world verification

The test-only `serverFixture` Mod/source set/run is excluded from ordinary builds; the archive checker rejects fixture classes in production. Its wrapper requires an explicit **disposable** server run directory, an already manually accepted `eula.txt`, a loopback `server-ip`, online authentication, status queries enabled and RCON/query disabled. It loads/saves that test world and stops the server. It never writes/accepts the EULA; the existing file must remain byte-identical. Do not use a production world/directory.

With current source-bound native resources already generated, and the disposable directory prepared as above:

```sh
python3 minecraft-mod/tests/server_boot_fixture.py --run-directory minecraft-mod/java/adapters/fabric-1.21.1/run --offline
```

An explicitly labelled development resource requires `--development`. Missing consent/configuration/resources fail before successful execution, not as a skipped test. Loom fixture directories are canonicalized and expressed relative to the adapter so the game's actual working directory is the selected directory. Ordinary and fixture game launch tasks depend on production-archive verification, not merely argument order.

The fixture executes 29 checks against the actual dedicated Minecraft world and required server transformation: current world ownership, native helper readiness/idle status, an additional guarded loopback listener, wrong-capability rejection, coalesced capability plus real Vanilla status handshake/response, listener revocation, ordinary Vanilla status after private-listener cleanup, exact native-child cleanup and world-owner retirement on server stop. `online-mode` remains enabled. The fixture uses Vanilla status requests to check the guarded listener, world lifecycle and cleanup.

The wrapper accepts only a bounded, exact-schema, run-bound result with the full case set, exact nonzero check count and cleanup flags. It shares the bounded 240-second process-group/1 MiB diagnostic runner with the client fixture; logs and Gradle exit are not the result contract. Dedicated-world execution is verified on macOS/aarch64 with the pinned Fabric/Minecraft 1.21.1 inputs.


## Isolated local gameplay verification

`localGameFixture` is an opt-in test Mod/source set, excluded from ordinary jars
and runs. The production archive verifier rejects fixture classes. It drives the
actual Minecraft account/room widgets and original Multiplayer server-list controls and Vanilla login, configuration
and play through the production Mod, not a direct connection to the ordinary
server port or an echo server.

Prerequisites are Python 3, Rust, Go on `PATH`, Java 21, a working graphical
Minecraft session and the pinned Cargo/Gradle dependencies. From the repository
root, explicitly accept the server EULA and the disposable loopback server's
**offline Minecraft authentication** mode:

```sh
python3 minecraft-mod/tools/local_game.py --accept-eula --offline-mode --offline --development
```

`--offline` restricts dependency resolution to local caches; it does not disable
the runtime's normal network discovery. `--development` admits only explicitly
labelled, source-bound development native resources; omit it only for a clean
release-mode checkout. Neither option bypasses archive/source verification.

The wrapper builds the unchanged local Go Control and current native agent,
creates fresh private P2WLAN accounts and one room, and starts separate dedicated
server and client JVMs. It completes fresh-install Vanilla accessibility setup,
checks empty/filled/saved-account button eligibility and the HTTP warning, then
uses the actual register, login, join-room, connect, original Multiplayer
selection/Join Server and room-stop controls. It checks retained ordinary saved
servers, human-readable virtual LAN rows, measured native peer telemetry,
browser-close catalog cleanup and retained login/Control state after room stop. It also checks adjacent square-logo placement in the title/multiplayer/pause menus, the default-off HUD, display-setting persistence/reopening, native window statistics and the rendered advanced HUD. A disposable, separately owned helper fails profile creation before hello; the rendered diagnostic screen still enables copy/log controls, preserves numeric OS evidence and excludes private paths/content from the clipboard. Native peer snapshots retain the original runtime's identity fences.

Success requires both exact, run-bound results: 68 server and 65 client
assertions, one actual Vanilla player login, 20 ordered C2S player commands with
matching S2C game messages and experience-level changes, at least 18 seconds of
play plus world-tick progress, player disconnect, room stop, world retirement and
both native helpers exiting. A helper `READY`, world `HOSTED`, process exit or
log message alone cannot pass. All waits have deadlines; the wrapper owns at
most three game/control process groups and retains at most 1 MiB of diagnostics
per process. It does not silently rerun a failed game test.

Only newly created disposable test directories are used. EULA consent and
`online-mode=false`, creative/peaceful flat-world settings apply only to that
fresh loopback server; existing worlds, launcher accounts, server properties,
private profiles and release bundles are unchanged. Builds are isolated from
active JVM classpaths. After confirmed group cleanup, temporary credentials,
profiles, Control database and worlds are removed. Bounded private diagnostics
and results remain under the ignored adapter `build/local-game-fixture/` tree;
uncertain process cleanup retains private test state and fails closed. Do not
publish these diagnostics as product documentation or release evidence.

This fixture is verified on macOS/aarch64 with Fabric/Minecraft 1.21.1 only.
Linux graphical execution, Windows/HMCL, other loaders/versions, forced Relay,
public NAT, cross-machine play, online Minecraft authentication, integrated
hosting/pause and long-duration/network-change behavior remain unverified.


## HMCL ZIP packaging

`tools/hmcl_pack.py` creates a CurseForge-format ZIP recognized by HMCL. Its
`manifest.json` pins Minecraft 1.21.1 and Fabric Loader 0.16.14; `overrides/mods`
contains only the checked P2WLAN Mod and the exact Fabric API 0.116.7+1.21.1 Maven
artifact. A short UTF-8 Chinese installation guide is included both outside and
inside `overrides`. The game, Java, launcher accounts, server addresses, private
profiles, logs, worlds and EULA files are not copied into the pack. The launcher
obtains the game and loader; the player uses their own account and Java 21.

After generating current **release-mode** native resources, from the repository
root, choose a native target actually present in the verified Mod:

```sh
python3 minecraft-mod/tools/java.py hmcl-pack -PhmclPlatform=windows-x86_64
```

The task verifies the release Mod before packaging, resolves the original API jar
through Gradle, and checks its fixed SHA-256. It writes
`minecraft-mod/java/adapters/fabric-1.21.1/build/distributions/p2wlan-hmcl-fabric-1.21.1-windows-x86_64-0.1.0.zip`.
A missing Windows resource, development native, changed source binding or wrong
API artifact fails the task; a Mac-only jar cannot produce a Windows-target pack.
The same explicit property selects any other recognized target present in the
verified Mod.

Verify that exact pack independently, from the same clean checkout:

```sh
python3 minecraft-mod/tools/hmcl_pack.py verify minecraft-mod/java/adapters/fabric-1.21.1/build/distributions/p2wlan-hmcl-fabric-1.21.1-windows-x86_64-0.1.0.zip --platform windows-x86_64
```

`P2WLAN-PACK.json` binds the source revision, native input digest, target and each
payload's size/SHA-256. Checking rejects extra/missing entries, ZIP links,
non-matching loader instructions, tampered metadata and stale native resources.
Publication is create-only and atomic; an identical existing pack is reused, not
rewritten, and a different existing file is never overwritten. The index and
printed archive SHA-256 record the packaged bytes and build identity. HMCL
real-device verification remains incomplete. This is a ZIP, not an executable
installer; import it through HMCL's modpack installation entry, not by placing the
ZIP in `mods`.
