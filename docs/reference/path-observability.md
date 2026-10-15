# 路径可观测性参考

> 本页是上游 P2WLAN 网络核心、VPN 客户端或服务端的参考；Minecraft Mod 的安装与操作见[Mod 指南](../../minecraft-mod/docs/quickstart.md)。

路径观测只读取已提交的 Direct/Relay 状态，不做路径决策、不写 socket、不阻塞数据面。唯一的状态转换入口是 commit_path_transition；每个 peer 最多保留 32 条转换记录，动态标签不包含 peer id、endpoint、IP、session id 或任意错误文本。

本地认证的 GET /status 响应附带进程内递增的 X-P2WLAN-Status-Request-ID 响应头，用于把同主机客户端的连接/首字节耗时与 daemon 快照、序列化和写回阶段对应起来。诊断日志只记录请求编号、进程编号、耗时和快照重试/回退状态，不记录授权头、token 或 peer 身份；JSON schema 不依赖该可选响应头。

状态对象带 schema_version、network_generation、peer_session_generation、remote_candidate_epoch、lifecycle、current_path、previous_path、transition_reason、last_path_change_reason、path_age_ms、first_direct_commit_age_ms、direct_state、relay_state、recovery_state、selected_path_mtu 和 selected_udp_datagram_size。`transition_reason` 是最后一次被接纳事件的原因，可能没有改变活动路径；`last_path_change_reason` 仅在活动路径实际切换时更新。首个 Direct 提交的 age 保留到本网络代际结束，不依赖 32 条转换事件环。

固定指标字段包括：

    accepted_transitions
    accepted_observations
    duplicate_events
    rejected_transitions
    path_changes
    direct_attempts
    direct_retries
    direct_validations
    direct_successes
    direct_failures
    validation_failures
    relay_confirmations
    relay_fallbacks
    relay_failures
    candidate_refreshes
    control_reconnects
    network_generation_changes
    lifecycle_resets
    dplpmtud_changes
    active_tasks
    active_sockets
    dropped_transition_events
    direct_time_to_connect_ms

direct_time_to_connect_ms 使用固定边界 50、100、250、500、1000、3000、10000、30000 毫秒和一个溢出桶。Direct-first 使用 `direct_first_started`、`direct_first_deadline` 和 `direct_first_satisfied` 记录权威准入窗口事件；这些事件记录窗口的开始、截止和满足状态。reason code 是封闭集合；诊断文本不能变成指标标签。旧客户端缺少该对象时按 schema 0 读取，不能把缺失字段解释成当前路径成功。

control_reconnect_counter_survives_timeline_eviction 是必须保持的回归契约：进程时间线淘汰旧记录后，Control 重连计数仍来自有界状态，而不是依赖已被淘汰的事件。

`connection_timeline.hot_path_observations` 保留进程生命周期内的精确分类计数，包括会话证据锁竞争、身份过期、业务 ingress 暂缓、出站队列冲刷批次，以及匹配 ACK 被验证调度器接纳、合并、限流或因健康 Direct 而忽略的次数。高频会话事件只在每类计数达到 1、2、4、8 等 2 的幂时写入时间线和状态事件流；计数本身不会按包推动状态 revision。`session_evidence_contended` 表示当前身份围栏暂时竞争，`stale_session_evidence` 表示身份已过期，不应仅凭采样事件数反推总包数。

## Hard↔Hard attempt report

控制客户端的阶段追踪同时识别 `hh1` 与 `hh2`，服务端在 `P2WLAN_A0_SIGNAL_TRACE=1` 时记录对应信令接纳阶段。关联字段使用短摘要标签，不输出原始 session token；信令持久化记录服务端接纳；对端探测执行和 Direct 提交使用各自阶段的记录。

实验环境变量 P2WLAN_EXPERIMENT_* 标签和信令延迟只在显式 --hard-hard-experiment-only 模式下生效；信令延迟默认为 0，最大 2000 ms。普通模式即使继承这些环境变量，也不会增加延迟或把实验标签写入 attempt 报告。

