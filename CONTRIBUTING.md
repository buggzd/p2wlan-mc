# 贡献 P2WLAN Minecraft

## 修改边界

先阅读 [仓库 AI 约束](AGENTS.md)、[仓库 skill](skills/p2wlan-repository/SKILL.md)、[文档入口](docs/README.md)和[工程质量与架构边界](docs/explanation/engineering-quality.md)。修改前确认工作区已有改动，并阅读当前 docs/ 下的全部 Markdown 文档及受影响实现、测试、脚本和 workflow。

Minecraft 集成、原生适配、构建工具、测试和 Mod 产品文档归属 `minecraft-mod/`，还须遵守其中的 [AGENTS.md](minecraft-mod/AGENTS.md)。Java 与 Rust 工程独立于根 Cargo workspace；复用本 checkout 的上游实现，不维护一份 vendored 网络核心，也不为实现 Mod 改写上游客户端／服务端／workflow。根 README 与文档导航负责介绍本仓库产品，不取代 Mod 实现契约。

- 玩家操作文档写入 `minecraft-mod/docs/`；协议、资源上界和测试入口维护在 [Mod 技术参考](minecraft-mod/README.md)。
- 根 `docs/` 保留底层客户端、服务端与共同工程规则，不能将其平台支持、TUN 或发布规则直接当成 Mod 的行为。
- 实现变更同时更新直接受影响的测试与稳定文档。命令、路径、参数和默认值以当前源码或固定发布包为准。
- 开发过程放在 PR／Issue，版本绑定证据放在 Release 资产。不要提交一次性验收台账、个人基础设施、日志、凭据、运行身份或构建产物。

## 网络与生命周期规则

网络、session、room authorization 和 Direct／Relay 的权威状态仍属于原生 P2WLAN 核心。Java 只消费带 process／runtime／generation／revision 等身份围栏的快照，不能维护第二份路径状态机。

跨异步边界保存状态时携带 fencing identity，并在实际发送、提交、游戏线程呈现或持久化前重新验证。新增队列、锁、缓存、重试和后台任务必须有容量、deadline、取消条件和结构化失败原因。正常生命周期竞争与外部输入失败不得用 panic 或字符串日志驱动控制流。

有效拆分隔离状态所有权、副作用、协议编码或可独立测试的决策逻辑；不要只为了缩短文件机械抽象。复杂度棘轮约束源码体积与增长，历史热点相对基线只能缩小。

## 本地检查

以下命令均从仓库根目录运行。所有修改至少执行：

```sh
python3 scripts/docs/verify_repository.py
python3 scripts/quality/check_code_health.py --base-ref main
python3 -m unittest discover -s scripts/quality/tests -p 'test_*.py'
bash -n scripts/install-server.sh scripts/deploy-server.sh scripts/p2wlan-server
```

Mod 修改按受影响组件补充：

```sh
python3 -m unittest discover -s minecraft-mod/tests -p 'test_*.py'
python3 minecraft-mod/tools/java.py test
python3 minecraft-mod/tools/native.py fmt --all -- --check
python3 minecraft-mod/tools/native.py test --workspace --locked
python3 minecraft-mod/tools/native.py clippy --workspace --all-targets -- -D warnings
```

Java 测试构建需要 JDK 21；Python 工具使用 Python 3.11+。Fabric 构建、原生资源校验、互操作、游戏 fixture、账号控制台和 HMCL 产物按 [Mod 技术参考](minecraft-mod/README.md)的前提执行。首次依赖获取不要盲目使用 `--offline`。

说明类文本遵循[仓库表达规则](AGENTS.md#说明类文本的表达)：围绕目的写清行为、成果和依据，用范围、条件与数据限定表述。UI 给出当前状态和下一步操作，指标说明来源、单位、窗口与样本；必要限制在相关句子中简述。

正式原生打包要求干净、已提交的 checkout；有意使用开发资源时必须显式标记，普通包和 HMCL 打包不能绕过源码身份检查。真实 Minecraft fixture 需要图形／系统环境及显式游戏条款同意。

只有确实修改底层组件时，再按范围执行根工程检查：

```sh
cargo fmt --all --check
cargo test --workspace --all-targets -- --test-threads=1
(cd server && go vet ./... && go test ./... -count=1)
```

Flutter、真实 TUN、公网 NAT、双端设备、Windows／Linux 实机和独立安全审计均单独记录覆盖范围。没有运行的项目必须在交付和 PR 中明确说明。

## 提交与 PR

提交信息使用 `feat:中文` 格式，`feat:` 后不加空格。已验证的小改动独立提交；PR 描述最终行为、实际验证和未覆盖边界，不把过程报告复制成仓库文档。

连接、并发、持久化或数据面修改必须说明状态所有者、fencing identity、资源上界、transport handoff 失败阶段、Direct／Relay／rekey／replay／MTU／room authorization 影响，以及超时、取消、重复、过期和竞争测试。

发布相关改动说明准确源码 SHA、原生输入摘要、最终产物摘要、嵌入版本、目标与实际执行核验方式。Windows 交叉构建记录编译结果与 Wine build-info 执行结果；实机或跨机器验收记录所用设备、平台和游戏场景。
