# Minecraft Mod 安装与联机指南

[仓库介绍](../../README.md) · [技术参考](../README.md) · [文档导航](../../docs/README.md)

本页按玩家、房主和专用服务器三种角色，介绍从安装到进入游戏的完整操作。项目处于实验阶段，固定配置与验证范围见本页和[技术参考](reference/testing.md#installed-universal-gameplay-verification)。

## 安装前准备

客户端、专用服务器和单人世界使用同一份 **`p2wlan-universal.jar`**。选择下表中的 Minecraft／加载器配置；房主与朋友使用相同的 Minecraft 版本。

| Minecraft | 加载器 | Java | 额外 Mod |
| --- | --- | --- | --- |
| 1.16.5 | Fabric Loader 0.16.14 | 17 | Fabric API 0.42.0+1.16（完整安装 JAR） |
| 1.16.5 | Forge 36.2.42 | 17 | 无 |
| 1.18.2 | Fabric Loader 0.16.14 | 17 | Fabric API 0.77.0+1.18.2（完整安装 JAR） |
| 1.18.2 | Forge 40.3.12 | 17 | 无 |
| 1.20.1 | Fabric Loader 0.16.14 | 17 | Fabric API 0.92.12+1.20.1 |
| 1.20.1 | Forge 47.3.2 | 17 | 无 |
| 1.20.1 | NeoForge 47.1.106 | 17 | 无 |
| 1.21.1 | Fabric Loader 0.16.14 | 21 | Fabric API 0.116.7+1.21.1 |
| 1.21.1 | Forge 52.1.16 | 21 | 无 |
| 1.21.1 | NeoForge 21.1.256 | 21 | 无 |

上表列出十个精确配置；1.16.5 和 1.18.2 的 NeoForge 配置不在矩阵中。请使用表中的完整版本号。使用 64 位 Java，架构与包内原生 Agent 匹配。还需要管理员提供的可信 Control 根地址，以及参与者在同一 Control 下的房间。自托管参见[底层服务部署指南](../../docs/guides/self-hosting.md)。

Mod 自带联机所需的原生程序。Minecraft 本体、Java、游戏账号和 Control 服务由使用者准备。

### 手动安装 JAR

1. 创建独立游戏实例，安装表中的 Minecraft 和加载器；专用服务器选择相同配置。
2. 将 `p2wlan-universal.jar` 放入 `mods/`。更新时移走旧 P2WLAN JAR，保留实例的 `config/`。
3. Fabric 实例另放入表中对应的 Fabric API；Forge／NeoForge 实例直接使用 Universal JAR。
4. Forge 1.16.5 使用 Java 17 时，在启动器的 Java／JVM 额外参数中加入 `-Dnashorn.args=--anonymous-classes=false`；专用服务器将该参数放在 Java 命令的 `-jar` 或主类之前。
5. 确认包内有本机的原生目标，再正常启动。Agent 从包内提取并启动。

Fabric 1.16.5 和 1.18.2 必须使用包含嵌套模块的官方完整 Fabric API 安装 JAR。对应 Maven 依赖聚合文件只供构建解析，不能代替游戏实例中的完整 Mod；打包器会检查固定摘要。

原生目标为 `macos-aarch64`、`macos-x86_64`、`linux-aarch64`、`linux-x86_64`、`windows-x86_64`。源码构建默认生成主机目标，其他目标须单独构建。Windows 游戏实例、私有配置和提取位置使用本地 NTFS，Mod 会核验用户 ACL。

专用服和共享单人世界使用同一安装 JAR。真实游戏夹具分别检查原版登录、20 轮有序游戏数据往返、至少 18 秒持续游戏和退出清理；兼容矩阵与执行入口见[技术参考](reference/testing.md#installed-universal-gameplay-verification)。Windows/HMCL 与跨机器联机待验证。

### 导入 HMCL ZIP

在 HMCL 的**整合包安装入口**选择对应目标的 ZIP。不要把 ZIP 放进 `mods/`，也不要把它当作 EXE 安装器。

选择与加载器、原生目标对应的 ZIP。包内固定 Minecraft／加载器版本，并附带同一份 Universal JAR、中文安装说明和 `P2WLAN-PACK.json`；Fabric 包另含对应版本的 Fabric API。游戏与 Loader 由启动器获取，Java 和游戏账号由玩家自行准备。打包校验核对 ZIP 内容、文件摘要和原生构建身份。

## 玩家：登录并连接房间

### 1. 打开 P2WLAN

标题、多人游戏和暂停菜单的原有按钮旁提供方形局域网像素图标。若其他 Mod 占用了菜单布局，可在游戏内使用：

```text
/p2wlan-client
/p2wlan-client status
```

`status` 显示本机助手进程和 IPC 状态。helper 启动失败时，账号按钮不会启用；记录稳定错误码，不要反复注册账号。

### 2. 登录或注册

在「账号设置」填写：

- **Control 地址**：使用根 origin，例如 `https://control.example.com`，不要附加 `/api/v1`、查询参数、账号密码或片段。
- **账号邮箱**与 **P2WLAN 密码**：属于 P2WLAN，不是 Microsoft／Minecraft 凭据。

优先使用 HTTPS。HTTP 以明文传输账号、房间和信令，界面持续显示风险提示。

成功后进入房间列表。本机会保存登录会话和 Control 地址，不保存输入的账号密码。会话有效且未主动退出时，再次打开可直接进入房间列表；关闭界面、退出世界或断开房间后，账号会话仍保留。账号与房间成员操作需要先断开当前房间运行时。

### 3. 创建／加入，再连接

- 房主点击「创建房间」，填写房间名称和密码。
- 朋友点击「加入房间」，填写 **8 位数字房间码**和密码。
- 房间密码要求 **8–72 UTF-8 字节**，不是 8–72 个字符；不会自动去掉空格或规范化。
- 成功后回到房间列表，**每一端都要点击「连接 · 房间名」**。

```text
创建／加入成员关系 → 点击房间建立网络 → 房主共享世界 → 多人游戏选择服务器
```

连接房间不自动共享世界或加入游戏。当前一个实例只维护一个房间运行时；需要切换时先断开当前房间。

## 房主：共享单人世界

使用上表中的精确 Minecraft／加载器配置。共享期间保持原版世界、玩家与游戏模式；停止共享后恢复单人暂停行为。

1. 登录并连接房间，等待房间运行时就绪。
2. 进入想共享的单人世界，打开暂停菜单中的 P2WLAN 面板。
3. 点击「共享本地世界」。Mod 显式发布当前世界，不调用原版「对局域网开放」广播，也不自动改变作弊或默认游戏模式。
4. 朋友连接同一房间后，在「多人游戏」中选择发现的 P2WLAN 服务器。
5. 不再共享时点击「停止共享」；世界关闭或替换也会撤销本次发布。

共享按钮无需开启作弊。P2WLAN 共享单人世界支持 HMCL 离线账号；通过共享通道加入的朋友使用按游戏名生成的原版离线 UUID，房主与朋友使用不同的 Minecraft 游戏名。朋友进入世界后，可通过移动、聊天等游戏交互确认联机。

## 专用服务器：控制台配置与重启恢复

### 1. 启服后初始化和登录

将 Universal JAR 放入服务端的 `mods/`，按照 Fabric／Forge／NeoForge 的流程启动 Minecraft 服务端 JAR，游戏条款由管理员自行确认。启动完成后，在**服务端 JAR 控制台**输入下列命令（也就是平时输入 `list`、`stop` 的同一个输入位置）。`p2wlan` 是 Mod 注册的 Minecraft 服务端命令：

```text
p2wlan init https://control.example.com
p2wlan login "user@example.com" "<账号密码>"
```

`https://control.example.com` 是 P2WLAN Control 服务地址的示例，替换为实际地址；账号和密码同样替换为自己的值。后续创建／加入房间和共享也都在这个服务端控制台完成。

没有 P2WLAN 账号时，用 `p2wlan register "user@example.com" "<账号密码>"` 代替登录。`init` 保存 Control 根地址；优先使用 HTTPS，选择 HTTP 时控制台会提示账号和房间密码通过明文连接发送。P2WLAN 账号独立于 Microsoft／Minecraft 账号。

`init`、`login`、`register`、`create`、`join` 仅接受真正的服务器控制台来源，拒绝玩家、RCON 和命令方块。参数可以使用双引号；其中的双引号和反斜线分别写成 `\"` 和 `\\`，密码中的空格保持原样。Mod 的反馈不回显这些输入，**终端、托管面板或控制台历史仍可能保留完整命令**，输入密码前确认这些记录的访问范围。输入的账号密码和房间密码不写入恢复配置。

### 2. 创建／加入房间并共享

创建一个房间：

```text
p2wlan create "服务器房间" "<房间密码>"
```

加入他人的房间时，改用 `p2wlan join 12345678 "<房间密码>"`，将 `12345678` 替换为实际的 8 位房间码。房间密码要求 **8–72 UTF-8 字节**。这两个控制台命令成功后都会保存房间并开始连接。

已经是房间成员时，可用 `p2wlan rooms` 查看房间，再用 `p2wlan connect <房间码或房间ID>` 连接。`rooms` 也可在连接期间查询；切换账号或房间的命令会先撤销当前共享并停止旧运行时，再执行账号或成员变更。

```text
p2wlan runtime
p2wlan host
p2wlan world
```

先通过 `runtime` 确认 `ready`，再用 `host` 共享并保存自动共享设置。`world` 的 `HOSTED` 表示本机受保护的世界监听和原生服务注册已创建；朋友连接同一房间后，在多人游戏界面发现并加入。

### 3. 重启与手动调整

`config/p2wlan-server.json` 保存 Control 地址、账号 ID、规范房间 ID 和共享开关；会话及设备身份仍由原生 Agent 保存在 `config/p2wlan-private/`。服务器重启后，Mod 在助手就绪后使用同一 Control／账号的保存会话连接原房间；自动共享开启时，再等待房间运行时就绪并发布当前世界。运行时就绪等待最多 **40 秒**，每次启动只尝试一次恢复。

```text
p2wlan status
p2wlan restore
```

`status` 显示恢复阶段、失败码和已保存的房间／共享设置。会话过期时先重新登录，网络失败时先恢复服务，再执行 `restore` 重试保存的房间；恢复过程不自动注册账号、重新登录或创建房间。配置无效时用 `init`、登录和连接命令重新保存；手工编辑配置文件后需重启服务器加载。切换 Control 地址前先断开房间，再执行 `init` 和登录。

| 命令 | 当前操作与下次启动 |
| --- | --- |
| `p2wlan unhost` | 停止共享，保留房间连接；重启仍连接原房间，保持不共享。 |
| `p2wlan disconnect` | 先撤销共享再停止房间，清除保存的房间选择；账号和成员关系保留，下次启动等待手动连接。 |
| `p2wlan logout` | 撤销共享、停止房间并清除账号会话及房间选择，下次需要重新登录。 |
| `p2wlan host` | 共享当前世界，并保存重启后自动共享。 |

其他非敏感 `/p2wlan` 命令也可由权限级别 **4** 的玩家执行；玩家命令需带开头的 `/`。专用服务器遵循自己的 `online-mode`，原有普通监听端口仍由管理员控制。

独立的 [`p2wlan-account` 工具](reference/building.md#dedicated-server-account-console)保留为可选的离线账号维护入口；使用它时才需要停服，避免同时占用私有目录。控制台配置与重启恢复的真实游戏验收状态见[验证参考](reference/testing.md#dedicated-console-and-restart-workflow)。

## 朋友：从多人游戏加入

连接房间后打开 Minecraft「多人游戏」。服务发现只在这个浏览器打开时执行，P2WLAN 条目与普通局域网条目和已保存服务器共存；关闭浏览器会清理发现条目，但不会断开房间。

使用原版选中、双击、Enter 或「加入服务器」操作。Mod 会再次验证当前房间、对端和所选世界身份；世界关闭、更换或目录过期时，旧条目不能继续代表新世界。

共享单人世界通过当前 P2WLAN 房间及世界通道接纳离线玩家；专用服务器遵循管理员的 `online-mode` 设置。Mod 保留原版玩家登录、配置和资源包同意流程。进入世界后，通过移动、聊天等游戏交互确认联机。

## 显示设置与延迟统计

P2WLAN 面板提供「显示设置」和「延迟统计」。显示设置可在 helper 不可用时打开：

| 设置 | 行为 |
| --- | --- |
| 游戏 HUD | 默认关闭，只显示当前 P2WLAN 游戏对端；隐藏 HUD 或打开调试界面时一并隐藏。 |
| 位置 | 四角可选。 |
| 基础 | 最新 RTT 和 Direct／Relay。 |
| 详细 | 基础加 Min／Avg／Max、原生抖动和样本数。 |
| 高级 | 详细加 P50／P90／P95／P99、已确认路径 MTU 和累计收发字节。 |

「保存并返回」写入该实例的 `config/p2wlan-display.json`，「取消」不保存。文件只含显示偏好，不含账号、Control 地址或身份。

统计窗口为最近 **60 秒**、每个原生路径测量 owner 最多 **256** 个最新成功 RTT 样本；样本不足会提示。Direct 与 Relay 历史分别维护。RTT 是 P2WLAN 路径的实测往返时间；没有有效样本时显示「未测得」。

房间面板的在线数量统计近期已观测设备加本机，最多观察 16 个对端。Relay 是直连不可用时承载游戏流量的加密中继路径。

## 安装包与源码构建

在本仓库的 GitHub Actions 中打开成功的 **Minecraft Mod Build**，从 Artifacts 下载 `p2wlan-minecraft-mod-<源码 SHA>`。自动包包含 Windows x64、Linux x64 和 macOS Apple Silicon 的原生资源；macOS Intel／Linux ARM64 需要另外构建对应资源。

玩家与专服安装入口为 `p2wlan-universal.jar`；可选离线账号工具独立构建为 `p2wlan-account-0.1.0.zip`，构建步骤见下方参考。HMCL ZIP 固定一组 Minecraft／加载器／原生目标，文件内的 `P2WLAN-PACK.json` 记录其配置和摘要；按当前电脑与游戏实例选择。

[构建与打包参考](reference/building.md)说明完整 checkout、Rust／JDK 前提、Universal JAR、账号工具、HMCL 打包与校验命令。开发资源需要显式标记；正式 HMCL 包要求正式资源和对应原生目标。

## 常见问题与反馈边界

| 现象 | 先检查 |
| --- | --- |
| 登录按钮禁用／helper 启动失败 | 当前平台是否在 JAR 原生资源中、与 Minecraft 版本匹配的 64 位 Java、私有目录锁／权限，以及 `/p2wlan-client status` 的稳定错误码。 |
| 已加入房间但没有网络 | 是否点击了房间行；加入成员关系不自动启动运行时。 |
| 房间已连但找不到服务器 | 房主是否连接同一房间并显式共享世界；专服是否已 `host`；是否打开多人游戏触发发现。 |
| 发现条目存在但加入失败 | 是否为当前已发布世界、当前账号授权是否有效，以及游戏版本、Mod 和原版认证设置是否匹配。 |
| 只显示 Relay | 当前选择加密中继路径；需要排查直连时，检查双方 NAT 和 UDP 防火墙。 |
| 延迟未知／在线数少 | 是否有新鲜原生测量；面板最多观察 16 个对端，在线数按近期观测统计。 |
| 退出世界后房间仍连接 | 属于当前行为；在 P2WLAN 面板显式点击「断开房间」。 |

反馈时提供 Mod／Minecraft／Loader 版本、操作系统与架构、包内原生目标、最小操作步骤和完整稳定错误码。不要提交 `config/p2wlan-private/`、账号密码、会话 token、设备密钥或未脱敏日志。隐私与本地信任边界见[隐私说明](../../PRIVACY.md)，漏洞使用[非公开报告流程](../../SECURITY.md)。
