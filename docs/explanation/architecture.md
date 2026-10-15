# 架构

> 本页是上游 P2WLAN 网络核心、VPN 客户端或服务端的参考；Minecraft Mod 的安装与操作见[Mod 指南](../../minecraft-mod/docs/quickstart.md)。

上游 P2WLAN 将控制面、数据面和中继分开；Mod 通过原生适配复用其中的网络核心：

- Control Plane（Go + SQLite）负责账号、设备、虚拟地址、房间、信令、凭据、Relay catalog，以及持久化 daemon 上报的权威活动路径观测与有界迁移历史，提供只读 Admin Connections API。
- Rust daemon 负责 TUN、路由、Peer、NAT traversal、加密会话、路径状态、Direct/Relay 选择与向 Control 异步上报权威路径遥测。daemon 是活动路径事实的唯一所有者，Control 不推断或猜测数据面路径。
- Relay（Go）验证短期票据并转发密文，不解密业务载荷。
- Flutter 提供面向终端用户的跨平台客户端界面；CLI 复用 daemon 的本地控制接口。上游 VPN 客户端的用户功能和 daemon↔客户端交互以 Flutter 为入口；本仓库的 Minecraft 功能由 Mod 游戏适配层承载。
- `server/admin-ui` 是面向自托管服务管理员的只读 Control 运维界面，生命周期、权限和数据契约都属于服务端。它使用 React/TypeScript/Vite 开发，但 production 静态产物由 Go 嵌入 `p2wlan-control`，供持有管理员令牌的部署者使用。

Control 的 HTTPS/WSS 只负责控制、信令和路径遥测上报，Relay 的 TLS 连接负责数据转发。客户端负责加入房间并创建本机 TUN。

## 界面边界

Flutter 是上游 VPN 的终端用户界面，管理本机账号、设备、房间、诊断和 daemon 生命周期；客户端功能仍以 Flutter 与本地 daemon 契约为准。`server/admin-ui/` 是部署者使用的服务器运维控制台，只读取 Control 能确认的服务端状态与只读连接遥测，并与 `p2wlan-control` 同 origin、同二进制发布。

daemon 拥有 Direct/Relay 路径状态，Control 通过 `path_telemetry_v1` 持久化其上报的有界快照与迁移记录。管理台按用途提供以下只读视图：

- Relationships 展示 Control 资源归属；
- Connections 展示 daemon 的单向路径观测，Live Topology 可视化选定网络下的 fresh Connections；
- Connection Health 在请求时聚合当前快照和有界迁移历史，Health 工作区复用 Connections 的方向详情与时间线；
- Connection Trends 在遥测写事务中按 network/小时累加计数与 RTT histogram，保留 30 天。

管理台使用这些已上报事实展示状态；路径决策和客户端 TUN 控制仍由 daemon 负责。

管理台只展示 Control 能直接确认的服务端事实。账号、membership、设备挂载和待处理 signaling 可以组成控制面拓扑；daemon 当前选择的 Direct/Relay 数据路径仍由 daemon 拥有，Control 未持久化的状态不会由管理台根据 RTT、候选或在线标记推断出来。

当前数据面使用自包含的 WireGuard-like Noise 实现。实现细节以源码、协议测试和发布身份检查为准。
