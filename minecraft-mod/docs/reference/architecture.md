# Mod architecture and protocol contracts

[Technical index](../../README.md) · [User guide](../quickstart.md) · [Build and packaging](building.md) · [Gameplay verification](testing.md)

State ownership, resource bounds, Native IPC and the userspace game channel. Commands run from the complete repository root unless a distribution directory is explicitly selected. Paths beginning with `java/`, `native/` or `tools/` in prose are relative to `minecraft-mod/`.

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

The build requires JDK 17 and 21. Common Java, transport and Bootstrap use the JDK-17 compiler; modern game SDKs use a JDK-21 reader and emit Java-17 classes. The Gradle 8.14 wrapper verifies its fixed distribution checksum. From the repository root:

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
All supported loader/version Mixins delegate to this same implementation.

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

## Shared-world Minecraft login

A published singleplayer world accepts offline Minecraft game names through its
P2WLAN listener. `ServerLoginConnectionMixin` reads the actual Netty channel;
`ServerLoginMixin` skips the session-server challenge only for a singleplayer
server and an admitted, currently live `LoopbackAdmission` owner. Before login and
final profile admission it rechecks that exact channel owner, including retirement
while Vanilla waited for its server tick. Legacy delayed player placement and
modern login acknowledgement/configuration completion recheck the same owner at
their actual protocol or player-commit boundary. The channel retains the original owner
identity after revocation; a retired private connection cannot become an ordinary
connection or acquire a replacement world. The integrated server's global
authentication setting and other listeners keep their Vanilla policy. Dedicated
servers continue to follow `online-mode`.

Vanilla generates offline player UUIDs from game names; participants use distinct
names. P2WLAN room, peer and world authorization remain with the original Native
and world owners. Minecraft profile admission does not replace those permissions.

## Shared join-preface transport component

The portable `ProxyPrelude` component wraps one Netty initializer/socket. It validates the exact loopback proxy target, writes the 40-byte local capability before installing any Vanilla initializer/codecs or emitting their channel-active event, and then forwards every received byte as game data. Native join proxies send no local readiness acknowledgement; this component must not consume an imagined acknowledgement from the Minecraft stream. A two-second total deadline starts at handler installation and covers connect/prelude, including sockets that never become active. One 100 ms lifecycle timer closes an idle retired attempt. Both timers are cancelled on close/removal. No game-byte buffer, work queue, reconnect or capability replay is added.

The component fences inbound/outbound pipeline delivery against its attempt owner and closes on out-of-order traffic, wrong target, expiry or revocation. Actual Netty/socket tests check delayed initialization, ordering, timeout, cancellation, late write completion, target mismatch, revoked buffer release and a 128 KB round trip. The scoped client bootstrap hook below installs this component for explicit Mod joins.

## Scoped Minecraft client bootstrap hook

The client-only, required `ClientConnectionMixin` redirects the exact Fabric 1.21.1 `ClientConnection.connect(InetSocketAddress, boolean, ClientConnection)` initializer installation. `ScopedClientConnect` captures one exact connection identity and loopback capability in a synchronous thread-local scope before asynchronous channel initialization. The hook installs `ProxyPrelude` only for this explicit operation; ordinary Vanilla connections and queries keep their original initializer. Missing transformation, nested/repeated consumption, wrong connection/target and retired attempts fail with stable `JOIN_BOOTSTRAP_*` codes. No address-matching registry, background work queue or transport retry is added.

The client configuration is explicitly client-only in Fabric metadata and has its own required refmap. Archive verification checks both configurations, their exact game-method mappings and class versions. Bootstrap admission tests use fake marker identities to exercise scope consumption and owner checks. The game connection screen uses this scope to admit protected joins; ordinary Vanilla connections do not gain a P2WLAN scope. The [real-client hook and local gameplay fixtures](testing.md) exercise their respective game integration paths.

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

The Fabric adapter supplies a game-root `logs/p2wlan-mod/` sink independent of the credential directory. Its always-available **诊断日志** screen displays initialization operation/category/OS error evidence, copies a redacted in-memory summary and opens the local log directory. These actions do not require an Agent, `hello`, account or room. See the [user diagnostic guide](../diagnostics.md).

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
