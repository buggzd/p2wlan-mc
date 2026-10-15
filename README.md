<div align="center">

<img src="assets/p2wlan_icon.svg" width="112" alt="P2WLAN">

# P2WLAN Minecraft

**在游戏里开房间，和朋友像在同一个局域网里一样联机。**

![Minecraft](https://img.shields.io/badge/Minecraft-1.16.5%20%7C%201.18.2%20%7C%201.20.1%20%7C%201.21.1-62B47A)
![Loaders](https://img.shields.io/badge/Loaders-Fabric%20%7C%20Forge%20%7C%20NeoForge-DBD0B4)
![Java](https://img.shields.io/badge/Java-17%20%7C%2021-E76F00)
![Status](https://img.shields.io/badge/status-experimental-orange)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

简体中文 · [English](README.en.md)

[获取安装包](#获取安装包) · [安装与联机指南](minecraft-mod/docs/quickstart.md) · [技术参考](minecraft-mod/README.md) · [贡献指南](CONTRIBUTING.md)

</div>

---

P2WLAN Minecraft 是一个 **Minecraft Java 联机 Mod**。在游戏里登录、创建房间，朋友凭房间码加入后，房主共享的世界会直接出现在原版「多人游戏」列表中，双击即可进入。

本仓库以 Minecraft Mod 为产品入口。Mod 内置原生 Agent，复用 [P2WLAN](https://github.com/yhan-sun/p2wlan) 的加密网络核心：玩家之间优先 P2P 直连，打洞失败时可经加密中继转发。整个通道运行在用户态，不需要另开 VPN 客户端，也不改动系统网卡和路由。

## 特性

- **游戏内一站式** — 标题、多人游戏和暂停菜单都有局域网像素图标，也可以用 `/p2wlan-client` 打开面板。
- **房间制联机** — 创建房间，朋友使用 8 位房间码和密码加入。
- **原版选服体验** — 房间内共享的世界出现在多人游戏列表里，选中、双击或「加入服务器」即可进入；已保存的服务器保持不变。
- **单人世界与专用服务器** — 单人世界一键共享／停止共享；专用服务器在 Minecraft 控制台完成配置，重启自动恢复房间与共享设置。
- **直连优先，中继兜底** — 原生核心负责加密会话、NAT 打洞和 Direct／Relay 切换。
- **网络状态可视** — RTT、直连／中继、虚拟 IP、抖动、MTU 和累计流量一目了然，可选开启游戏内 HUD 和延迟统计。
- **原版加入流程** — 保留玩家登录、配置和资源包确认；共享世界支持离线游戏名，专用服遵循 `online-mode`。P2WLAN 账号与 Minecraft 账号相互独立。
- **专服控制台管理** — 启服后初始化、登录、创建／加入房间和共享世界；手动断开或停止共享会保存为下一次启动的设置。

## 获取安装包

在本仓库 **Actions → Minecraft Mod Build → 对应源码提交的成功运行 → Artifacts** 中，下载 `p2wlan-minecraft-mod-<源码 SHA>`。工作流配置见 [minecraft-mod.yml](.github/workflows/minecraft-mod.yml)，构建成功后提供下列文件，保留 14 天：

| 文件 | 用途 |
| --- | --- |
| `p2wlan-universal.jar` | 客户端、单人世界房主和专用服务器共用的 Mod，放入实例的 `mods/` |
| `p2wlan-hmcl-<Minecraft>-<加载器>-windows-x86_64.zip` | 十个固定配置各一份 Windows x64 HMCL 包 |
| `BUILD-METADATA.json`、`SHA256SUMS` | 源码提交、包内原生目标、产物清单和 SHA-256 |

先解压下载的 Actions 产物归档，再取其中的 JAR 或 HMCL ZIP。HMCL 的整合包安装入口导入内层配置包；Fabric 配置包已包含匹配的完整 Fabric API。Minecraft、加载器、Java 和游戏账号仍需按启动器流程准备。

自动构建的 JAR 包含 **Windows x64、Linux x64、macOS Apple Silicon** 三个原生目标。Intel Mac 和 Linux ARM64 可通过源码工具构建对应资源。使用与原生目标架构一致的 64 位 Java；Windows 的游戏实例和私有目录使用本地 NTFS。

安装包由 Actions 提供，工作流不自动创建 Release。完整格式与校验方法见[构建与打包参考](minecraft-mod/docs/reference/building.md#github-actions-build)。

## 游戏与加载器版本

玩家、单人世界房主和专用服务器使用同一个 **`p2wlan-universal.jar`**，覆盖以下十个精确配置。房主与朋友使用相同的 Minecraft 版本；手动安装 Fabric Mod 时，另装表中对应的完整 Fabric API 安装 JAR。

| Minecraft Java Edition | Fabric Loader / API | Forge | NeoForge | 游戏 Java |
| --- | --- | --- | --- | --- |
| 1.16.5 | 0.16.14 / 0.42.0+1.16 | 36.2.42 | — | 17 |
| 1.18.2 | 0.16.14 / 0.77.0+1.18.2 | 40.3.12 | — | 17 |
| 1.20.1 | 0.16.14 / 0.92.12+1.20.1 | 47.3.2 | 47.1.106 | 17 |
| 1.21.1 | 0.16.14 / 0.116.7+1.21.1 | 52.1.16 | 21.1.256 | 21 |

使用 64 位 Java。Forge 1.16.5 的 Java 17 启动参数需要 `-Dnashorn.args=--anonymous-classes=false`。配置来源为 [`minecraft_profiles.py`](minecraft-mod/tools/minecraft_profiles.py)。

表中是四个游戏版本、十个加载器配置的完整版本号，其他游戏补丁版本或加载器组合不在当前矩阵中。所有参与者需共用可信的 P2WLAN Control 服务，可按[自托管指南](docs/guides/self-hosting.md)部署；需要中继兜底时，还须配置 Relay。

## 快速开始

```text
登录 P2WLAN  →  创建／加入房间  →  连接房间  →  房主共享世界  →  朋友在多人游戏中加入
```

1. **安装 Mod** — [获取安装包](#获取安装包)，按上表创建游戏实例，将 `p2wlan-universal.jar` 放入 `mods/`；Fabric 再加入对应完整 Fabric API。使用 HMCL 时，导入匹配配置的 HMCL ZIP。
2. **登录** — 点击菜单里的局域网图标，填写 Control 根地址（如 `https://control.example.com`），登录或注册 P2WLAN 账号。
3. **进房间** — 房主创建房间；朋友输入 8 位房间码和密码加入。
4. **连接** — 每位玩家都在房间列表中点击「连接 · 房间名」，接入房间网络。
5. **共享世界** — 房主进入单人世界，在暂停菜单的 P2WLAN 面板中点击「共享本地世界」。
6. **加入游戏** — 朋友打开「多人游戏」，选择出现的 P2WLAN 服务器进入。

玩完后在面板中点击「断开房间」。退出世界不会自动断开；断开房间后，账号会话和房间成员关系依然保留。

### 专用服务器

安装相同的 Universal JAR，正常启服后，在服务器本机控制台完成配置。下面的密码是占位符；终端或托管面板的历史可能保留完整命令，输入前确认记录的访问范围：

| 命令 | 操作 |
| --- | --- |
| `p2wlan init https://control.example.com` | 保存 Control 根地址 |
| `p2wlan login "user@example.com" "<账号密码>"` | 登录；新账号改用 `register` |
| `p2wlan create "服务器房间" "<房间密码>"` | 创建并连接；加入他人房间改用 `join <8位房间码> "<房间密码>"` |
| `p2wlan runtime` | 等待运行时显示 `ready` |
| `p2wlan host` | 发布当前世界 |
| `p2wlan world` | 查看发布状态 |

房间和共享选择保存在 `config/p2wlan-server.json`，服务器重启后自动连接同一房间并按设置共享。`p2wlan unhost` 保存为不共享；`p2wlan disconnect` 清除房间选择，保留账号和成员关系。恢复失败时查看 `p2wlan status`，修正后用 `p2wlan restore` 重试。

初始化和带密码的命令仅接受真正的服务器控制台；其他非敏感命令可由权限等级 4 的玩家加 `/` 执行。专用服务器遵循自己的 `online-mode` 设置。独立账号工具保留为[可选离线维护入口](minecraft-mod/docs/reference/building.md#dedicated-server-account-console)。

完整步骤、HMCL 导入、显示设置和常见问题见 **[安装与联机指南](minecraft-mod/docs/quickstart.md)**。

## 工作原理

```mermaid
flowchart LR
    subgraph host [房主]
        MA[Minecraft] <-->|本地 TCP| FA[Mod 游戏适配层] <--> NA[原生 Agent]
    end
    subgraph guest [朋友]
        NB[原生 Agent] <--> FB[Mod 游戏适配层] <-->|本地 TCP| MB[Minecraft]
    end
    NA <==>|加密 Direct／Relay| NB
    C[(Control<br/>账号 · 房间 · 信令)]
    NA -.- C
    NB -.- C
```

| 组件 | 职责 |
| --- | --- |
| **Mod 游戏适配层** | 游戏菜单、房间面板、世界发布和多人游戏列表集成 |
| **原生 Agent** | 复用 P2WLAN 网络核心：加密会话、直连／中继选路、用户态 TCP 转发 |
| **Control** | 账号、房间、设备授权与连接信令；游戏数据不经过 Control |

房间内的虚拟 IP 只服务于 Mod 自身的游戏通道，不会作为系统网卡暴露给其他程序。

## 源码构建

[Minecraft Mod Build](.github/workflows/minecraft-mod.yml)对 `main`／`codex/**` 的推送、PR 和手动运行执行检查与打包。它分别构建三个平台的原生程序，再组装同一份 Universal JAR。

本地完整构建使用 **Python 3.11+、Rust、本机 C/C++ 编译工具和 JDK 8／17／21／25**。JDK 17／21 编译共享代码和游戏适配；Forge 构建还使用完整 JDK 8（含 `lib/tools.jar`）及 JDK 25。Gradle 由仓库包装器启动，首次构建需要联网获取固定依赖。

先按[构建前提](minecraft-mod/docs/reference/building.md#build-prerequisites-and-primary-artifact)配置本机 JDK 路径；`JAVA_HOME` 选择 21，`P2WLAN_LEGACY_JAVA_HOME` 选择 17，Forge 的辅助工具链选择器见[适配参考](minecraft-mod/docs/reference/adapters.md#forge-adapter-and-build-inputs)。在干净、已提交的完整仓库根目录执行：

```sh
python3 minecraft-mod/tools/native_bundle.py
python3 minecraft-mod/tools/java.py universal-build --output minecraft-mod/dist/p2wlan-universal.jar
python3 minecraft-mod/tools/universal_jar.py verify minecraft-mod/dist/p2wlan-universal.jar
```

以上命令生成 `minecraft-mod/dist/p2wlan-universal.jar`，包含当前资源目录中已构建的原生目标；本地默认构建本机目标。各加载器的独立 JAR 是 Universal 的组装输入。

HMCL ZIP、`BUILD-METADATA.json` 和 `SHA256SUMS` 由 `minecraft-mod/tools/distribution.py` 另行生成，所选平台必须已包含在 JAR 中。开发包、多平台资源合并、专服账号工具和[编译文件清理](minecraft-mod/docs/reference/building.md#clean-compilation-outputs)见[构建与打包参考](minecraft-mod/docs/reference/building.md)。

## 项目状态

项目处于**实验阶段**，当前版本 `0.1.0`。

| | 范围 |
| --- | --- |
| 自动构建检查 | 独立 Rust workspace、共享 Java、Python 构建与归档契约，以及最终 Universal／HMCL 产物校验；Actions 构建不启动 Minecraft 游戏。 |
| 游戏验证入口 | 已安装 Universal 夹具可选择上表十个精确配置，检查专用服务器与共享单人世界、原版登录、有序游戏数据往返和退出清理。执行条件和已记录范围见[游戏验证参考](minecraft-mod/docs/reference/testing.md#installed-universal-gameplay-verification)。 |
| ⏳ 待环境验证 | 专服控制台配置与重启恢复的真实游戏验收、跨机器／公网联机、Windows／HMCL 实机、Linux 图形环境、正版在线认证、强制中继、长时间游戏与切网恢复、与其他 Mod 的兼容性、独立安全审计。 |
| ➖ 范围之外 | 上表以外的 Minecraft／加载器配置、基岩版、移动端。 |

直连成功率取决于双方的 NAT 和防火墙环境；未配置 Relay 时，无法直连的玩家之间无法联机。

## 安全与隐私

- 尽量使用 **HTTPS** 的 Control 地址。HTTP 可用，但账号、房间和信令会以明文传输，界面会持续提示。
- `config/p2wlan-private/` 保存登录会话和设备身份，请勿分享或提交到版本库。
- 专用服务器原有的监听端口保持不变，公网暴露由管理员自行管理。经 Mod 进入的玩家在服务器看来来自本机回环地址，依赖真实 IP 的封禁或插件需要另行调整。

详见[隐私说明](PRIVACY.md)；安全问题请按[安全报告流程](SECURITY.md)私下提交。

## 仓库结构

```text
minecraft-mod/        Mod 本体
├── java/             多加载器适配、共享游戏实现、通用协议、账号工具与原生资源
├── native/           Rust workspace：Agent、TCP 桥、网关、运行时适配
├── tools/            构建、打包与校验脚本
├── tests/            Mod 测试
├── dist/             JAR、HMCL ZIP 与摘要清单（构建生成，不提交）
└── docs/             玩家指南与按主题划分的技术参考
client/               上游 P2WLAN 网络核心与客户端（Mod 复用其网络实现）
server/  deploy/      上游 Control／Relay 服务与部署配置
docs/                 上游参考文档导航与共同工程规范
```

## 致谢与许可

网络实现来自 [P2WLAN](https://github.com/yhan-sun/p2wlan)，感谢上游全体贡献者。

本项目以 [MIT License](LICENSE) 发布。Minecraft、各加载器、启动器及其他依赖遵循各自的许可与使用条款。
