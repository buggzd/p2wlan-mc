# P2WLAN Minecraft 文档

本仓库的产品入口是 **Minecraft Java Edition / Fabric Mod**。文档只描述当前实现、稳定规则和可复现操作，不保存开发日志、阶段报告或验收流水。

## Mod 使用与开发

- [项目介绍](../README.md)：功能、版本、平台边界和源码构建入口。
- [安装与联机指南](../minecraft-mod/docs/quickstart.md)：Mod／HMCL 安装、账号、房间、世界共享、专服和显示设置。
- [Mod 技术参考](../minecraft-mod/README.md)：独立 Java／Rust 工程、原生资源、IPC、身份围栏和测试入口。
- [贡献指南](../CONTRIBUTING.md)：修改边界与按组件执行的检查。
- [隐私说明](../PRIVACY.md)与[安全报告](../SECURITY.md)。

Mod 产品文档放在 `minecraft-mod/docs/`，实现契约与测试说明保留在 `minecraft-mod/README.md`。

## 保留的底层客户端与服务端指南

以下文档来自本仓库保留的 P2WLAN 底层实现，供网络核心维护者、Control／Relay 部署者和上游客户端使用者查阅。**它们不是 Minecraft Mod 安装说明**：其中的 TUN、系统路由、Flutter、CLI、移动端和客户端发布包不表示 Mod 具备同样的产品行为或平台验收结果。

- [底层客户端快速开始](quickstart.md)：独立 VPN 客户端与虚拟 IP 业务验证。
- [底层客户端指南](guides/client.md)：CLI、daemon、诊断和支持包。
- [OpenWrt](guides/openwrt.md)：原生包、procd 和路由器节点；不是 Mod 运行目标。
- [底层房间指南](guides/rooms.md)：网络成员、权限、地址与上游多房间客户端。
- [Control／Relay 自托管](guides/self-hosting.md)：服务端包、TLS 和 Compose。
- [服务端升级与恢复](guides/upgrade-and-recovery.md)：备份、恢复、回滚和失败边界。
- [服务端运维](guides/operations.md)：服务管理、证书、日志、管理台与数据保护。
- [底层网络排障](guides/troubleshooting.md)：Control、Relay、Direct、路由与数据面。

## 底层准确规则

- [配置参考](reference/configuration.md)
- [上游 CLI 参考](reference/cli.md)
- [网络参考](reference/networking.md)
- [上游兼容性参考](reference/compatibility.md)
- [上游发布契约](reference/release-contract.md)
- [路径可观测性](reference/path-observability.md)

上游客户端的 `vX.Y.Z` 与服务端的 `server-vX.Y.Z` 不是 Mod 发布版本。Mod 原生资源、JAR 和 HMCL ZIP 的身份契约以 Mod 构建工具与技术参考为准。

## 底层机制与共同工程规则

- [底层架构](explanation/architecture.md)
- [工程质量与架构边界](explanation/engineering-quality.md)
- [底层安全模型](explanation/security-model.md)
- [连接生命周期](explanation/connection-lifecycle.md)

## 文档边界

- 根 README 面向 Mod 使用者，只保留产品定位、限制和最短入口；具体操作放在 Mod 指南，底层文档不冒充 Mod 行为。
- 部署脚本、配置生成器、测试和 workflow 是各自组件的可执行契约；保留的上游 CI 不等于 Mod 的构建／游戏验证。
- 源码审查、测试输出、真实设备验收和发布清单绑定到对应 PR、Issue 或 Release 资产，不复制到 docs/。
- 示例使用 example.com、占位符和演示数据。真实地址、密钥、token、用户目录和日志禁止进入仓库。
