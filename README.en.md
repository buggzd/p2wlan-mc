# P2WLAN Minecraft

[简体中文](README.md) | English

**P2WLAN room networking, inside Minecraft.**

P2WLAN Minecraft is a multiplayer Mod for **Minecraft Java Edition 1.21.1 / Fabric**. Sign in, create or join a room, and discover published room worlds in the ordinary Multiplayer browser without separately starting the upstream P2WLAN GUI or CLI.

A bundled native Agent reuses the P2WLAN encrypted networking core. It attempts Direct connections and retains encrypted Relay fallback when a usable Relay is configured. The Mod's channel runs in user space: **no system TUN/VPN interface and no system route changes**.

[Installation and multiplayer guide (Chinese)](minecraft-mod/docs/quickstart.md) · [Technical reference](minecraft-mod/README.md) · [Documentation index](docs/README.md) · [Contributing](CONTRIBUTING.md)

> **Experimental project.** An isolated local fixture exercises an actual Minecraft client and dedicated server. This is not proof of cross-machine public-network play or universal platform compatibility; see the limits below.

## Features

| Feature | Current behavior |
| --- | --- |
| In-game entry | A pixel LAN icon in the title, Multiplayer and pause menus; `/p2wlan-client` opens the same panel. |
| Accounts and rooms | Enter your own Control origin, sign in/register a P2WLAN account, and create or join rooms using an eight-digit code and password. |
| Room connection | Select a room to connect its virtual network; disconnecting retains the saved account session and membership. |
| Server discovery | Published P2WLAN servers appear beside ordinary LAN rows. Vanilla selection, double-click and Join Server controls start the protected connection; saved servers remain unchanged. |
| World publication | Explicit share/unshare controls for integrated worlds, and connect/host/unhost commands for dedicated servers. Worlds are never published automatically. |
| Network visibility | Native-measured RTT, Direct/Relay path, virtual IP, jitter, MTU and cumulative traffic, with an optional default-off HUD and latency statistics. |
| Dedicated-server account tool | A separate `p2wlan-account` distribution initializes the P2WLAN account and membership while the server is stopped, without a client GUI. |

**A P2WLAN account is not a Microsoft/Minecraft account.** Vanilla login, authentication and resource-pack consent remain in place. The Mod neither replaces game accounts nor accepts the EULA automatically.

## Versions and platform boundaries

| Component | Current build configuration |
| --- | --- |
| Minecraft | Java Edition **1.21.1** |
| Mod loader | Fabric Loader **0.16.14**; metadata requires `>=0.16.14` |
| Fabric API | **0.116.7+1.21.1** |
| Java | **Java 21**, with a 64-bit JVM matching the native target |
| Mod version | **0.1.0**, independent of upstream VPN client versions and packages |

Recognized native targets are `macos-aarch64`, `macos-x86_64`, `linux-aarch64`, `linux-x86_64` and `windows-x86_64`. **Target recognition or compilation is not device acceptance.** A default source build packages only the current host executable; a single-platform JAR is not a universal package.

Existing real local gameplay verification is limited to macOS / Apple Silicon, Fabric / Minecraft 1.21.1 and an isolated offline-authentication test server. Windows/HMCL, Linux graphical execution, online Minecraft authentication and cross-machine gameplay acceptance remain incomplete. Other Minecraft versions, Forge, NeoForge, Bedrock and mobile platforms are outside the current adapter scope.

## Quick start

1. **Prepare a game instance.** Install the Minecraft, Fabric Loader and Fabric API versions above. Place a Mod JAR containing your exact native target in that instance's `mods/`. Import HMCL ZIPs through its modpack installer, not the `mods/` directory.
2. **Sign in to P2WLAN.** Open the menu's LAN icon, enter an administrator-provided root Control origin such as `https://control.example.com`, and sign in/register. No public service is preconfigured; participants must use the same Control.
3. **Join and connect a room.** The owner creates a room; friends join with its eight-digit code and password. Each participant must then select the room to connect. Membership alone does not start the network.
4. **Publish the host's world.** In an integrated world, select **共享本地世界** (Share local world). On a dedicated server, use `/p2wlan connect <room-code>` and, once the runtime is ready, `/p2wlan host`. Integrated publication has an implemented entry point but full gameplay acceptance remains incomplete.
5. **Join through Multiplayer.** Select a discovered P2WLAN server in Vanilla's Multiplayer browser. Leaving the game world or closing the panel does not disconnect the room; explicitly select **断开房间** (Disconnect room) when finished.

