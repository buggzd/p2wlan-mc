# Field Gate Runbook — dual-CGNAT 一次 10 轮跑，三件事同时验收

> 面向现场（Mini + Air 双机、真实 dual-CGNAT、control server 可达）。
> 目标：用**一次** strict-acceptance 10 轮 A/B 序列，同时落成三件事的证据：
>
> | Gate | 事项 | 判定（`field-gate-verify.sh` 自动） |
> |---|---|---|
> | **A** | Gate4 10/10 | strict-acceptance 10 轮全部 `ok` |
> | **B** | liveness normal 防假阳性 | 全程 **0 次** `outbound_liveness verdict="blocked"`（正常 egress 下 CGNAT 黑洞不得被误标防火墙） |
> | **C** | R1 p2v2 下发 | 每轮 `/status` peer 的 `nat_type` 非空值**全部**为 `p2v2:`，且 a→b、b→a 双向都观测到 |
>
> **为什么一次跑覆盖三件事**：Gate A 是 smoke harness 的 acceptance 判据；Gate B 与 C 是
> daemon 在**同一条** 10 轮跑里各自落的日志（liveness 结构化 event）和 `/status` 诊断
> （peer `nat_type`），不需要额外场景。liveness 默认开（`udp_liveness_enabled`
> serde `default_true`），R1 的 p2v2 label 随 `update_endpoint` 下发进 control server 并
> 回到对端 `/status`——三者天然同场。

---

## 1. 现场硬前提（缺一即 abort，别在现场排错）

1. **双机 dual-CGNAT**：Mini 与 Air 各自在真实 CGNAT 后，公网出口**双向**（含对端 IP）
   可达——这是 Gate B/C 有意义的前提（EIM/APD 判定依赖对端能观察到本端 p2v2 label）。
2. **control server 可达**：`REMOTE_CONTROL_URL`（https）从双机都能连，注册/取对端 endpoint
   正常。R1 的 p2v2 label 走的就是这条控制面。
3. **legacy baseline 二进制**在本地可用（`DAEMON_BIN_OVERRIDE`）——A/B 序列用它跑
   compat-baseline 3/3，strict 阶段用**当前树** build。
4. 当前树能编译：先执行 `cargo check -p p2wlan-daemon`。

> 此门禁的测试条件为两端各处于真实 CGNAT 后。两端使用 public IP 时，先切换到
> dual-CGNAT 环境，再运行 10 轮序列；结果绑定本轮的 NAT 条件。

## 2. 跑法（A/B 序列，标准 10/10）

`run-ab-sequence.sh` 已经封装了 compat(3) → strict-preflight(3) → strict-acceptance(10) 的
完整链与自动 retry。**填好变量直接跑**，别手拆三步（fingerprint 锁依赖同一 AB_SEQUENCE_DIR）：

```bash
cd <repo-root>

export REMOTE_CONTROL_URL="https://<control-host>:<port>"   # 现场 control server（https）
export NETWORK_ID="<network-or-tenant>"                     # 双机一致
export ARTIFACT_ROOT="$HOME/p2wlan-artifacts"               # 跑完的 BASE_DIR 都在这
export AB_SEQUENCE_DIR="$ARTIFACT_ROOT/seq-$(date +%s)"
export DAEMON_BIN_OVERRIDE="<path-to-legacy-v0.1.116-daemon>" # compat baseline 二进制
mkdir -p "$ARTIFACT_ROOT" "$AB_SEQUENCE_DIR"

# 双机现场参数（mini-air-smoke.sh 必需，见其头部 env 清单）
export AIR_HOST="<air-ip>" AIR_USER="<air-user>" AIR_SSH_KEY="$HOME/.ssh/<key>"
export AIR_KNOWN_HOSTS_FILE="$HOME/.ssh/known_hosts"
export ALLOW_STAGING_TEST=1 ALLOW_REMOTE_RESTART=1         # 授权真实重启 Air daemon
# REAL_TUN=1 仅当要生产数据面证据（availability 模式）；strict-acceptance 需 REAL_TUN=1：
#   （见 mini-air-smoke.sh：acceptance requires REAL_TUN=1）

scripts/dual-end/run-ab-sequence.sh
```

- **strict-acceptance 要求 `REAL_TUN=1`**（mock/`--validate-overlay-only` 不算生产数据面证据）。
  若只跑 non-TUN 的 functional-direct，那是对应 compat 口径，不是 Gate4 10/10 的 strict 口径——
  按你要验收的口径选 `ACCEPTANCE_MODE`，但 Gate A 的 10/10 语义指向 strict-acceptance。
