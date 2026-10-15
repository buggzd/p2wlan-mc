# Minecraft adapter contracts

[Technical index](../../README.md) · [User guide](../quickstart.md) · [Build and packaging](building.md) · [Gameplay verification](testing.md)

The shared game implementation and exact loader-specific build and integration boundaries. Commands run from the complete repository root unless a distribution directory is explicitly selected. Paths beginning with `java/`, `native/` or `tools/` in prose are relative to `minecraft-mod/`.

## Fabric adapter bootstrap

The explicit Fabric adapter targets **Minecraft 1.21.1**, with Fabric Loader 0.16.14, Fabric API 0.116.7+1.21.1, the official Minecraft 1.21.1 Mojang mappings and Loom 1.10.5 as its pinned build inputs. Its loader metadata accepts Fabric/Minecraft 1.21.1 only. Loader events stay in `java/adapters/fabric-1.21.1/`; Minecraft 1.21.1 client screens, game joins and display observers are shared under `java/game-1.21.1/`. Common Java and native networking remain game-independent.

From a clean repository checkout, generate the host resource and build the adapter:

```sh
python3 minecraft-mod/tools/native_bundle.py --offline
python3 minecraft-mod/tools/java.py fabric-build
```

The explicit adapter build remaps its jar, embeds the common and native-resource jars, and checks the exact loader entrypoints, Java 17 game/portable bytecode read against the Java 21 SDK, absence of game/loader references in common bytecode, source-bound native manifests and executable hashes. Empty, stale, mismatched or duplicate native resources fail the artifact check. The checker emits public metadata binding the final jar SHA-256, source commit, embedded version and native verification targets; archive verification checks the packaged bytes and build identities. Common Java tests do not configure Loom or fetch Minecraft. A single-host resource package does not provide executable coverage for other operating systems or architectures.

For explicitly labelled development resources and a development build:

```sh
python3 minecraft-mod/tools/native_bundle.py --development --offline
python3 minecraft-mod/tools/java.py fabric-build -PdevelopmentNative
python3 minecraft-mod/tools/java.py fabric-server -PdevelopmentNative
python3 minecraft-mod/tools/java.py fabric-client -PdevelopmentNative
```

`fabric-server` uses Loom's development server; it does not accept or rewrite Minecraft's EULA. Game/account terms must be accepted by the user before a playable server can run. The development property permits development manifests in the build check and forwards the same explicit opt-in to Loom's development JVM. A normally installed adapter rejects development-native resources by default.

The common loader entrypoint contains no client initialization. Dedicated-server lifecycle events start the native helper off the game thread and close it when the server stops. On a client, the helper lifetime follows the application rather than individual integrated worlds. The shared `AgentLifecycle` is single-use: one startup worker, one 40-second startup deadline, no retry/work queue, immediate revocation on close, and identity checks before an asynchronous client reference can be used. Late startup completion closes its own child rather than publishing a revoked owner.

The dedicated-server `/p2wlan status` command requires permission level 4 and shows the room-restoration phase, stable reason and saved room/share intent. The client command `/p2wlan-client status` shows helper process/IPC readiness and stable startup/cleanup reason codes. The client exposes a 20×20 pixel LAN-logo button beside existing controls in the title, multiplayer and pause menus; bare `/p2wlan-client` opens the same entry. An idle helper shows account/membership controls; an active `starting`/`ready` runtime shows room controls. The screens support explicit login/register, saved-account room membership, bounded paging, refresh, create/join membership and logout. Room create/join forms require a password of 8–72 UTF-8 bytes and reject invalid input locally before submitting a native request; passwords are not trimmed or normalized. Password fields mask rendering and narration, block copy/cut and clear widget retention after submission/removal/resize; Java strings are not securely zeroisable. No Minecraft/Microsoft credential is required by these screens.

A shared completion gate permits at most one native request, local intent or queued game-thread presentation. Process/view/runtime identities are rechecked at admission, the queued IPC write and the actual presentation boundary. Closing a screen discards late presentation without replaying or claiming to undo a submitted mutation. Buttons derive eligibility from native snapshots, not a second account/runtime state machine. Creating/joining membership does **not** connect the overlay runtime; clicking a room-list row is the separate connection intent. Reopening P2WLAN after stopping a room uses the saved native session and returns to the room list without requesting a password or Control URL again. Explicit sign-out clears the session; stopping a room does not.

The runtime screen maintains the Mod's virtual room network only. It shows four compact read-only cards (RTT, recently observed online devices, local virtual IP and path), with explicit room disconnect and return-to-game actions. Connecting a room does not join a world. Returning from a game or closing the P2WLAN UI leaves the room connected until explicitly disconnected. It does not create an OS TUN interface or modify system routes.