普通运行也记录 Hard↔Hard 阶段、profile 绑定和执行认领失败原因。`hard_hard_candidate_work` 区分候选 worker 开始与结束，携带匿名 session/plan/peer 标签、任务 owner 及期望和观察到的代际；`hard_hard_candidate_discarded` 记录排队替换或重放保留。已经进入 worker 的任务被取消时仍输出结束记录，尚未开始的排队任务随 peer 生命周期清理时需结合生命周期日志定位。投递层的 `Applied` 表示应用接纳；hard 计划接纳、发送和 Direct 提交分别由对应阶段记录。

认领失败进一步区分时间窗、peer session、计划、恢复 epoch、generation 配额和去重等待原因；`phase=lease_release` 说明旧信令租约是否已请求按序重投。高频 `control_healthy` 过程日志使用 debug 级别，断连和恢复事件保持原可见性，避免正常心跳占满上传日志尾部。

`/status` 的 `peers[].direct_events[]` 在 `stage=hard_hard_attempt_report` 时携带 schema 2 的 `hard_hard_attempt`。它是现有会话状态所有者导出的只读终态记录，不参与候选排序、发送准入、路径选择或取消判定。写入前会再次核对 network generation、peer session generation、remote candidate epoch、profile generation、punch generation、socket index、session token 和 attempt；旧会话的迟到结果不会记到新会话。`plan_tag` 仅用于把同一会话的单个 rendezvous 计划在两端配对，与 `session_tag` 分离。

终态记录由原会话的诊断 owner 单次封存。写入当前连接时，在有界等待后持有代际与连接保护并再次核对完整身份；身份替换、锁竞争或提交等待被取消时，原记录进入独立的 `hard_hard_attempt_report_archived` 结构化日志，不写入新连接的事件环。该历史日志不参与当前路径判断。选中 socket、扫描汇总和完成等辅助事件始终输出结构化日志，连接事件环只作非阻塞写入；辅助记录不会阻塞确认发包或正式终态的封存。认证接收证据仅来自原 token 和所属动态 socket 的精确候选对，不使用同 peer 的普通 Probe 汇总差值。

同一封存入口另将最后 16 条正式 HH 终态压缩到进程时间线的 `hard_hard_terminal_summaries`，连接对象更换后仍可读取。摘要只保留阶段、代际、匿名会话/计划标签、发送与匹配 ACK 计数及结果；`current_connection_committed=false` 和 `archive_reason` 明确表示旧身份终态未写入当前连接。该摘要用于跨连接对象查看历史终态；当前路径从 peer 状态读取，业务结果从 ingress 记录读取。

`mode` 表示当前会话实际协商的 `fixed_anchor`、`predictable` 或 `birthday`；兼容流程保留其原有模式值。模式来自同一会话的权威计划，不能仅凭 socket 数推断。

身份字段包含构建源码、比较基线、build ID、实验 variant/scenario/seed、角色与 attempt。原始 session、IP 和端口不进入结构化记录；`session_tag` 与 `target_order_tags` 是会话加盐的短 SHA-256 标签，只用于同一 attempt 内关联和保留候选顺序，不能当作跨会话身份。

候选与发送成本保持不同口径：

- `requested`、`generated`、`unique`、`advertised` 分别表示请求、模型输出、去重和本端已提交且获得发布接受证据的候选数。`parsed_targets_for_plan` 只表示到达本端并解析后进入该计划的目标数；它不是信令原始接收量，也不表示解析前数量或裁剪前数量；
- `planned_targets`、`planned_sockets`、`planned_socket_target_combinations`、`planned_logical_probes` 与 `planned_physical_datagram_cap` 描述有界计划；
- `attempted_targets` 是至少收到一次逻辑探测的唯一远端目标数；重复波次由 `logical_probes_attempted`、`logical_probes_sent` 单独计数；
- `send_success_datagrams` / `send_success_bytes` 与 send-error 字段描述实际 UDP 系统调用结果。一个逻辑探测可能带一个有界兼容副本，因此物理 datagram 数不能从候选数推导；
- `budget_skipped` 与 `planned_logical_probes_not_attempted` 保留没有执行的计划量；矩阵汇总把后者归为成功后取消、过期、预算拒绝、生命周期失效或未知原因；
- STUN datagram/byte/error/response 单独计费。`candidate_signal_payload_logic_bytes` 只累计候选和来源字符串长度，不是序列化请求、HTTP/WebSocket 帧、TLS 或完整控制传输字节。