Both the host/dedicated server and joining players need a matching Mod and dependencies. See the [installation and multiplayer guide (Chinese)](minecraft-mod/docs/quickstart.md) for installation, server-account initialization, display settings and troubleshooting.

## How it works

```text
Minecraft / Fabric
       ↕ protected local TCP channel
Java adapter ↔ bundled native Agent
                       ↕
              P2WLAN encrypted Direct / Relay
                       ↕
               remote Agent and game world
```

- **Control** owns accounts, rooms, device authorization and signaling. Game data is not carried by ordinary Control HTTP requests.
- **Native Agent** reuses this checkout's P2WLAN core for encrypted sessions, Direct/Relay and user-space TCP forwarding.
- **Fabric adapter** owns game UI, world publication and join intents. It consumes fenced native observations rather than maintaining a second path state machine.

Virtual IPs belong to the Mod's room channel, not a system-wide VPN. They do not automatically connect SSH, NAS services or unrelated Vanilla servers through the operating system network.

## Build from source

Prerequisites: Python **3.11+**, Rust and its native compilation dependencies, and JDK 21. The Gradle Wrapper is checked in. Initial builds fetch the pinned Cargo, Gradle, Fabric and Minecraft dependencies.

From the root of a **clean, committed, complete repository checkout**:

```sh
python3 minecraft-mod/tools/native_bundle.py
python3 minecraft-mod/tools/java.py fabric-build
```

The current output is `minecraft-mod/java/adapters/fabric-1.21.1/build/libs/p2wlan-fabric-1.21.1-0.1.0.jar`. Packaging checks embedded native resources, source identity and hashes; missing or mismatched resources fail instead of downloading another Agent at runtime.

Development packages, the server-account distribution, Windows native resources and HMCL packaging are described in the [technical reference](minecraft-mod/README.md). Development-native archives and upstream P2WLAN DMG/EXE/APK installers are not release Mod packages.

## Limits and security

- One game instance maintains one room runtime. Connecting a room, publishing a world and successfully joining a game are distinct states.
- Direct connectivity depends on NAT, firewalls and network conditions. Relay fallback requires a usable configured service; universal Direct connectivity is not promised.
- Cross-machine/public-network play, forced Relay, sustained gameplay, network-change recovery, integrated-world hosting/pause semantics and comprehensive compatibility with other Mods remain unverified.
- Dedicated servers retain their ordinary listeners. Operators remain responsible for their public exposure. The Mod channel presents a loopback source to Minecraft, so real-IP bans/plugins require separate consideration.
- Prefer trusted HTTPS Control. HTTP is permitted with a warning but exposes account, room and signaling traffic to interception/tampering.
- `config/p2wlan-private/` contains sessions and device identities; do not share or commit it. Local isolation does not defend against malicious Mods, same-OS-user code or administrators.
- No independent security audit has been completed. Build checks, checksums and automated tests do not replace security review or device acceptance.

See [privacy](PRIVACY.md) and [security reporting](SECURITY.md).

## Repository map

| Directory | Purpose |
| --- | --- |
| `minecraft-mod/java/` | Fabric adapter, common Java protocol, account tool and native resource packaging |
| `minecraft-mod/native/` | Independent Rust workspace: Agent, TCP bridge, gateway and upstream runtime adaptation |
| `minecraft-mod/tools/`, `minecraft-mod/tests/` | Mod build, archive verification and test entry points |
| `client/` | Retained upstream P2WLAN networking core and client sources |
| `server/`, `deploy/` | Retained Control/Relay services and deployment configuration |
| `docs/` | Documentation navigation and retained networking, server and engineering references |

Mod usage documentation belongs in `minecraft-mod/docs/`. Upstream VPN client documentation is not a Mod installation guide. The root Cargo workspace, Flutter client and existing server workflows do not establish Mod build or acceptance results.

## Upstream and license

This project builds on [P2WLAN](https://github.com/yhan-sun/p2wlan); Minecraft integration lives in the independent `minecraft-mod/` project. Thanks to the upstream contributors. Existing copyright and license notices are retained.

Code is licensed under the [MIT License](LICENSE). Minecraft, Fabric, launchers and other dependencies retain their own licenses and terms.