Open Minecraft's ordinary **Multiplayer** screen to select a game server. Current P2WLAN discoveries appear as transient LAN rows alongside unchanged saved servers and ordinary LAN discovery. The rows use the authenticated Control device label, or a generic Minecraft server label, plus room name and virtual address; opaque node/world capability IDs are not displayed. These labels are not Minecraft MOTDs. Rows are not written to `servers.dat` or broadcast to the OS LAN. Vanilla selection, double-click, Enter and the Join Server button start the fenced Mod join/cancel flow. Native reauthorizes the selected runtime, peer and exact remote-world identity when creating the local proxy; actual forwarding revalidates the original room and world capabilities. Joining remains refused while a local/remote world or another unreleased attempt is active.

The application-scoped display observer reads the original native owners through at most one read-only IPC request (five-second deadline) at a time and waits one second between observation cycles. It projects at most 16 peers from a complete native roster of at most 128 members, and 32 services total (at most eight per peer); capacity limits are reported as `VIEW_CAPACITY_16` / `SERVICE_CAPACITY_32`. Telemetry observations expire after five seconds and service catalogs after 30 seconds. Owner/runtime/generation/session changes, member removal and offline observations discard incompatible rows; stale room observations hide them. Discovery occurs only while the Multiplayer browser is open; closing it cancels its discovery lifetime and clears the catalog, without stopping the room. Replacement or shutdown cancels the sole pending read and clears all observations.

