# Mod gameplay verification reference

[Technical index](../../README.md) · [User guide](../quickstart.md) · [Build and packaging](building.md) · [Gameplay verification](testing.md)

Opt-in local game fixtures, their consent requirements, result contracts and platform scope. Commands run from the complete repository root unless a distribution directory is explicitly selected. Paths beginning with `java/`, `native/` or `tools/` in prose are relative to `minecraft-mod/`.

## Dedicated console and restart workflow

`python3 minecraft-mod/tools/java.py test` includes the game-independent
dedicated-controller, console-input and settings harnesses. They use controlled
Native replies and explicit ticks/time to check saved room/share restoration,
account/process/world fencing, cancellation, one-attempt failure handling,
runtime readiness deadlines and atomic bounded-file persistence. Password-input
parsing checks fixed errors, quoting and argument limits without echoing inputs.

The Minecraft fixtures below cover their stated world/listener/gameplay paths.
Actual dedicated-console command submission, a full server restart restoring the
saved room/shared world, and console-history behavior have not yet been checked
inside a running game or hosting panel. These are separate from the portable
controller and file checks.

## Isolated integrated-world gameplay verification

The same test-only `IntegratedHostChecks` source runs on all three loaders. Loader
fixtures supply client ticks, command and player/world lifecycle events only.
`fixtureCommon` data is compiled into each independent host/guest test mod from
one source; the host runtime does not load the dedicated/guest fixture mod.
None of these sources are in ordinary runs or production archives.

```sh
python3 minecraft-mod/tools/local_game.py --loader neoforge --integrated --accept-eula --offline-mode --offline --development
python3 minecraft-mod/tools/local_game.py --loader fabric --integrated --accept-eula --offline-mode --offline --development
python3 minecraft-mod/tools/local_game.py --loader forge --integrated --accept-eula --offline-mode --offline --development
```

The host creates a disposable creative/peaceful flat world with cheats disabled.
It checks local pause before publication, then uses the actual P2WLAN account
screen's explicit share action. The exact current `HOSTED` publication keeps
server ticks progressing while the host stays in the pause menu, without a
Vanilla LAN listener or announced port. A second real client discovers and joins
through the original Multiplayer controls and completes the same 20 ordered
C2S commands / S2C game-state and message replies over at least 18 seconds.
After guest disconnect, the host uses the actual unhost action, verifies local
pause restoration, stops the room and follows Vanilla's level-disconnect/world
shutdown sequence. Success requires all **80 host / 65 guest** checks, the exact
nine host cases, world retirement and both native helpers exiting.

The execution scope is local macOS/aarch64, Java 21 and Minecraft 1.21.1 with the
pinned Fabric, NeoForge and Forge versions. The integrated server retains its Vanilla
authentication setting before, during and after sharing. The guest uses an offline
game name through the production P2WLAN capability-admitted login path.
EULA consent, bounded process/result checks, isolated build outputs and private
state cleanup are the same as the dedicated gameplay mode below. Failed runs
are retained as failures; a cleanup failure preserves a fixed original execution
reason when available.

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
creates fresh private P2WLAN accounts and one room, and starts a dedicated server
plus client JVM, or two client JVMs with `--integrated`. It completes fresh-install Vanilla accessibility setup,
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

The local Minecraft 1.21.1 compatibility matrix uses Java 21 on macOS/aarch64:

| Loader | Dedicated host / real client | Integrated host / real guest | Gameplay Mod input |
| --- | --- | --- | --- |
| Fabric 0.16.14 / API 0.116.7+1.21.1 | 68 / 65 checks | 80 / 65 checks | Source outputs |
| NeoForge 21.1.256 | 68 / 65 checks | 80 / 65 checks | Source outputs; separate [installed dedicated/status checks](adapters.md#neoforge-adapter) |
| Forge 52.1.16 | 68 / 65 checks | 80 / 65 checks | Source outputs and checked final JarJar archive |

These local runs use offline Minecraft authentication. Linux graphical
execution, Windows/HMCL, other game versions, forced Relay, public NAT,
cross-machine play and long-duration/network-change behavior are separate
verification targets.

## Installed Universal gameplay verification

Use an absolute, current source-bound archive with the local fixture:

```sh
python3 minecraft-mod/tools/local_game.py --minecraft 1.20.1 --loader forge --installed-mod /absolute/path/to/p2wlan-universal.jar --accept-eula --offline-mode --offline --development
python3 minecraft-mod/tools/local_game.py --minecraft 1.20.1 --loader forge --installed-mod /absolute/path/to/p2wlan-universal.jar --integrated --accept-eula --offline-mode --offline --development
```

Select `fabric` or `neoforge` and the game version in the profile table for other
cases. Dedicated and integrated checks retain the exact 68/65 and 80/65 host/guest
contracts, Vanilla login, 20 ordered exchanges, sustained play and world/helper
cleanup. Archive hash and module/class backing are checked before the assertions.
Source outputs and portable/native/bootstrap project fallbacks are excluded.

Forge 1.16.5, 1.18.2 and 1.20.1, plus early NeoForge 1.20.1, use the official production SRG runtime. Their
pinned installers prepare both sides; patched outputs, library metadata, assets
and natives are checked against official digests. The observer fixture is
independently reobfuscated with its exact resource-pack metadata and captured
in a strict run plan. Derived client/server SRG files retain their captured
size and SHA-256. Each production launch rechecks the SDK, fixture and original
Universal archive. Named MCP
userdev runs explicitly reject the installed archive. Fabric uses its installed
intermediary namespace and the Java version selected by the exact profile. On macOS/aarch64, the older SDK fixture substitutes a pinned complete LWJGL Java/native family, and records those artifact identities in its run plan.

The installed fixture selects the [exact installation profiles](building.md#universal-installation-archive) and checks dedicated
68/65 or integrated 80/65 host/guest results, Vanilla login, 20 ordered exchanges
over at least 18 seconds, and world/helper cleanup. Java 17 runs 1.16.5, 1.18.2
and 1.20.1; Java 21 runs 1.21.1. It uses local Control and Direct traffic with
explicitly authorized offline game authentication. Verification records bind
the final Universal digest to the source identities, exact profile and mode.
The local fixture supports POSIX hosts; Windows uses HMCL. Windows/HMCL,
remote machines, forced Relay and public NAT remain unverified.