`counts.send_success_datagrams` / `send_success_bytes` 保持扫描口径。支持完整确认计数的 HH2 attempt 另带可选 `confirmation`：`triggered_check`、`nomination`、`probe_ack`、`validation_request`、`validation_ack` 分别记录成功 UDP handoff 的 `datagrams` 和 `bytes`；`retryable_not_sent`、`budget_deferred`、`delivery_unknown`、`stopped` 记录候选对发送结果分类次数。确认成本不占用或改写扫描计划字段。旧版、兼容流程或尚未绑定 owner 的报告缺少该对象时表示未知，不能当作零。

矩阵成本汇总使用 `probe_cost_scope=sweep_only` 明确原扫描口径，并在 `confirmation_costs` 单独给出确认成本已知部分、各字段缺失报告数与完整报告数。有效轮次与全部 requested 轮次分别统计；字段缺失时，汇总保留已知成本并标明缺失报告数。

生日扫描的正式报告可带 `birthday_sweep`，保留同一终态快照中的 socket 可用性、计划与完成波次、目标覆盖、按 socket 的物理发送量、错误和停止原因；辅助事件环缺项不再承担这些计数的唯一存储。该对象缺失表示旧版本或没有生日扫描明细，不能解释为零发送。其 `first_send_at_ms` / `last_send_at_ms` 保留原扫描账本的本端墙钟口径；过程耗时仍使用报告 `timeline` 中的单调时间，不混用两种时钟。

`target_order_tags` 保留实际目标顺序，重复目标仍重复出现；`confirmed_target_rank` 在加密验证选中的远端地址属于该计划时记录其从 0 开始的位置，不暴露地址，空值表示未确认 Direct 或确认的是计划外学习地址。`candidate_cap` 与 `truncation_reason` 说明裁剪边界。分类包括 `measurement_insufficient`、`model_unpredictable`、`budget_rejected`、`send_error`、`missed_schedule`、`candidate_not_executed`、`no_response`、`probe_hit_validation_failed`、`cancelled_generation_changed`、`encrypted_validation_completed` 和证据不足时的 `unknown`。`encrypted_validation_completed` 记录加密验证完成；采集器在真实业务 ingress 存在时派生 `direct_business_succeeded`。

`timeline` 时间字段来自同一 daemon 进程的单调时钟。`planned_send_at_ms` 在 `hh2` 完成最终首发协商时更新为约定时间，发送偏差不再与此前的最晚截止比较。`candidate_signal_accepted_at_ms` 表示本端发布流程获得接受证据的观察时刻：证据可以是信令 API 成功返回，也可以是同一 `hh2` 轮次已通过身份检查的后续消息。它不是服务端持久化或对端实际接收的时间戳，也不表示双方已经完成整个交换，不能用它计算 HTTP RTT。`probe_last_hit_at_ms` 与 `probe_last_hit_source` 表示验证前最后一次认证 Probe 或匹配 ACK，不是首次命中。`measurement_age_at_send_ms`、`measurement_to_first_send_ms`、`last_probe_hit_to_validation_ms` 使用非负差值；缺时间或顺序逆置时为空，不把异常压成零。它们分别描述测量新鲜度、测量到首发、最后命中到验证。最终 SYNC_ACK 的本地入队、服务端接受与对端实际接收是不同证据；本地入队不会授予 Direct，也不能单独作为无响应策略失败的依据。

矩阵采集器只在验证 session、Direct commit sequence、transport instance 和 socket index 全部与对应 timeline 事件一致时，才把业务里程碑归给该 attempt。仅有 peer/network generation，或任一身份字段缺失/不匹配时，`business_ready_at_ms`、`first_business_success_at_ms` 和派生的 `validation_to_first_business_ms` 保持未知并标为 `not_attributable`；不从同 generation 的其他 attempt 回填。`connection_to_first_business_ms` 目前缺少可与该 attempt 精确关联的连接开始身份，因此保持未知。请求级真实双向业务证据仍由既有 `first_usable_summaries` 判定，两种统计口径不得互相替代。

`business_ready_at_ms` 只取生产出站选择器观察到 `direct_business_mtu_ready` 的时刻；`first_business_success_at_ms` 只取精确身份匹配的 Direct `business_ingress_observed`。两者都不能用 active path、probe/ACK 或加密验证时刻代替。两者分别描述本端出站就绪和入站交付，均须晚于本端加密验证，但在双向业务中互相没有先后因果约束：对端可能在本端出站 MTU 探索完成前先发送一帧合法业务。不同 daemon 的绝对毫秒值不能互减。

