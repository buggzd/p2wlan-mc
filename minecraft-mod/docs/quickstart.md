# Minecraft Mod 安装与联机指南

[仓库介绍](../../README.md) · [技术参考](../README.md) · [文档导航](../../docs/README.md)

本页按玩家、房主和专用服务器三种角色，介绍从安装到进入游戏的完整操作。项目处于实验阶段，已验证和未完成的范围见[项目状态](../../README.md#项目状态)。

## 安装前准备

- Minecraft **Java Edition 1.21.1**。
- Fabric Loader 构建版本 **0.16.14**；Mod 元数据要求 `>=0.16.14`，其他 Loader 版本仍需自行验证。
- Fabric API **0.116.7+1.21.1** 和 **Java 21**，JVM 架构须与包内原生程序匹配。
- 包含目标操作系统／架构原生 Agent 的 Mod JAR，或对应目标的 HMCL ZIP。
- 管理员提供的可信 Control 根地址，以及参与者在同一 Control 下的房间。需要自托管时参考[底层服务部署指南](../../docs/guides/self-hosting.md)。

Mod 自带联机所需的原生程序，无需安装上游 P2WLAN 桌面或移动客户端。Minecraft 本体、Java、游戏账号和 Control 服务需要自行准备。

### 手动安装 JAR

1. 为客户端创建独立的 Fabric 1.21.1 游戏实例；专用服务器也使用匹配的 Fabric 版本。
2. 在实例的 `mods/` 目录放入 Fabric API 和 `p2wlan-fabric-1.21.1-0.1.0.jar`。
3. 确认 Mod 包含本机的原生目标。默认源码构建只包含构建主机的资源；跨系统复制 JAR 可能因缺少资源而拒绝启动。
4. 正常启动 Minecraft。原生 Agent 随 Mod 包提取和启动，不从网络下载可执行程序。

原生资源目标名为 `macos-aarch64`、`macos-x86_64`、`linux-aarch64`、`linux-x86_64`、`windows-x86_64`。实机验证范围见[项目状态](../../README.md#项目状态)。Windows 的游戏实例、私有配置与原生提取位置须满足本地 NTFS 和用户 ACL 检查；网络共享、重解析链接或不匹配架构不能用关闭校验来绕过。

### 导入 HMCL ZIP

在 HMCL 的**整合包安装入口**选择对应目标的 ZIP。不要把 ZIP 放进 `mods/`，也不要把它当作 EXE 安装器。

包内固定 Minecraft／Fabric 版本，并附带 Mod、Fabric API、中文安装说明和 `P2WLAN-PACK.json`；游戏与 Loader 由启动器获取，Java 和游戏账号由玩家自行准备。打包校验核对 ZIP 内容、文件摘要和原生构建身份。

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

此流程已有游戏内入口，完整的单人世界共享／暂停及跨机器验收仍未完成。

1. 登录并连接房间，等待房间运行时就绪。
2. 进入想共享的单人世界，打开暂停菜单中的 P2WLAN 面板。
3. 点击「共享本地世界」。Mod 显式发布当前世界，不调用原版「对局域网开放」广播，也不自动改变作弊或默认游戏模式。
4. 朋友连接同一房间后，在「多人游戏」中选择发现的 P2WLAN 服务器。
5. 不再共享时点击「停止共享」；世界关闭或替换也会撤销本次发布。

共享按钮无需开启作弊。朋友进入世界后，可通过移动、聊天等游戏交互确认联机。

## 专用服务器：初始化账号并发布世界

### 1. 停服初始化 P2WLAN 账号

使用与 Mod 对应的 `p2wlan-account` 分发包。先停止 Minecraft 服务器，再以**运行服务器的同一系统用户**操作该服务器的 `config/p2wlan-private/`。Mod 和账号工具不能同时占用同一私有目录。

在已解压的账号分发包根目录运行。下面是 macOS／Linux 启动器示例，将绝对目录占位符替换为自己的服务器目录：

```sh
./bin/p2wlan-account status --profile-dir /absolute/path/to/server/config/p2wlan-private
./bin/p2wlan-account login --profile-dir /absolute/path/to/server/config/p2wlan-private
./bin/p2wlan-account rooms --profile-dir /absolute/path/to/server/config/p2wlan-private
./bin/p2wlan-account join-room --profile-dir /absolute/path/to/server/config/p2wlan-private
```

没有 P2WLAN 账号时改用 `register`，需要创建房间时使用 `create-room`。Control、邮箱和密码通过终端交互输入；密码操作要求真实终端，不支持密码命令行参数或回显 stdin 后备。不要把账号密码放进 Minecraft 命令、环境变量或公开日志。

工具只初始化账号／成员关系，不启动房间、不发布世界、不接受 Minecraft EULA，也不修改服务器的认证模式。Windows 有独立的批处理启动器，但真实 Windows 配置／启动验收仍未完成。

### 2. 启服后显式连接与发布

按照正常 Fabric 服务器流程启动，游戏条款由管理员自行确认。以下为游戏内命令，要求权限级别 **4**；在服务器控制台输入时省略开头的 `/`。将 `<房间码>` 替换为已加入房间的 8 位数字码：

```text
/p2wlan status
/p2wlan connect <房间码>
/p2wlan runtime
```

等待 `runtime` 为 `ready` 后再执行：

```text
/p2wlan host
/p2wlan world
```

`HOSTED` 表示本机受保护的世界监听和原生服务注册已创建。朋友需要连接同一房间，在多人游戏界面发现并加入。

### 3. 停止共享或断开

```text
/p2wlan unhost
/p2wlan disconnect
```

`unhost` 撤销世界共享但保留房间连接；`disconnect` 先撤销世界，再停止房间运行时，保留账号会话和成员关系。专用服务器原有普通监听端口不受这些命令管理，公网暴露仍由管理员控制。

## 朋友：从原版多人游戏加入

连接房间后打开 Minecraft「多人游戏」。服务发现只在这个浏览器打开时执行，P2WLAN 条目与普通局域网条目和已保存服务器共存；关闭浏览器会清理发现条目，但不会断开房间。

使用原版选中、双击、Enter 或「加入服务器」操作。Mod 会再次验证当前房间、对端和所选世界身份；世界关闭、更换或目录过期时，旧条目不能继续代表新世界。

Mod 保留原版登录、认证、配置和资源包同意流程，不提供游戏账号或正版认证绕过。进入世界后，通过移动、聊天等游戏交互确认联机。

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

## 从源码获得安装包

使用完整仓库，而不是只复制 `minecraft-mod/`：原生适配会读取本 checkout 中的上游网络实现。需要 Python 3.11+、Rust 及原生编译依赖、JDK 21；第一次构建需要联网解析固定依赖。

在干净、已提交的仓库根目录运行：

```sh
python3 minecraft-mod/tools/native_bundle.py
python3 minecraft-mod/tools/java.py fabric-build
```

JAR 位于 `minecraft-mod/java/adapters/fabric-1.21.1/build/libs/p2wlan-fabric-1.21.1-0.1.0.jar`。账号工具另外构建：

```sh
python3 minecraft-mod/tools/java.py account-build
```

对应分发包为 `minecraft-mod/java/account-cli/build/distributions/p2wlan-account-0.1.0.zip`。

生成当前原生目标的 HMCL 包时显式指定该目标。例如已有 `macos-aarch64` 正式原生资源：

```sh
python3 minecraft-mod/tools/java.py hmcl-pack -PhmclPlatform=macos-aarch64
```

输出为 `minecraft-mod/java/adapters/fabric-1.21.1/build/distributions/p2wlan-hmcl-fabric-1.21.1-macos-aarch64-0.1.0.zip`。换用其他目标前必须先生成其对应资源；不存在的目标、开发原生包和过期源码身份会让打包失败。

`--offline` 只在依赖已缓存时使用，不是首次构建的前提。开发资源需要显式开发标记，普通安装默认拒绝，不能把开发 JAR 当作正式安装包。具体开发与 Windows 交叉构建入口见[技术参考](../README.md)。

## 常见问题与反馈边界

| 现象 | 先检查 |
| --- | --- |
| 登录按钮禁用／helper 启动失败 | 当前平台是否在 JAR 原生资源中、64 位 Java 21、私有目录锁／权限，以及 `/p2wlan-client status` 的稳定错误码。 |
| 已加入房间但没有网络 | 是否点击了房间行；加入成员关系不自动启动运行时。 |
| 房间已连但找不到服务器 | 房主是否连接同一房间并显式共享世界；专服是否已 `host`；是否打开多人游戏触发发现。 |
| 发现条目存在但加入失败 | 是否为当前已发布世界、当前账号授权是否有效，以及游戏版本、Mod 和原版认证设置是否匹配。 |
| 只显示 Relay | 当前选择加密中继路径；需要排查直连时，检查双方 NAT 和 UDP 防火墙。 |
| 延迟未知／在线数少 | 是否有新鲜原生测量；面板最多观察 16 个对端，在线数按近期观测统计。 |
| 退出世界后房间仍连接 | 属于当前行为；在 P2WLAN 面板显式点击「断开房间」。 |

反馈时提供 Mod／Minecraft／Loader 版本、操作系统与架构、包内原生目标、最小操作步骤和完整稳定错误码。不要提交 `config/p2wlan-private/`、账号密码、会话 token、设备密钥或未脱敏日志。隐私与本地信任边界见[隐私说明](../../PRIVACY.md)，漏洞使用[非公开报告流程](../../SECURITY.md)。
