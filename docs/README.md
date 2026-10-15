# 文档导航

[← 返回项目介绍](../README.md)

本仓库主攻 Minecraft Mod。先从下面的 Mod 文档进入；上游文档保留网络核心、VPN 客户端和 Control／Relay 的参考契约。

## Minecraft Mod

| 文档 | 内容 |
| --- | --- |
| [项目介绍](../README.md) | 功能、环境要求、快速开始和源码构建 |
| [安装与联机指南](../minecraft-mod/docs/quickstart.md) | Mod／HMCL 安装、账号、房间、世界共享、专服控制台与重启恢复、显示设置与常见问题 |
| [技术导航](../minecraft-mod/README.md) | Mod 工程边界与各主题参考入口 |
| [构建与打包](../minecraft-mod/docs/reference/building.md) | 原生资源、Universal JAR、可选离线账号工具、HMCL ZIP 与自动构建 |
| [架构与协议](../minecraft-mod/docs/reference/architecture.md) | 用户态 TCP、原生 IPC、授权与生命周期围栏 |
| [Minecraft 适配](../minecraft-mod/docs/reference/adapters.md) | 加载器构建输入、共享游戏实现与世界发布 |
| [游戏验证](../minecraft-mod/docs/reference/testing.md) | 真实游戏 fixture 的前提、断言与平台范围 |
| [启动诊断](../minecraft-mod/docs/diagnostics.md) | 初始化错误、日志位置与脱敏反馈 |
| [贡献指南](../CONTRIBUTING.md) | 修改边界、本地检查与提交规范 |
| [隐私说明](../PRIVACY.md) · [安全报告](../SECURITY.md) | 本地数据、信任边界与漏洞报告方式 |

## 上游 P2WLAN

以下文档来自上游 P2WLAN，面向网络核心维护者和 Control／Relay 部署者。本仓库自动构建入口是 [Minecraft Mod Build](../.github/workflows/minecraft-mod.yml)；保留的上游 workflow 通过手动入口维护，对应版本仍须满足各自发布契约。搭建 Mod 所需的 Control 服务时，从[自托管](guides/self-hosting.md)开始。

### 服务端部署与运维

| 文档 | 内容 |
| --- | --- |
| [Control／Relay 自托管](guides/self-hosting.md) | 服务端包、TLS 与 Compose |
| [升级与恢复](guides/upgrade-and-recovery.md) | 备份、恢复、回滚与失败处理 |
| [服务端运维](guides/operations.md) | 服务管理、证书、日志、管理台与数据保护 |
| [网络排障](guides/troubleshooting.md) | Control、Relay、Direct、路由与数据面 |

### 上游客户端

| 文档 | 内容 |
| --- | --- |
| [客户端快速开始](quickstart.md) | VPN 客户端安装与虚拟 IP 验证 |
| [客户端指南](guides/client.md) | CLI、daemon、诊断与支持包 |
| [房间指南](guides/rooms.md) | 网络成员、权限、地址与多房间 |
| [OpenWrt](guides/openwrt.md) | 原生包、procd 与路由器节点 |

### 参考

| 文档 | 内容 |
| --- | --- |
| [配置参考](reference/configuration.md) | Control、Relay 与客户端路径策略配置 |
| [CLI 参考](reference/cli.md) | 上游 `p2wlan` 命令行 |
| [网络参考](reference/networking.md) | 路径选择、端口、MTU、NAT 限制与网络切换 |
| [兼容性](reference/compatibility.md) | 上游平台、发布形态与 Hard↔Hard 协商 |
| [发布契约](reference/release-contract.md) | 上游客户端 `vX.Y.Z` 与服务端 `server-vX.Y.Z` 发布 |
| [路径可观测性](reference/path-observability.md) | 路径遥测、连接健康与趋势统计 |

### 设计与工程规范

| 文档 | 内容 |
| --- | --- |
| [架构](explanation/architecture.md) | 控制面、数据面与中继的划分 |
| [连接生命周期](explanation/connection-lifecycle.md) | 连接身份、建立顺序与旧状态回收 |
| [安全模型](explanation/security-model.md) | 身份凭据、数据面加密与运维边界 |
| [工程质量与架构边界](explanation/engineering-quality.md) | 状态所有权、资源上界、测试分层（Mod 与上游共同遵守） |

## 文档约定

- 只描述当前已实现的行为、稳定规则和可复现操作；开发过程记录在 PR／Issue，版本证据保存在 Release 资产。
- 说明围绕用途、成果和依据，用具体范围、条件与数据限定表述；必要限制放在相关句子中，避免惯例性的防御结尾。UI 提示说明当前状态和下一步操作。
- Mod 玩家指南放在 `minecraft-mod/docs/`，实现、构建与测试契约放在 `minecraft-mod/docs/reference/`，`minecraft-mod/README.md` 维护技术导航。
- 每条命令都能在仓库或发布包中找到执行入口；命令、路径和默认值以源码为准。
- 示例统一使用 `example.com` 与占位符，不写入真实地址、密钥、token、用户目录或日志。