RTT and jitter come from the existing native committed path's health measurements, never HTTP/IPC timing. No extra probe or path selection is introduced. Missing, contended or old measurements display unknown/pending instead of fabricated zero milliseconds. The panel uses the current game peer when playing, otherwise the lowest measured RTT among recently observed online peers; the tooltip identifies the measured device. Online count includes the recently observed subset plus the local device, with at most 16 remote peers observed. Direct uses green, Relay blue (normal and usable), connecting yellow. Hover cards/server metrics for virtual IP, RTT, jitter, current confirmed Direct-path MTU (matching generation/session/candidate epoch) and cumulative send/receive byte counters. Path MTU is the original runtime's diagnostic measurement; the Mod TCP endpoint uses the [Native supervisor transmit ceiling](architecture.md#native-agent-entry-and-runtime-supervisor). The optional in-game overlay reports only the current P2WLAN game peer and hides with the HUD/debug screen. It is disabled by default. Required exact-version Multiplayer Mixins and their bytecode/refmaps are verified in the production archive; the client-only hooks never initialize on a dedicated server.

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


The join lifetime owns one native proxy, one real Minecraft connection and two one-shot completion cells, with no retry/executor queue. The required client Mixin scopes the original synchronous bootstrap to that exact connection object and loopback address; ordinary Vanilla bootstraps remain unchanged. A 40-byte capability precedes all game codecs, with a two-second total connect/prefix deadline. Native join proxies send no local ACK, so every returned byte is Minecraft data. Login begins on the game thread only after a fenced handoff, retaining Vanilla login/authentication, configuration, compression, encryption and resource-pack consent. Minecraft session credentials are not replaced. Only login/configuration connections not already owned by Vanilla's play tick are ticked by the adapter. Once handed to Vanilla play, the old owner never invokes its pre-play disconnect handler again; a normal Vanilla connect/transfer or unrelated play connection retires the old presentation intent. Cancel, channel close, runtime replacement and shutdown revoke the exact local intent; a replacement cannot start until native release and channel closure. The [local gameplay fixture](testing.md#isolated-local-gameplay-verification) checks the rendered UI and Vanilla login, configuration, play and disconnect flow on macOS/aarch64.

## Guarded local world publication

The shared `WorldSessions` owner retains one exact dedicated/integrated server lifetime and revokes its world guard **before** server stop/replacement. Each loader forwards start/tick/stop events and supplies its version-specific bind callback; queued intents and native publication use the same world identity. A publication has one worker and one JVM capacity slot, with no queued setup or automatic retry. A revoked, delayed native `host` reply is retained only for exact registration cleanup, never published for a replacement world. Local cleanup failure holds the capacity slot; uncertain native cleanup terminates that exact helper rather than replaying a mutation. Queued world IPC requests recheck their lifetime immediately before the pipe write.

The adapter uses Minecraft's existing codecs, rate limiter, handshake handler and authoritative game-connection list, with an additional ephemeral **127.0.0.1** listener. It does not call the integrated-server open-to-LAN operation or advertise a LAN port. Existing dedicated-server listeners/configuration are unchanged: server operators remain responsible for restricting any separately configured public game port. Minecraft sees a loopback source, not the remote player's physical/virtual IP; IP-based bans or other Mods that rely on source IP need separate consideration. No forwarded-IP identity spoofing is performed.

The Minecraft-facing listener itself consumes a fresh 40-byte local capability **before** initializing Vanilla codecs/connections. The outer `WorldGuard` authenticates that target and consumes its one-byte readiness acknowledgement before acknowledging native readiness; neither local prelude/acknowledgement reaches Minecraft. Unauthenticated local users cannot bypass the outer guard by connecting directly to this listener. A single Mod-owned NIO event loop drives accept/child registration, so unauthenticated local connects cannot build an unbounded registration queue on another game event loop. At most 32 children, 40 prelude bytes per child and one two-second timer per child are retained; existing Minecraft packet/player internals remain Minecraft-owned. Target revocation fences inbound/outbound game bytes and closes owned channels. Target cleanup waits at most two seconds each for children/listener closure and three seconds for the sole event loop to terminate; failed cleanup does not release the publication slot. Same-user code and other Mods remain inside the trusted local environment.

The one physical server Mixin under `java/game-1.21.1/src/server/java` is compiled into every loader archive and checked during bootstrap; absence fails before an unguarded bind. Its explicitly scoped bind reuses Vanilla's bind/stop ordering under the existing channel-list monitor, with a two-second wait limit. The Mod owns and closes its extra listener rather than accumulating closed entries in Vanilla's listener list. Ordinary Vanilla binds retain their original behavior. Concurrent full-game bind/stop competition remains unverified; the [isolated local gameplay fixture](testing.md#isolated-local-gameplay-verification) checks dedicated and integrated worlds. A required client-only IntegratedServer Mixin derives remote/pause semantics from the exact current `HOSTED` publication and its world/runtime fence. It does not maintain another hosting flag, change cheats/default game mode, announce a LAN port, or alter ordinary Vanilla LAN publication. The [integrated fixture](testing.md#isolated-integrated-world-gameplay-verification) checks unshared pause, hosted menu tick progress, unhost/pause restoration and world/helper cleanup on all three pinned loaders.

In an integrated world, the runtime screen exposes explicit host/unhost intents without requiring cheats. One server-thread task is retained per world, with a three-second deadline. An expired result keeps its capacity until the queued runnable actually drains; the late runnable cannot mutate a replacement world/runtime or begin a host intent after its view expires. Closing a view after the effect has already begun does not undo that world-owned intent. Host receives the originally selected runtime rather than reacquiring a newer one. `Stop room` revokes local publication before requesting native stop, including a runtime still `starting`. A stop acknowledgement waits for a matching native status transition before returning to account controls; late presentation rejects another active runtime. None of these actions silently accept terms or automatically publish a world.

Dedicated operators configure P2WLAN after normal server startup. All commands
require permission level 4; console input omits the leading `/`:

| Command | Behavior |
| --- | --- |
| `p2wlan init URL` | Save the Control origin and initialize the helper. |
| `p2wlan login ACCOUNT PASSWORD` / `register ACCOUNT PASSWORD` | Save a Native-owned account session. |
| `p2wlan rooms` | Read the saved account's rooms, including while connected. |
| `p2wlan create NAME PASSWORD` / `join CODE PASSWORD` | Create/join membership, save its canonical room ID and connect it. |
| `p2wlan connect ROOM` | Connect a member room by ID/eight-digit code and save that selection. |
| `p2wlan runtime` / `world` | Report Native `idle`/`starting`/`ready` or local publication status. |
| `p2wlan host` | Publish the current world and enable sharing for later restarts. |
| `p2wlan unhost` | Revoke publication and disable saved sharing, keeping the selected room. |
| `p2wlan disconnect` | Revoke publication, stop the runtime and clear room/share intent; retain account/membership. |
| `p2wlan logout` | Stop the room and clear the saved account session and room/share selection. |
| `p2wlan restore` | Make one explicit attempt to restore the saved selection. |

`init`, `login`, `register`, `create` and `join` require the actual dedicated
server console source; players, RCON and command blocks are refused. Their
bounded quoted-field parser produces fixed errors without echoing credentials.
Terminal/panel command history can retain complete inputs. The
[user guide](../quickstart.md#专用服务器控制台配置与重启恢复) gives quoted examples.

Each dedicated world owns one server-thread `DedicatedServerController`.
`config/p2wlan-server.json` saves the non-secret Control/account/room selection
and share flag. After helper readiness, one startup attempt reconnects the saved
room for the same Native account and waits at most 40 seconds for the exact
runtime to become ready. An enabled share flag then publishes the current world.
Manual account/room changes stop the prior publication/runtime first; command
revision, world and process checks fence queued writes and completions. Failures
wait for operator action, without automatic login, room creation or retry.
Integrated-world controls retain their explicit share/unshare behavior.

The subprocess/world tests check capacity, native-owner/runtime fencing, cancellation during setup, cleanup of late host replies and malformed registration failure. Protected-target socket tests check that both local capability handshakes disappear before game bytes. Fabric transport tests use actual Netty pipelines with fragmented/coalesced input, virtual-time expiry, capacity, revocation and delayed Vanilla initialization, plus a real 128,000-byte TCP exchange between the Java guard and a protected Netty echo target. The socket tests use a protected Netty echo target. The [independent account console](building.md#dedicated-server-account-console) remains optional offline maintenance and requires a stopped server to obtain the profile lock. The [gameplay fixture](testing.md#isolated-local-gameplay-verification) checks Vanilla login and at least 18 seconds of play; the new console/restart flow has the separate [verification scope](testing.md#dedicated-console-and-restart-workflow). Cross-machine gameplay, cancellation during actual configuration and world-replacement acceptance remain separate verification targets. Ordinary builds and the hook/server-boot fixtures do not accept EULA/account terms; the [local gameplay fixture](testing.md#isolated-local-gameplay-verification) requires explicit consent.

## NeoForge adapter

The opt-in `java/adapters/neoforge-1.21.1/` adapter targets **Minecraft 1.21.1**,
**NeoForge 21.1.256**, **ModDevGradle 2.0.148** and **Java 21**, using the game's
Mojang-named API. It supplies loader events, operator/client commands and the
required server-connection Mixin. `WorldSessions`, Native IPC, account/runtime
ownership and the protected Netty transport are shared with Fabric. The same `java/game-1.21.1/` client implementation supplies account/room screens,
Multiplayer joins, display settings, diagnostics and integrated-world controls to
both loaders. Dedicated and integrated client/server gameplay is verified locally
on macOS/aarch64 with the pinned Minecraft 1.21.1 loaders in the [gameplay matrix](testing.md#isolated-local-gameplay-verification).

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
accept the EULA. Dedicated operators initialize accounts and configure restart
restoration through the Minecraft console commands above. Development native
resources require `native_bundle.py --development` and `-PdevelopmentNative` for
the Java build/run, as with Fabric.

The checked NeoForge SDK input contains exactly three JarJar components: common Java, portable
Netty transport and native resources. The resource module has the stable Java
module name `io.p2wlan.mc.nativebundle`. JNA 5.14.0 comes from the pinned game's
bootstrap layer; no second named JNA module is embedded. Development runs
explicitly add the portable libraries to ModDev's additional runtime classpath.
The common `LayerNativeResources` resolver selects the exact local native-resource
module through its module reader, or uses the development classloader. Loader
glue supplies typed Union/JarJar backing edges. The common policy checks at most
eight levels before reading; ambiguous/remote
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

## Forge adapter and build inputs

`java/adapters/forge-1.21.1` targets **Minecraft 1.21.1**, **Forge 52.1.16**,
**ForgeGradle 7.0.29**, **JarJar Gradle 0.2.3** and **Java 21**. Loader events,
client render/widgets and typed loader-filesystem edges are the adapter's only
implementation responsibilities. Its account screens, game joins, display
observer, operator commands and server/client Mixins are the same physical
Minecraft sources used by Fabric and NeoForge. Native and `WorldSessions`
retain their existing state ownership.

Forge uses the separate `java/forge` Gradle **9.3.1** build graph. It references
the original portable projects, not copies. Fabric, NeoForge and ordinary Java
checks retain the Gradle 8.14 entry point. The Forge bootstrap copies the existing
tracked wrapper into one of two ignored, create-only slots and enforces the fixed
Gradle distribution SHA-256. No new wrapper binary is stored in source control.

The pinned Forge build uses JDK **25** for Mavenizer and JDK **8** as a launcher
configuration helper, in addition to the game's JDK 21. Install those toolchains
or permit the settings' Foojay resolver to provision them. Optional
`P2WLAN_FORGE_JAVA25_HOME` / `P2WLAN_FORGE_JAVA8_HOME` select absolute installed
helper directories; `JAVA_HOME` selects Java 21 outside the macOS automatic
selection. An optional `P2WLAN_FORGE_GRADLE_ZIP` selects an absolute local Gradle
9.3.1 ZIP only after its fixed official SHA-256 matches. These are build inputs,
not account or server settings.

With current source-bound native resources:

```sh
python3 minecraft-mod/tools/java.py forge-build -PdevelopmentNative
python3 minecraft-mod/tools/java.py forge-server -PdevelopmentNative
python3 minecraft-mod/tools/java.py forge-client -PdevelopmentNative
```

Use `native_bundle.py --development` for development resources; a clean release
checkout uses `native_bundle.py` and omits `-PdevelopmentNative`. The Forge assembly input is
`minecraft-mod/java/adapters/forge-1.21.1/build/libs/p2wlan-forge-1.21.1-0.1.0.jar`.
Players install the checked [`p2wlan-universal.jar`](building.md#universal-installation-archive).
The `-slim.jar` lacks the required portable/native payloads and is not an install
artifact. `forge-build` requires the explicit 33-check resource-policy harness, six
launcher-argument checks and production-archive/source verification. The archive contains the same three
JarJar components as NeoForge, with one named native-resource module and no
second JNA payload. The development run forwards both required Mixin configs;
installed runs read them from the final archive's `MixinConfigs` manifest.
Archive checks require the Minecraft 1.21.1 resource/data-pack metadata, strict
Mixin versions, unique common/transport class ownership and native payloads only
in the named resource module. Build/test classes and duplicate JNA payloads are
rejected. The resource harness includes actual Union/JarJar paths seen across
separate classloaders. Forge's NIO provider may belong to the bootstrap loader;
the adapter reads only the pinned SDK's public zero-argument `Path` getters,
checking their exact class and signature before the shared locality/depth policy.

ForgeGradle merges classes/resources within each source-set output, not between
test mods. Its opt-in local fixture compiles dedicated and client observations
into one dist-filtered test mod; the integrated host has a separate output and
its own copy of the single `fixtureCommon` source. Production archives reject
all fixture classes. Game checks still use the shared exact result contracts.
A build-only entry adapter places the asset-cache argument before Slime
Launcher's game separator. It is excluded from the installed JAR. Assets use
an ignored build cache, not the user's Minecraft directory. For cached fixture execution, `P2WLAN_FORGE_ASSETS` may explicitly
select an absolute asset cache; normal CLI runs can pass
`-PforgeAssetDirectory=/absolute/path/to/asset-cache`. The launcher's asset checks
remain enabled. First-run toolchains, dependencies and assets require network
access before a cached/offline fixture run.

The Forge local gameplay fixture accepts `--installed` for final-JarJar loading:

```sh
python3 minecraft-mod/tools/local_game.py --loader forge --installed --accept-eula --offline-mode --offline --development
python3 minecraft-mod/tools/local_game.py --loader forge --installed --integrated --accept-eula --offline-mode --offline --development
```

Each mode creates fresh host/guest `mods/` directories, copies only the checked
Mod archive and retains its SHA-256 through each launch. The Forge runtime
excludes production source outputs and portable project libraries; only the
test observation Mod remains on the development classpath. Before the shared
game checks, each JVM verifies the exact installed Mod path/hash, same-layer
`common` / `netty.transport` / `io.p2wlan.mc.nativebundle` modules, and at most
eight typed backing steps to that same installed archive. Native helper startup
then exercises the installed resource resolver. The fixed Slime development
launcher supplies the game/Forge runtime for both source and installed modes.

Both source and final-JarJar modes complete the dedicated **68 host / 65 guest**
and integrated **80 host / 65 guest** contracts on macOS/aarch64. Windows/HMCL
and other game versions require platform/version-specific execution.

## Shared Minecraft game layer

`java/game-1.21.1/src/server/java` supplies one operator-command and protected-server-Mixin implementation
for all three loaders. Each loader registers it with the existing `AgentLifecycle`,
the current `WorldSessions` lookup and its transport-hook availability check.
Command completions retain the original process/world fences; loader glue
does not own another room, publication or command queue.

`java/game-1.21.1/src/client/java` is compiled directly into all three loader archives;
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
python3 minecraft-mod/tools/local_game.py --loader forge --accept-eula --offline-mode --offline --development
```

All three paths retain the shared 68 server / 65 client checks and real Vanilla
login, 20 ordered C2S/S2C exchanges, at least 18 seconds of play, room stop and
world/helper cleanup. The default loader remains Fabric. Verification currently
uses local macOS/aarch64 and Minecraft 1.21.1 with the pinned loader versions;
Windows and remote-machine execution remain separate verification targets.