- 跑完 `run-ab-sequence.sh` 打印 `A/B sequence complete: compat 3/3 + preflight 3/3 +
  acceptance 10/10` 即序列成功。`BASE_DIR` 是 `$ARTIFACT_ROOT/mini-air-$RUN_ID`（RUN_ID 在
  smoke 日志首行），`AB_SEQUENCE_DIR` 里是 `sequence-results.json`。

> **fingerprint 锁提示**：`field-gate-verify.sh` 是**只读**分析器，
> 不改 harness/parser，故不会使 A/B 序列的 fingerprint 失效。反过来**不要**为了加验证去改
> `mini-air-smoke.sh` 或 `strict-direct-parser.py`（改了 `harness_sha256`/`parser_sha256`，
> 已 lock 的序列会 `sequence-invalid`）。

## 3. 验收（一键三 gate）

```bash
# BASE_DIR = $ARTIFACT_ROOT 下最新的 mini-air-*（跑完 ls 确认）
BASE_DIR=$(ls -dt "$ARTIFACT_ROOT"/mini-air-* | head -1)

scripts/dual-end/field-gate-verify.sh "$BASE_DIR" "$AB_SEQUENCE_DIR"
```

输出三段的 PASS/FAIL 与逐轮明细；`exit 0` = 三 gate 全绿。

## 4. 逐 gate 判读 + 失败时看什么

**Gate A（10/10）FAIL** — `sequence-results.json` 里某轮 `ok=false`。
- 看该轮 `round-N/evidence.log`（Direct validation lifecycle / traversal plan / relay proof
  三段）+ `round-metrics.tsv` 的 `strict_convergence_ms`。
- 常见根因是**环境**（CGNAT 双向黑洞、control 429、relay 确认超时），不是 R1——
  R1 只改 `filtering_behavior` 来源，不改 direct 提升/relay 保底（见 R1b 交付说明）。
- 10/10 在 CGNAT 下**不可靠达成**是已知环境特性（~17-24% 冷启动黑洞轮），连续几轮 FAIL
  优先怀疑网络，别直接判 R1 回归。

**Gate B（liveness normal）FAIL** — 出现 `verdict="blocked"`。
- 定位轮次：`field-gate-verify.sh` 的 `blocked_rounds=` 列出。
- 打开该轮 `round-N/node-?.log`，看 `outbound_liveness` 行的 `targets=[...]`：
  - 若**全部** targets 全失败 → 真被拦（现场 egress 有问题），非 daemon 假阳性。
  - 若**部分** target 通、部分黑洞（典型 CGNAT）却判 blocked → 真·误判，daemon bug，回报。
- 判据：正常网络「全部目标×全部重试全失败」应≈0 误判（多目标冗余）。单看某轮 blocked
  要先排除 CGNAT 黑洞把默认 targets 全打穿的可能。

**Gate C（p2v2 下发）FAIL** — 双向没都到、或出现非 `p2v2:` 值。
- `field-gate-verify.sh` 的 `non-p2v2 samples:` 列出具体轮/端/值。
- `a_sees_p2v2`/`b_sees_p2v2` 有一边为 0 → 那一侧的 `update_endpoint` 没把 p2v2 送出去，
  或 control server 没回给对端。查 control server 里该 device 的 `nat_type` 存储字段
  的 `nat_type` 是否为 `p2v2:`。
- 出现 `p2:linear` 之类 legacy 值 → 该 daemon 不是当前树 build（`DAEMON_BIN_OVERRIDE`
  串了，或 strict 阶段误用了 legacy 二进制——strict **必须** unset `DAEMON_BIN_OVERRIDE`）。

## 5. 结果范围

- Gate A 读取 `sequence-results.json` 中的 strict-acceptance 记录，要求 10 轮全部 `ok`。
- Gate B 统计本轮日志中的 `outbound_liveness` 事件，要求 `blocked` 次数为 0；
  同时报告 `ok` 与 `unknown` 次数。
- Gate C 读取 poll JSON 中非空的 `nat_type`，要求全部使用 `p2v2:` 前缀，
  且两个方向各至少有一个样本。映射与过滤字段的含义见[网络参考](../../docs/reference/networking.md#nat-限制)。

---

## 结果保存

保存 `$BASE_DIR` 中的逐轮 evidence、metrics 和 poll JSON，以及
`$AB_SEQUENCE_DIR/sequence-results.json`。将 `field-gate-verify.sh` 的三项判定和
逐轮明细附到 PR、Issue 或内部验收记录。
