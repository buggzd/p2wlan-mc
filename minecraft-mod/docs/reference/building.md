# Mod build and packaging reference

[Technical index](../../README.md) · [User guide](../quickstart.md) · [Build and packaging](building.md) · [Gameplay verification](testing.md)

Source-bound Native resources, Universal installation archives, account distributions and HMCL ZIPs. Commands run from the complete repository root unless a distribution directory is explicitly selected. Paths beginning with `java/`, `native/` or `tools/` in prose are relative to `minecraft-mod/`.

## Build prerequisites and primary artifact

Use Python 3.11+, Rust with the host native compiler/dependencies, and installed JDK 17 and 21. Forge 1.21.1 additionally uses JDK 25 and JDK 8 helpers; its optional toolchain selectors are in [Forge build inputs](adapters.md#forge-adapter-and-build-inputs). Forge 1.16.5 requires the full JDK 8 including `lib/tools.jar`, selected by `P2WLAN_FORGE1165_MCP_JAVA_HOME`; its compiler uses JDK 17. Gradle wrappers and helper launchers verify their pinned distributions. First-run dependency resolution requires network access; use `--offline` only with complete caches.

From a clean, committed repository root, build the host Native resource and the installable archive:

```sh
python3 minecraft-mod/tools/native_bundle.py
python3 minecraft-mod/tools/java.py universal-build --output minecraft-mod/dist/p2wlan-universal.jar
python3 minecraft-mod/tools/universal_jar.py verify minecraft-mod/dist/p2wlan-universal.jar
```

| Artifact | Location |
| --- | --- |
| Player/client/server installation JAR from the command above | `minecraft-mod/dist/p2wlan-universal.jar` |
| Dedicated-server account distribution | `minecraft-mod/java/account-cli/build/distributions/p2wlan-account-0.1.0.zip` |
| Native build resources | `minecraft-mod/java/native-bundle/build/generatedResources/p2wlan/native/<platform>/` |
| Isolated SDK assembly intermediates | Temporary `minecraft-mod/java/universal-bootstrap/build/inputs-*` trees, removed after assembly |

The exact game/loader allowlist is documented under [Universal installation archive](#universal-installation-archive). Local packaging includes only Native targets already built into the resource directory; players need the resource matching their OS/CPU. The target manifests retain `native-build-info` or `wine-build-info` execution identities.

## GitHub Actions build

[`.github/workflows/minecraft-mod.yml`](../../../.github/workflows/minecraft-mod.yml) defines **Minecraft Mod Build**. It runs for pushes to `main` and `codex/**`, every pull request, and explicit manual dispatch. Shared Java/Python and repository checks run alongside the three Native jobs. The package job requires all checks and Native builds, then assembles every exact loader profile into one Universal JAR.

| Native target | Runner | Identity execution |
| --- | --- | --- |
| `windows-x86_64` | Windows 2022 | Native `--build-info` |
| `linux-x86_64` | Ubuntu 22.04 | Native `--build-info` |
| `macos-aarch64` | macOS 14 | Native `--build-info` |

The workflow requires exactly these three resource directories before packaging. It uses the same source commit and Native-input digest across runners, including exact source bytes on Windows. `macos-x86_64` and `linux-aarch64` remain recognized manual build targets.

After a successful run, download the **`p2wlan-minecraft-mod-<source SHA>`** artifact from the run's Artifacts section. The uploaded `minecraft-mod/dist/` contains:

| File | Purpose |
| --- | --- |
| `p2wlan-universal.jar` | The same client/integrated/dedicated installation JAR for all ten profiles, with three Native targets |
| Ten `p2wlan-hmcl-<minecraft>-<loader>-windows-x86_64.zip` files | One Windows x64 HMCL ZIP for each exact profile |
| `BUILD-METADATA.json` | Mod/source/Native identities, game and loader allowlists, HMCL targets and artifact hashes |
| `SHA256SUMS` | SHA-256 for the Universal JAR, HMCL ZIPs and build metadata |

Final distribution artifacts are retained for 14 days. Native transfer artifacts are retained for seven days. These are Actions build artifacts; the workflow does not create a GitHub Release. It builds and checks packages without launching Minecraft gameplay or HMCL.

To collect the same distribution format locally, first build a release Universal JAR containing the selected Native target. For a JAR that includes Windows x64:

```sh
python3 minecraft-mod/tools/distribution.py --mod minecraft-mod/dist/p2wlan-universal.jar --platform windows-x86_64 --download-fabric-api
```

The collector defaults to `minecraft-mod/dist/`, creates all ten HMCL profiles, and resolves missing Fabric API inputs through the registry's fixed official Fabric Maven or Modrinth URLs. It caches verified inputs under `minecraft-mod/java/build/fabric-api/` after SHA-256 and installation-JAR checks. Omit `--download-fabric-api` to require an already populated cache. Repeat `--platform` to package additional Native targets actually present in the JAR. Development resources, missing targets, changed inputs, unexpected output files and conflicting existing artifacts fail the collector. A separate `--output` directory may be selected.

## Native resource package

A native resource package is built from this checkout, not downloaded at runtime. The default build executes on the current host. From the repository root:

```sh
python3 minecraft-mod/tools/native_bundle.py
```

Release packaging requires a clean checkout. Manifest schema 2 records the execution method as `native-build-info` or `wine-build-info`; unknown methods and a Wine-labelled non-Windows target are refused. For an explicitly labelled development package, add `--development`. The producer selects Cargo's reported release artifact, runs its bounded `--build-info` command and checks its exact protocol, version, source commit, native-input digest and compiled platform before publication. The manifest binds those fields to the executable's SHA-256 and size; the copied resource bytes are checked again. A concurrent source change, ambiguous artifact or failed verification aborts packaging.

Compilation remaps repository/home source prefixes to generic build paths; a bounded scan rejects executable bytes that still contain those absolute build prefixes.

Generated executable resources remain under the ignored `java/native-bundle/build/` directory. The Java resource subproject refuses to build an empty resource jar. The recognised native-resource targets are macOS/Linux on aarch64/x86_64 and Windows x86_64. Windows resources use `p2wlan-mc-agent.exe` consistently in the producer, Java selector and archive verifiers; other Windows architectures are refused. Foreign packaging is limited to an explicitly selected Windows x86-64 target executed by a trusted Wine runner; there is no compile-only publication or silent runtime substitution. Java extraction uses a platform-specific executable-isolation backend. Real-device verification by platform is listed in [Project status](../../../README.md#项目状态).

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

The installed distribution is `java/account-cli/build/install/p2wlan-account/`; the matching zip is `java/account-cli/build/distributions/p2wlan-account-0.1.0.zip`, both beneath `minecraft-mod/`. Verification requires matching installed/zip bytes, Java 17 game-independent console/common classes, fixed dependencies, native executable hashes and current source binding. Native targets are limited to resources actually packaged on the build host, not inferred cross-platform coverage. For an explicitly labelled development bundle use `native_bundle.py --development --offline` and `java.py account-build -PdevelopmentNative --offline`.

Stop the Minecraft server first. Run as the same OS user that runs it, and pass that server's exact private-profile directory explicitly. For the Mod it is `SERVER_ROOT/config/p2wlan-private`. Do not copy profiles between servers/devices or scan another application's credentials. From the installed or extracted distribution root:

```sh
./bin/p2wlan-account status --profile-dir /absolute/path/to/server/config/p2wlan-private
./bin/p2wlan-account login --profile-dir /absolute/path/to/server/config/p2wlan-private
./bin/p2wlan-account rooms --profile-dir /absolute/path/to/server/config/p2wlan-private
./bin/p2wlan-account join-room --profile-dir /absolute/path/to/server/config/p2wlan-private
```

`register` creates a P2WLAN account; `create-room` creates a room; `logout` clears the saved account without deleting retained room identities. Login/register prompt for the root Control origin, identifier and password; for HTTP they print `WARNING HTTP_UNENCRYPTED` before identifier/password entry, without printing the URL. Successful HTTP login/register and saved-account status receipts repeat the warning. A failed warning-output write stops before credentials/helper launch. Room operations prompt for name/code and a required password of 8–72 UTF-8 bytes, matching the current Control password-based flow. Empty, short, overlong or malformed UTF-16 console input is rejected before helper launch; the native Agent validates byte length again before any room mutation request. The Mod does not expose an invite-token join flow. Passwords use the real Java console with hidden input. No password, token, identifier or Control URL option/environment variable is supported. Password operations require a terminal; there is no echoing stdin fallback. `status`, `rooms` and explicit `logout` need no interactive credential input.

Input is collected and validated before any helper starts. Fields use the native API's 256-byte UTF-8 limit; malformed surrogates, NUL/newlines, empty required fields and invalid eight-digit codes fail before launch. Returned console character arrays are cleared, and JSON argument references are discarded after encoding. Java strings and IPC buffers cannot be securely zeroized; terminal editing and hostile same-user code remain outside this boundary.

One lifecycle owns one helper and one request, with no mutation retry. Startup/request waits are at most 41/6 seconds (the underlying owner/client deadlines are 40/5 seconds); cleanup waits at most 20 seconds, including a bounded shutdown hook. Replies are checked against the exact lifecycle/native owner before capture. Output is a bounded historical command receipt, emitted after child/extraction cleanup, not a live room/path cache: status exposes authenticated/signed-out, and room operations expose only eight-digit codes. URLs, user IDs, JWTs, arbitrary room names and child stderr are not projected. An uncertain mutation emits `DELIVERY_UNKNOWN`; inspect `status`/`rooms` before deciding whether to retry. Profile-lock/privacy failures can terminate the native helper before hello and therefore surface as a generic typed startup/exit failure, not a diagnosis inferred from logs.

After setup, start the server normally and use the [operator commands](adapters.md#guarded-local-world-publication) to connect the chosen room and explicitly host it. The console does not bypass the native profile lock or become another writer of credentials/network state.

The opt-in console fixture uses the installed launchers and packaged native executable against the unchanged Go Control, disposable accounts and real POSIX terminals. It checks password masking/no echo, EOF/no-console rejection before helper launch, room/account persistence, same-profile exclusion and helper cleanup during an in-flight mutation. With the [Go Control executable](architecture.md#managed-account-and-room-profiles) built:

```sh
python3 minecraft-mod/tests/account_console_fixture.py --distribution minecraft-mod/java/account-cli/build/install/p2wlan-account --zip minecraft-mod/java/account-cli/build/distributions/p2wlan-account-0.1.0.zip --control minecraft-mod/native/target/server/p2wlan-control
```

A development distribution requires explicit `--development` on this fixture and admits development resources only for its child invocations. Diagnostic retention is capped at 64 KiB per child. Console/sequence execution deadlines are 72/300 seconds, followed by at most 23 seconds of graceful child-group cleanup and a three-second kill/reap wait; exact child groups and extracted files must be gone before success. No transcript, credentials or acceptance evidence is committed. The real terminal fixture is verified on macOS/aarch64; Linux terminal/device acceptance and Windows private-profile ACL/package support remain incomplete. The fixture covers the standalone account-console flow.

## Universal installation archive

`tools/universal_build.py` builds nine pinned loader SDK inputs, one neutral
Bootstrap and one Forge-36 carrier, then `tools/universal_jar.py` produces
`p2wlan-universal.jar`. Early NeoForge 1.20.1 shares the Forge-compatible
1.20.1 SDK input and game binding. Players install the same archive for all ten
exact profiles; clients, dedicated servers and integrated worlds use that JAR.

| Minecraft | Fabric Loader / API | Forge | NeoForge | Game Java |
| --- | --- | --- | --- | --- |
| 1.16.5 | 0.16.14 / 0.42.0+1.16 | 36.2.42 | — | 17 |
| 1.18.2 | 0.16.14 / 0.77.0+1.18.2 | 40.3.12 | — | 17 |
| 1.20.1 | 0.16.14 / 0.92.12+1.20.1 | 47.3.2 | 47.1.106 | 17 |
| 1.21.1 | 0.16.14 / 0.116.7+1.21.1 | 52.1.16 | 21.1.256 | 21 |

These are complete version pins. Missing loader/game pairs and other patch
versions fail startup selection. All P2WLAN Java classes use classfile major 61;
Minecraft 1.21.1's SDK and game still require Java 21. The authoritative game
sources remain in `java/game-1.21.1`. The four `java/game-api-*` modules adapt
background, tooltip, widgets, connection/login and narration APIs. Generated
adaptations enforce exact source contracts before producing build-only files.
Forge 1.16.5 additionally applies 63 reviewed MCP class aliases, while the
pinned official member mappings supply reobfuscation. Screens, commands and
connection ownership have one shared implementation.

Fabric 1.16.5, 1.18.2 and 1.20.1 add a narrow login-discovery Mixin. It exposes
the existing join owner's current LOGIN connection to Fabric networking when
Vanilla's lookup has no connection, preserving an original nonempty result.
Connection ticks and cleanup remain with the join owner. Modern inputs retain
the thin configuration-stage Mixin at player commit; 1.16.5 checks both direct
player-placement paths before the effect.

One neutral Fabric entry and one Forge-compatible entry select only their
version delegates. Each has one application initialization owner; its selected
server delegate owns `WorldSessions`. NeoForge 1.21.1 supplies its own event
glue. Sixteen narrow dispatch configurations use Mixin 0.8.4 minimum versions,
empty static lists and Java-17 headers. Bootstrap selects the leaf Mixins,
refmaps and client side for the active exact pair. Inactive namespaces return
no Mixins, including configurations parsed by Forge 36's Mixin 0.8.4.

The archive retains one owner each for common, transport, Native resources and
JNA. Core contents must agree across SDK inputs; overlapping Forge entry classes
pass restricted semantic comparison before one Java-17 copy is selected. JNA
5.14 keeps its Java packages and API. Only its exact JNI resource-prefix
constant and resource paths change from `com/sun/jna/` to `p2wlan/jna/`.
Verification reverses that class change, checks the original class inventory,
and requires unchanged JNI payloads. The private prefix prevents the launcher's
older JNA resources from satisfying the Mod's JNI lookup.

Forge 36 has no JarJar loader. The public Mod-locator carrier makes
the canonical nested core components available to its game classloader.
Extraction has eight atomic temporary slots, each bound to the outer archive's
size/digest and a process PID/start-time lease. Reclamation requires an exited
original process and unchanged directory/file identities; live, changed or
uncertain owners retain their slots. The installed verifier binds the loaded
classes and native resources to those exact indexed child archives. Modern
Forge loaders use their normal nested-component loading path.

The outer archive has an explicit Java-17 `p2wlan.bootstrap` SERVICE module
with no exports. Forge 40/47 GAME discovery obtains its automatic Mod module
through the official FML factory and its `ModJarMetadata`. Forge 52 uses the
public SDK metadata API bound to the same FML `ModFileInfo`, including the
complete package and service-provider graph. Thin SDK factory
adapters forward scanning and provenance callbacks to the locator's single
owner. This separates the SERVICE and GAME read graphs when they use the same
physical archive. The source resolver verifies the installed filesystem
provider, exact SDK archive and defining-loader/class-resource identities
before resolving the public local backing path.

Forge 1.20.1 uses `java/forge-legacy`; Forge 1.18.2 uses `java/forge-1182`.
Both use Gradle 8.8, ForgeGradle 6.0.47 and Java 17. Set the absolute
`P2WLAN_LEGACY_JAVA_HOME` or `P2WLAN_FORGE1182_JAVA_HOME` to select the compiler;
the corresponding `P2WLAN_LEGACY_GRADLE_ZIP` or `P2WLAN_FORGE1182_GRADLE_ZIP`
selects an optional checksum-verified local Gradle distribution.

Forge 1.16.5 uses the independent `java/forge-1165` graph, pinned Gradle 8.4
and ForgeGradle 6.0.47. Production/fixture code compiles with JDK 17; MCP patch
compilation requires a full JDK 8, including `lib/tools.jar`. Prepare both
installed toolchains before building:

```sh
export P2WLAN_FORGE1165_JAVA_HOME=/absolute/path/to/jdk17
export P2WLAN_FORGE1165_MCP_JAVA_HOME=/absolute/path/to/jdk8
python3 minecraft-mod/tools/forge_1165_gradle.py compile
python3 minecraft-mod/tools/forge_1165_gradle.py build
```

`P2WLAN_FORGE1165_GRADLE_ZIP` optionally selects an absolute local Gradle-8.4
ZIP after the fixed distribution SHA-256 matches. These commands produce a
checked SDK assembly input; player installation uses the Universal archive.
Forge 1.16.5 gameplay runs Java 17 with
`-Dnashorn.args=--anonymous-classes=false`; the installed fixture supplies it.
HMCL and standalone server launchers must supply the same JVM argument.

All SDK graphs reference the original shared projects. Forge inputs are
reobfuscated to SRG before assembly; Fabric inputs retain exact intermediary
mappings and separate server/client refmaps. The nine SDK archives, Bootstrap,
carrier and in-memory relocated Fabric input form twelve named input receipts.

From a clean checkout with current Native resources:

```sh
python3 minecraft-mod/tools/java.py universal-build
python3 minecraft-mod/tools/universal_jar.py verify minecraft-mod/java/universal-bootstrap/build/distributions/p2wlan-universal.jar
```

Development uses `native_bundle.py --development` and
`universal-build --development`; verification adds `--development`. Each producer
uses fresh isolated outputs, excluding deleted classes and previously built
archives. The final index binds exact input sizes/hashes, file inventory, source
revision, Native digest, targets and execution methods. Publication creates a
new file or reuses identical bytes.

## HMCL ZIP packaging

`tools/hmcl_pack.py build --universal` creates a CurseForge-format ZIP with a
fixed Minecraft/loader profile and the same `overrides/mods/p2wlan-universal.jar`.
Only Fabric includes its version-matched, SHA-256-pinned API. The guide is present
outside and inside `overrides`; game files, Java, accounts, private profiles,
worlds and EULA files are excluded. Players use Java 17 for 1.16.5, 1.18.2 and
1.20.1, and Java 21 for 1.21.1. Forge 1.16.5 also requires the Nashorn JVM
argument above. Windows resources target x86-64 and local NTFS/user ACLs.

After building a release Universal archive containing the selected target:

```sh
python3 minecraft-mod/tools/java.py universal-hmcl-pack --minecraft 1.20.1 --loader forge --platform windows-x86_64 --mod minecraft-mod/java/universal-bootstrap/build/distributions/p2wlan-universal.jar --output minecraft-mod/java/universal-bootstrap/build/distributions/p2wlan-hmcl-forge-1.20.1-windows-x86_64-0.1.0.zip
python3 minecraft-mod/tools/hmcl_pack.py verify minecraft-mod/java/universal-bootstrap/build/distributions/p2wlan-hmcl-forge-1.20.1-windows-x86_64-0.1.0.zip --universal --minecraft 1.20.1 --loader forge --platform windows-x86_64
```

Fabric additionally needs `--fabric-api /absolute/path/to/fabric-api.jar`,
pointing to the unchanged official full installation JAR for the exact API pin
in the table. Fabric 1.16.5 and 1.18.2 require the full nested-module Mod, not
the small Maven dependency aggregate. Their full-artifact SHA-256 values are
pinned in `tools/minecraft_profiles.py`; the packager rejects a thin aggregate,
a different game version or changed bytes. Change the game, loader, target
and output arguments together to select another exact supported profile.

`P2WLAN-PACK.json` schema 2 binds the Universal SHA-256, source/Native identities,
input receipts, selected profile, Java version and payload inventory. Verification
rejects extra/missing files, archive links, stale source identities, mismatched
instructions and changed dependencies. A development Native resource or absent
target fails release packaging. Import the ZIP through HMCL's modpack installer.
Windows/HMCL gameplay is awaiting real-device validation; `wine-build-info`
records execution of the Windows build-identity command in Wine.

## Clean compilation outputs

Stop Cargo, Gradle and game fixtures before cleaning. Preview the known disposable output paths and their logical file-byte total from the repository root:

```sh
python3 minecraft-mod/tools/clean_builds.py
```

Apply that cleanup with:

```sh
python3 minecraft-mod/tools/clean_builds.py --apply
```

The tool selects Cargo debug/release trees and compilation output categories belonging to tracked Mod Gradle modules. It validates the whole plan before removal, requires ignored paths with no tracked contents, and refuses symbolic links or special files. It accepts no arbitrary cleanup path.

Distribution packages, generated Native resources, fixture/world/evidence directories, reports, test results, `run/`, `.gradle/`, toolchains and the Go cache are retained. `minecraft-mod/dist/` remains the player-distribution directory; compilation intermediates can be rebuilt from the documented entry points. Reported byte totals are logical file sizes, which may differ from the disk space recovered on filesystems with cloned or compressed files.