## Hard↔Hard NAT 实验入口

独立矩阵入口不会替换既有 Direct、Relay 或 fail-closed gate：

    python3 scripts/nat-sim/run-hard-hard-matrix.py --list
    python3 scripts/nat-sim/run-hard-hard-matrix.py \
      --scenario equal-step \
      --scenario random-high-entropy-negative \
      --rounds 1 \
      --output /absolute/path/outside/repository/hard-hard-evidence

`--help` 列出完整参数。输出目录必须是仓库外尚不存在的绝对路径，创建为仅当前用户可访问；runner 不覆盖或清理已有目录。每个固定 seed 只执行一次，不做诊断重试，原始 stdout/stderr、两端日志/status、NAT trace、进程清理耗时和普通 `nat-evidence.json` 均保留。缺任一侧 typed attempt、首业务证据、健康的 critical task、完整进程回收或原始 trace 时，manifest 失败关闭。

矩阵包含等/异步长、负步长回绕、端口竞争、单/双侧严格过滤、非对称 NAT/STUN/信令/准备延迟、丢包/乱序/重复，以及固定 seed 的高熵随机映射和 Relay 重连。Hard↔Hard 模式默认 `EGRESS_CAPTURE=shim`，在 macOS/Linux 测试子进程中拦截生产 daemon 的 loopback UDP `sendto` / `sendmsg`，保留原 socket 和目的地址；发往尚未绑定目的端口的包也先创建源 NAT 映射，再执行对端过滤。此模式关闭预建监听槽。普通 Direct/Relay gate 保留原入口；shim 不链接进生产程序，也不安装到全局环境。

`CONSUME_A` / `CONSUME_B` 在每个已测量 socket 的最后一次 STUN 与首次 peer 出站之间注入分配；`SWEEP_NOISE_EVERY` / `SWEEP_NOISE_COUNT` 在发送期间按新映射数量注入，并受 `SWEEP_NOISE_LIMIT` 总量约束。trace 区分两个阶段、源 socket、新映射和未绑定目的。`mapping_fidelity` 核对两端 shim 成功发送的包数/字节与网关捕获完全一致，并检查严格过滤与注入配置确实生效；本机 UDP 捕获丢包、缺计数或噪声阶段不符都会使该轮证据无效。互惠映射对数量是整轮模拟器事实，不能代替 attempt 命中、加密验证或业务成功。

高熵场景是负对照：允许 Direct 失败并以 Relay 有界兜底，但不允许缺证据、任务泄漏或无界退出。取消的 generation/session fencing 由隔离 Rust 回归覆盖；矩阵使用本地双进程和配置的模拟 NAT。

Hard↔Hard 实验的 Direct 放行时间来自双方 `hard_hard_start_activated` 的最终 SYNC 起点。最初的 `hard_hard_rendezvous_scheduled` 是协商上界，不能用于放行已提前的实际扫描。

manifest 分开报告 requested 场景/轮次、smoke 执行结果、证据有效性、保护期内 Direct 首业务、Relay-first、Relay 后升级 Direct、固定观测期最终 Direct、全部可读 typed attempt 失败/终态分布、测量年龄与计划偏差、候选执行和确认命中位置、条件延迟样本量、全部 requested 与 valid-only 两套 packet/byte/STUN/候选逻辑 payload 成本、同一 session/plan 的配对和不完整报告数、计划 Socket 峰值、清理耗时、子进程 CPU/RSS 和 critical task 数。部分无效轮次中可读的单侧成本保留，未读到的字段以 unknown/incomplete 表示；完整控制传输字节当前未知。`first_usable` 的请求级路径结果、attempt 级身份归因和有效轮次统计是独立分母。资源数的采集范围为本地构建、启动、实验与清理。确定性回归核对候选顺序、预算、路径和取消行为，场景与轮次按配置和 seed 统计。

## 正常接入与动态网络条件

