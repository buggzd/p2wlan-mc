## 变更

<!-- 只描述最终行为和边界，不粘贴开发过程。 -->

## 状态与不变量

<!-- 不涉及连接、并发、数据面或持久化状态时写“不涉及”。 -->

- 状态所有者：
- fencing identity（generation/revision/incarnation/session/owner token）：
- 新增或改变的队列、锁、任务、缓存及其上界：
- handoff 前失败 / handoff 后失败 / delivery uncertain 的处理：
- Direct/Relay、rekey、replay、MTU、room authorization 影响：

## 验证

<!-- 列出实际运行的命令和结果，不要把未运行的检查写成通过。 -->

- [ ] `python3 scripts/docs/verify_repository.py`
- [ ] `python3 scripts/quality/check_code_health.py --base-ref main`
- [ ] `python3 -m unittest discover -s scripts/quality/tests -p 'test_*.py'`
- [ ] `bash -n scripts/install-server.sh scripts/deploy-server.sh scripts/p2wlan-server`
- [ ] `python3 -m unittest discover -s minecraft-mod/tests -p 'test_*.py'`
- [ ] `python3 minecraft-mod/tools/native.py fmt --all --check` 与受影响 Rust 测试
- [ ] `python3 minecraft-mod/tools/java.py test` 与受影响加载器构建
- [ ] Universal JAR／HMCL ZIP 校验（涉及打包时）
- [ ] 需要时完成真实 Minecraft、Windows/HMCL、Direct/Relay 或公网 NAT 验证
- [ ] 上游依赖有改动时运行相应 Go／Flutter／网络核心检查

## 故障与回滚

- 新失败模式：
- 可观测入口 / reason code：
- 回滚是否需要处理持久化状态、协议兼容或发布资产：

## 未覆盖边界

<!-- 明确仍需要人工、设备或公网环境验证的内容；没有则写“无”。 -->
