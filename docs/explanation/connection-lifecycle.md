# 连接生命周期

> 本页是上游 P2WLAN 网络核心、VPN 客户端或服务端的参考；Minecraft Mod 的安装与操作见[Mod 指南](../../minecraft-mod/docs/quickstart.md)。

连接的有效状态由多个有界身份共同决定：daemon 进程、网络 generation、peer session、remote candidate epoch、Relay connection 和 Direct validation owner。旧任务完成时必须先检查它仍属于当前 owner，不能清除新连接的状态。

生命周期顺序是：

    注册 → 获取候选 → 建立 Control/Relay 信令 → 加密确认 → 选择 Direct 或 Relay → 持续刷新 → 网络/身份变化后重新验证 → 离开或撤权

Direct-first 的初始截止时间由权威路径 reducer 持有，使用本地单调时钟，不使用远端墙钟。重复在线通知、重试和候选刷新不会重置截止时间。认证 Relay 的就绪证据与本端 Relay 业务准入分开，窗口内的 Relay 确认只建立备用路径，不把首业务切向 Relay。

Relay 已确认可用时，Direct 探测失败或候选刷新不能直接清除 Relay。Direct 只有当前候选和加密业务确认成功后才替换 Relay。撤权、离开房间、地址改变、进程替换和网络 generation 变化会使旧会话失效。

活动路径变化仅在 `PeerConnection::commit_path_transition` 纯 reducer 接受并提交后生效；未提交的瞬态试探或非法事件不影响路径状态，亦不上报遥测。daemon 与 Control 的路径遥测遵循严格的分层 Fencing 规则：

    registration_seq (会话所有者) > network_generation > peer_session_generation > remote_candidate_epoch > observation_revision

旧会话或过期 registration_seq 无论携带多大的 revision 编号，都永远无法覆盖新会话的路径记录。遥测上报保持单向性 `(reporting_device, remote_device, network)`，仅反映本地端点直接观测到的对端路径状态。

诊断状态按进程和 revision 防止旧快照覆盖新状态。Connecting、在线设备数量、加密 ACK、收包计数和业务可达性分别表示不同层级；最终判断必须使用实际虚拟 IP 业务验证。

Android 网络回调先经过服务与 bridge 身份检查，再按已接纳的发布顺序合并积压通知；服务重建后重新开始的 Kotlin 计数不作为跨服务时钟。Control 注册过程吸收最新通知，较旧注册结果不能发布授权，已被当前注册覆盖的通知不会再次触发注册。UDP 在绑定前后复核网络通知，初始 STUN 采集与稳定运行共享生命周期取消和原 owner 清理路径，切网不必等待旧网络完成整轮采集。