`MODE=normal` 使用正常后台重试和路径选择，不启用 Hard↔Hard 独占开关或 Direct 放行屏障；默认观察 20 秒，可用 `NORMAL_OBSERVE_S` 设置为 10–120 秒，并相应增加 `OVERLAY_TIMEOUT_S` 和总轮次时限。业务允许通过 Direct 或 Relay，证据保留实际路径，并继续执行首业务时限、HTTP 状态、任务健康、无损坏载荷和完整 UDP 捕获检查。若持久化摘要证明首个 Direct 业务发生在 Relay 就绪之前，Relay 参考时间差保留为空并标记不适用，不伪造零延迟；专用 Direct/Relay 拓扑的时限门禁保持不变。它不要求本轮一定出现 Hard↔Hard attempt，业务可用与直连成功分别报告。

    MODE=normal ROUNDS=1 STRICT_FILTERING=1 \
      BACKGROUND_DEVICES=8 BACKGROUND_FLOWS=32 BACKGROUND_INTERVAL_MS=250 \
      NAT_SIM_ARTIFACT_DIR=/absolute/path/outside/repository/normal-nat-evidence \
      bash scripts/nat-sim/nat-sim-smoke.sh

背景流量在每端第一次 daemon UDP 出站后启动：每个逻辑设备使用独立 socket 和带种子的时间抖动发送真实 STUN 请求，与该端共享端口分配器，不读取候选或扫描阶段。设备数上限 16，间隔 10–5000 ms；默认关闭。有限流模式每设备最多 64 个短流；`BACKGROUND_DURATION_MS` 可启用最长 120000 ms 的持续流量，每端配置上限为 16384 次流尝试。`mapping-evidence.json` 核对请求完成、失败、退出取消以及与 daemon 流量的时间重叠；持续模式逐台检查从接入到最终捕获期间的流量间隔，缺少任一设备或出现超限空档都会失败。SIGTERM 退出会先回收背景任务并记录尚未完成流的取消，再关闭 trace。该模型覆盖共享端口分配干扰；`LOSS`、`REORDER` 和 UDP 延迟的作用范围为通过 NAT 过滤后的 peer UDP。

`run-network-matrix.py` 全部使用 `MODE=normal`，覆盖严格/宽松过滤、两端不同的时延抖动、单侧丢包、连续丢包、UDP 限速与有限队列、共享分配器、随机端口、接入期间短时 UDP 中断、单/双侧 NAT 映射重建、加速映射超时、错峰信令和慢中继。需要 Python 3.11 或更高版本及 smoke 入口使用的 Rust、Go 和本机编译器。

    python3 scripts/nat-sim/run-network-matrix.py --list
    python3 scripts/nat-sim/run-network-matrix.py \
      --scenario asymmetric-jitter --scenario live-nat-rebind \
      --rounds 2 --output /absolute/path/outside/repository/network-evidence

不指定 `--scenario` 时运行全部 17 个配置，默认每个配置 1 轮，总执行数上限 32。每轮独立冷启动，使用固定种子、不做失败重试，通常观察 30 秒；`shared-allocator-continuous` 观察 60 秒，双方各 8 台背景设备持续发流，并为启动及回收预留 120 秒流量上限。输出目录必须是仓库外尚不存在的绝对路径。manifest 记录源代码 commit 与包含未跟踪文件的补丁摘要、参数、实际 NAT seed、原始证据路径、每方向首业务路径和最终活动路径。两端首业务可以分别来自 Direct 和 Relay，收发方向不必使用同一路径，但双方都必须有真实业务 ingress；固定 Direct/Relay 拓扑仍执行原有路径要求。配置为固定种子的模拟场景。

需要验收直连时使用 `--require-direct`：双方最终活动路径必须为 Direct，且最后 2–5 秒内双方各有至少两个新的已验证 Direct 业务包。Relay 可用或历史 Direct 计数不满足该门禁。运行器保留每轮失败，不通过重跑挑选成功结果；产品自身有界重试仍按正常策略执行。

    python3 scripts/nat-sim/run-network-matrix.py \
      --scenario shared-allocator-continuous --rounds 3 --require-direct \
      --output /absolute/path/outside/repository/continuous-direct-evidence

单轮使用 `NETWORK_PROFILE=/absolute/path/profile.json` 传入版本化配置，例如：

```json
{
  "schema_version": 1,
  "A": {"jitter_ms": 40, "impair_stun": true},
  "B": {"rebind_after_ms": 12000}
}
```

