# P2WLAN Minecraft

简体中文 | [English](README.en.md)

**把 P2WLAN 房间联机带进 Minecraft。**

P2WLAN Minecraft 是面向 **Minecraft Java Edition 1.21.1 / Fabric** 的联机 Mod。在游戏里登录账号、创建或加入房间，再通过原版多人游戏列表发现和连接房间内已共享的世界，不需要另外启动 P2WLAN 图形客户端或 CLI。

Mod 使用随包提供的原生 Agent 和 P2WLAN 加密网络核心，尝试 Direct 直连；配置了可用 Relay 时保留加密中继后备。连接通道运行在用户态，**不创建系统 TUN/VPN 网卡，也不修改系统路由**。

[安装与联机指南](minecraft-mod/docs/quickstart.md) · [技术参考](minecraft-mod/README.md) · [文档导航](docs/README.md) · [贡献指南](CONTRIBUTING.md)

> **当前为实验性项目。** 已有本机隔离的真实 Minecraft 客户端／专用服务器测试入口，但不能据此宣称双机公网联机或全平台兼容。具体未验证范围见下方限制。

## 主要功能

| 功能 | 当前行为 |
| --- | --- |
| 游戏内入口 | 标题、多人游戏和暂停菜单提供局域网像素图标；也可用 `/p2wlan-client` 打开面板。 |
| 账号与房间 | 填写自己的 Control 地址，登录／注册 P2WLAN 账号，创建或用 8 位房间码与密码加入房间。 |
| 房间连接 | 点击房间列表中的房间连接虚拟局域网；断开房间保留账号会话和成员关系。 |
| 服务器发现 | 多人游戏界面展示房间内已发布的 P2WLAN 服务器，使用原版选择、双击和加入按钮连接；不覆盖已保存服务器。 |
| 世界共享 | 提供单人世界的显式共享／停止共享入口，以及专用服务器的连接、发布和撤销命令；不会自动发布世界。 |
| 网络状态 | 查看原生核心测得的 RTT、Direct／Relay、虚拟 IP、抖动、MTU 和累计流量；可开启默认关闭的 HUD 和延迟统计。 |
| 专用服务器账号工具 | 独立的 `p2wlan-account` 分发包可在服务器停止时初始化 P2WLAN 账号与房间，不需要客户端界面。 |

**P2WLAN 账号不是 Microsoft／Minecraft 账号。** Mod 保留原版游戏登录、认证和资源包同意流程，不代替游戏账号，也不自动接受 EULA。

## 版本与平台边界

| 项目 | 当前构建配置 |
| --- | --- |
| Minecraft | Java Edition **1.21.1** |
| Mod Loader | Fabric Loader **0.16.14**；元数据要求 `>=0.16.14` |
| Fabric API | **0.116.7+1.21.1** |
| Java | **Java 21**，使用与原生目标匹配的 64 位 JVM |
| Mod 版本 | **0.1.0**；不沿用上游 VPN 客户端的版本号或发布包 |

原生资源识别 `macos-aarch64`、`macos-x86_64`、`linux-aarch64`、`linux-x86_64` 和 `windows-x86_64`。**目标能被识别或编译，不等于对应设备已经验收。** 默认源码构建只打包当前主机的原生程序，不能把同一个单平台 JAR 当成通用跨平台包。

当前真实本机游戏测试范围为 macOS / Apple Silicon、Fabric / Minecraft 1.21.1、隔离的离线认证测试服。Windows／HMCL、Linux 图形运行、正版在线认证与跨机器游戏验收仍未完成。其他 Minecraft 版本、Forge、NeoForge、Bedrock 和移动端不在当前适配范围内。

## 快速开始

1. **准备游戏实例。** 安装上述 Minecraft、Fabric Loader 和 Fabric API，将包含本机原生目标的 Mod JAR 放入该实例的 `mods/`。HMCL ZIP 应从整合包安装入口导入，不能直接放进 `mods/`。
2. **登录 P2WLAN。** 点击菜单里的局域网图标，填写管理员提供的 Control 根地址，例如 `https://control.example.com`，再登录或注册。项目不预置公共服务器；参与者需要使用同一个 Control。
3. **加入并连接房间。** 房主创建房间，朋友使用 8 位房间码与密码加入；每一端还需要点击对应房间建立连接。加入成员关系不等于连接网络。
4. **由房主发布世界。** 单人世界内可在面板选择「共享本地世界」；专用服务器使用 `/p2wlan connect <房间码>`，待运行时就绪后执行 `/p2wlan host`。单人共享已实现入口，但完整游戏验收尚未完成。
5. **在多人游戏中加入。** 朋友打开原版「多人游戏」，选择发现的 P2WLAN 服务器进入。退出世界或关闭面板不会自动断开房间；不再使用时点击「断开房间」。

