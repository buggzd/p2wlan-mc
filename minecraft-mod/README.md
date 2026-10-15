# P2WLAN Minecraft 技术导航

[项目介绍](../README.md) · [安装与联机指南](docs/quickstart.md) · [文档导航](../docs/README.md) · [贡献指南](../CONTRIBUTING.md)

`minecraft-mod/` 是本仓库的主要产品目录，包含独立 Rust workspace、共享 Java 实现、加载器适配、构建工具和测试。玩家安装 `p2wlan-universal.jar`；它选择 Minecraft 1.16.5、1.18.2、1.20.1、1.21.1 中十个精确加载器配置之一，配置以 [`minecraft_profiles.py`](tools/minecraft_profiles.py) 为准。

## 按任务阅读

| 任务 | 文档 |
| --- | --- |
| 安装、账号、房间、共享世界、专服控制台与重启恢复、显示设置 | [安装与联机指南](docs/quickstart.md) |
| 定位初始化、资源提取和助手启动错误 | [启动诊断与日志](docs/diagnostics.md) |
| 源码构建、原生资源、Universal JAR、可选离线账号工具和 HMCL ZIP | [构建与打包参考](docs/reference/building.md) |
| 状态所有权、授权、TCP 桥、IPC、进程与私有配置 | [架构与协议契约](docs/reference/architecture.md) |
| 加载器构建输入、共享游戏实现、界面、世界发布与游戏钩子 | [Minecraft 适配契约](docs/reference/adapters.md) |
| 真实游戏 fixture 的前提、精确断言、执行入口与平台范围 | [游戏验证参考](docs/reference/testing.md) |

## 工程边界

Mod 从当前完整 checkout 复用上游 P2WLAN daemon 和网络 crate。`tools/native.py` 在忽略目录 `native/target/upstream-runtime/` 生成构建适配，精确检查源码锚点及输入摘要；上游运行时继续拥有房间授权、peer、加密会话、Direct／Relay 和网络代际状态。

Java 负责游戏生命周期、界面、受保护的本机 TCP 监听和 IPC。跨线程结果携带原生 process／runtime／generation／revision 与世界身份，在实际发送、提交或呈现前再次验证。共享游戏实现位于 `java/game-1.21.1/`，版本 API 与加载器差异由薄适配层提供。

Mod 使用用户态游戏通道，不创建系统 TUN 或安装路由。根 `client/` 中的 VPN 客户端、`server/` 的 Control／Relay 及 `docs/` 的上游运维资料保留各自用途；它们的安装、平台支持和发布规则由对应组件文档说明。上游仓库 skill 作为网络核心与服务端维护参考，Mod 的构建、包名和验证入口以本目录实现为准。

## 快速开发检查

从完整仓库根目录运行，使用 Python 3.11+、Rust 和 JDK 17／21：

```sh
python3 -m unittest discover -s minecraft-mod/tests -p 'test_*.py'
python3 minecraft-mod/tools/java.py test
python3 minecraft-mod/tools/native.py fmt --all -- --check
python3 minecraft-mod/tools/native.py test --workspace --locked
python3 minecraft-mod/tools/native.py clippy --workspace --all-targets -- -D warnings
```

所有修改还须执行[贡献指南](../CONTRIBUTING.md#本地检查)中的仓库门禁。原生／Java 互操作、包内原生程序、真实 Minecraft、Windows NTFS 和终端交互按相关参考页的环境要求分别执行。

正式原生资源要求干净且已提交的 checkout；开发资源需要显式 `--development`。完整 Universal 构建使用固定 SDK 和辅助 JDK，准备及产物校验见[构建与打包参考](docs/reference/building.md)。