| 字段 | 行为与范围 |
| --- | --- |
| `jitter_ms` | 入站 UDP 固定时延上的均匀抖动幅度，0–5000 ms；最终时延不为负 |
| `loss_rate` | 该端独立丢包概率，0–1；省略时沿用 `LOSS` |
| `burst_loss_rate` / `burst_loss_packets` | 每个非连续丢包期间的包触发一段连续丢包的概率，以及每段 2–256 个包的长度 |
| `impair_stun` | 将丢包、抖动和限速同时应用于 STUN 响应；默认关闭 |
| `rate_kbps` | 每端共享入站 UDP 序列化速率，0 表示不限速，最大 1000000；不限制 TCP Relay |
| `queue_limit` / `max_queue_delay_ms` | 等待中的 STUN/UDP 投递任务数上限 1–4096，排队延迟上限 1–30000 ms；超限记录丢弃 |
| `outage_after_ms` / `outage_duration_ms` | 首次 UDP 活动后何时中断该端双向 UDP，以及中断时长；两项同时设置，分别不超过 120000/30000 ms |
| `rebind_after_ms` | 首次 UDP 活动后清除该端 NAT 映射，后续出站重新分配端口；0 关闭，最大 120000 ms |
| `mapping_idle_ms` | 未收到出站刷新时回收映射；0 关闭，启用范围 100–600000 ms |