房主／专用服务器和参与玩家都需要安装匹配的 Mod 与依赖。完整的安装、专服账号初始化、显示设置和排障步骤见[安装与联机指南](minecraft-mod/docs/quickstart.md)。

## 工作方式

```text
Minecraft / Fabric
       ↕ 受保护的本地 TCP 通道
Java 适配层 ↔ 随包原生 Agent
                    ↕
           P2WLAN 加密 Direct / Relay
                    ↕
            另一端 Agent 与游戏世界
```

- **Control** 管理账号、房间、设备授权与连接信令；游戏数据不走普通 Control HTTP 请求。
- **原生 Agent** 复用本仓库的 P2WLAN 网络核心，负责加密会话、直连／中继与用户态 TCP 转发。
- **Fabric 适配层** 管理游戏入口、世界共享和加入流程，只读取原生核心的带身份围栏状态，不另建路径状态机。

这里的虚拟 IP 属于 Mod 房间通道，不是可供系统任意应用使用的 VPN 地址。它不会自动让 SSH、NAS 或其他原版服务器通过系统网络互通。

## 从源码构建

需要 Python **3.11+**、Rust 工具链及其原生编译依赖、JDK 21。Gradle Wrapper 已包含在仓库中；首次构建需要获取固定的 Cargo、Gradle、Fabric 和 Minecraft 依赖。

在**干净且已提交的完整仓库 checkout** 根目录运行：

```sh
python3 minecraft-mod/tools/native_bundle.py
python3 minecraft-mod/tools/java.py fabric-build
```

当前构建产物为 `minecraft-mod/java/adapters/fabric-1.21.1/build/libs/p2wlan-fabric-1.21.1-0.1.0.jar`。构建会检查嵌入的原生资源、源码身份和摘要；缺失或不匹配会失败，不会运行时下载另一个 Agent。

开发包、专服账号分发包、Windows 原生资源与 HMCL 打包方式见[技术参考](minecraft-mod/README.md)。不要把开发原生包或上游 P2WLAN 的 DMG、EXE、APK 当作正式 Mod 安装包。

## 限制与安全

- 当前单个游戏实例只维护一个房间运行时；连接房间、发布世界和玩家成功入服是三个不同状态。
- Direct 成功与否受 NAT、防火墙和网络条件影响；Relay 后备依赖实际服务配置，不能保证所有网络都能直连。
- 跨机器／公网联机、强制 Relay、长时间游戏、切网恢复、单人世界共享／暂停语义和完整其他 Mod 兼容性验收未完成。
- 专用服务器原有监听端口不会被 Mod 自动关闭。管理员仍需管理其公网暴露；Mod 通道在游戏端表现为 loopback 来源，依赖真实玩家 IP 的封禁或插件需要另行评估。
- 优先使用可信 HTTPS Control。HTTP 虽可使用并有风险提示，但账号、房间和信令可能被监听或篡改。
- `config/p2wlan-private/` 含有会话与设备身份，不要共享或提交。其他恶意 Mod、同一系统用户的恶意代码和管理员不在本地隔离保护范围内。
- 项目尚未完成独立安全审计。构建校验、包摘要和自动测试不能替代安全审计或真实设备验收。

详见[隐私说明](PRIVACY.md)与[安全报告](SECURITY.md)。

## 仓库导航

| 目录 | 用途 |
| --- | --- |
| `minecraft-mod/java/` | Fabric 适配层、通用 Java 协议、账号工具和原生资源封装 |
| `minecraft-mod/native/` | 独立 Rust workspace：Agent、TCP 桥、网关与上游运行时适配 |
| `minecraft-mod/tools/`、`minecraft-mod/tests/` | Mod 构建、包校验和测试入口 |
| `client/` | 保留并复用的上游 P2WLAN 网络核心与客户端源码 |
| `server/`、`deploy/` | 保留的 Control／Relay 服务及部署配置 |
| `docs/` | 文档导航与保留的底层网络、服务端和工程规则 |

Mod 的使用文档位于 `minecraft-mod/docs/`，底层 VPN 客户端文档不等同于 Mod 安装说明。根 Cargo workspace、Flutter 客户端和既有服务端 workflow 也不代表 Mod 的构建或验收结果。

## 上游与许可

本项目基于 [P2WLAN](https://github.com/yhan-sun/p2wlan) 的网络实现，Minecraft 集成位于独立的 `minecraft-mod/` 项目中。感谢上游贡献者；保留原有版权和许可声明。

代码使用 [MIT License](LICENSE)。Minecraft、Fabric、启动器及其他依赖的许可与使用条款由其各自项目规定。