重建和超时会关闭旧映射、取消其发送工作，延迟投递在执行前重新验证源/目标映射身份。该行为模拟 NAT 状态丢失及端口变化，不模拟手机操作系统的换网通知或真实小区切换。`short-idle-stress` 的 1.5–2 秒超时用于加速触发边界；[RFC 4787](https://www.rfc-editor.org/info/rfc4787/) 对一般 UDP 映射要求至少 2 分钟，不能把这个压力参数当作合规 NAT 的默认值。

正常模式还生成 `business-samples.jsonl` 与 `continuity-evidence.json`。它们使用同一主机单调时钟，要求结束前的观测窗口仍有双向业务增长；发生中断或映射重建时，还要求最后一个故障边界之后持续观察至少 2 秒，并看到新的双向业务。仅有故障前的累计成功不能通过。`established-direct-outage` 和 `established-direct-rebind` 还要求首次故障前双方各有至少两个验证通过的 Direct 业务包；采样读取完成时间必须早于故障，只有连接提升日志或故障后才建立 Direct 都不能满足该前置条件。单轮可用 `NORMAL_REQUIRE_DIRECT_BEFORE_FAULT=1` 启用这一检查。故障后允许正常选择 Direct 或 Relay，最终快照还必须保有活动路径。故障事件、抖动值、重建后的新映射与超时必须在 trace 中实际出现；未触发配置故障会报告 `fault_not_exercised`。背景流的成功和超时分别记录，配置会影响 STUN 时允许实际超时，但所有已请求流仍须结束且至少有成功回复。测试进程与任务健康、原始发送捕获、损坏报文、业务首包时限和完整回收的检查继续保留。

## 活动路径遥测契约（path_telemetry_v1）

daemon 通过异步非阻塞 sidecar 向 Control 上报权威活动路径状态。遥测通道优先复用 WebSocket 信令连接（通过 ready 消息中的 `path_telemetry_v1` 协商），支持 HTTP `POST /api/v1/telemetry/paths` 作为断连或无 WebSocket 时的回退通道。

### 隐私硬约束

遥测载荷严格遵循零泄露原则：
- 严禁携带、传输或持久化任何 IP 地址、端口号、NAT 候选 socket 地址、WireGuard 密钥或数据包载荷。
- 路径状态仅抽象分类为 `direct`、`relay` 或 `none`。
- 状态迁移原因代码严格限制在已定义的封闭集合（如 `initial`、`direct_committed`、`relay_peer_confirmed`、`direct_path_failed`、`relay_path_failed`、`network_generation_advanced` 等）。

### 资源有界性

- daemon 脏节点待发送队列容量硬上限为 128 个 peer；并发状态变迁对同一对端合并最新快照（coalesced）。
- 每条对端观测最多附带最近 32 条本地迁移记录。
- Control 数据库持久化：`peer_path_observations` 表保留每个有向对 `(reporting_device, remote_device, network)` 的最新权威状态；`peer_path_transitions` 历史表对每个有向对保留上限 50 条最近记录，超出自动清理。


### 管理台呈现

Admin Connections 保留遥测的单向语义，不把两个方向合并成新的共享真相。列表与详情可以同时出现 `A → B = direct`、`B → A = relay`。Live Topology 必须先限定到单个网络，默认只把 `fresh=true` 的权威观测画成活动边；stale / reporter offline 记录仅在用户显式开启后以旧观测样式显示。

资源关系图继续使用 `graph_kind=control_relationships`，只表达账号、网络、房间、设备 membership 与 attachment。Connections 和 Relationships 不使用对方的数据推断自己的边。


## Connection Health 派生规则

Control 的 Connection Health 在 Admin 请求时读取当前 `peer_path_observations` 和已保留的 `peer_path_transitions`，按请求窗口派生运维 attention signals；结果不会反写 daemon、不会改变 Direct/Relay 选择，也没有单独的告警生命周期。

默认窗口为 3600 秒，允许 60–86400 秒。固定阈值为每方向窗口内至少 4 次 Direct↔Relay 切换触发 `frequent_path_switching`，至少 3 次显式 Direct/Relay failure reason 触发 `repeated_path_failures`。stable Relay observation 进入正常路径分布统计。

新鲜度继续复用 `DeviceOnlineTTL`：reporter heartbeat 失效时分类为 `reporter_offline`；reporter 仍在线但 observation 过期时分类为 `stale_observation`；fresh observation 且 peer lifecycle 为 `online`、同时没有 committed `current_path` 时分类为 `no_active_path`；`offline` / `unbound` 生命周期没有路径属于预期状态，不产生该信号。validation RTT 统计仅使用 fresh observation 中已有的最近验证样本。

每个方向的 transition history 仍受 50 条上限约束，所以当单方向在查询窗口内产生超过 50 条迁移时，path switch / failure 计数为当前保留历史上的下界。


## 小时级 Connection Trends

Control 额外维护有界的 `connection_metric_hourly` 聚合，用于比每方向 50 条 transition history 更长的运维趋势窗口。该聚合在一条 path telemetry observation 通过现有 session/generation/revision fencing 并与 authoritative snapshot 同一 SQLite 事务提交时累加。

固定规则：

- bucket 固定为 1 小时，按 Control 接收时间归桶；
- 每个 network 每小时最多 1 行，不保存 peer/device 级长期明细；
- 保留 720 小时（30 天），新 telemetry 写入时同步清理更早 bucket；
- accepted、non-resync committed observation 才计 sample；duplicate、rejected、显式 resync，以及 `registration_seq` 前进后的新 owner 首次权威快照重同步都不计趋势；owner advance 仍正常更新 latest snapshot 与 fencing 状态，但不会制造长期 sample、switch 或 failure；
- `direct_observation_samples` / `relay_observation_samples` / `no_path_observation_samples` 按接纳的 observation sample 次数计数；
- `path_switches` 只统计服务端已存在 Direct/Relay snapshot 与新 accepted snapshot 之间真正的 Direct↔Relay 切换；首次 observation、lifecycle-only 变化和 path→none 不算 Direct↔Relay switch；
- failure 只在 transition history 真正记录 `direct_probe_failed`、`direct_path_failed` 或 `relay_path_failed` 时累加，不把重复 snapshot 当新失败；
- validation RTT 使用固定累计 histogram 上界 50、100、250、500、1000、3000、10000 ms，并保留 >10000 ms overflow bucket；超过 24 小时的异常 RTT 输入按无效样本丢弃，不写入 authoritative RTT snapshot 或趋势；API 的 p50/p95 字段是 histogram upper bound，不是精确 percentile，落入 overflow 时不伪造上界。

`GET /admin/api/v1/connection-trends` 默认返回最近 24 个小时 bucket，支持 `window_hours=1..720` 与可选 `network_id`。响应固定补齐缺失小时为零 bucket，因此调用方不需要把“没有 sample”误读成丢失数据。全局查询按小时汇总所有 network，network-scoped 查询只读取指定 network。

当前 Rust telemetry wire 的 `selected_mtu`、`last_direct_latency_ms`、`last_relay_latency_ms` 会在 Control ingestion 时兼容映射到 authoritative snapshot 的 `selected_path_mtu` / 当前路径 validation RTT 字段；该映射只修正字段命名差异，不改变 daemon 的路径决策。
