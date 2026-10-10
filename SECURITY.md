# P2WLAN Minecraft 安全报告

本仓库的 Minecraft Mod 及其原生集成尚未完成独立安全审计，不声明安全认证。加密实现、包校验、CI、摘要清单与本机游戏测试不能替代安全审计、实机验证或部署者的风险评估。

## 非公开报告

Mod、Java／Fabric、随包原生 Agent、私有配置或本地桥接相关漏洞应报告给**本仓库维护者**，而不是默认转交原 P2WLAN 仓库。

如果本仓库启用了 GitHub Private vulnerability reporting，请使用本仓库 Security 页的私密报告入口；未启用时，先通过维护者提供的非公开联系方式确认安全接收渠道。不要在公开 Issue／PR 中提交可利用细节、真实基础设施或秘密。底层上游组件的问题可在确认归属后协调上游，避免将 Mod 适配问题误报为上游已支持功能。

报告只包含复现所需的最小信息：

- Mod 版本或源码提交、Minecraft／Fabric 版本、操作系统与架构；
- 受影响组件和包内原生目标，涉及构建时附执行核验方式；
- 最小复现步骤、预期／实际结果、影响与建议修复方向；
- 必要的稳定错误码和脱敏证据。

不要粘贴 `config/p2wlan-private/`、账号密码、JWT、设备凭据、Relay 票据、私钥、未脱敏日志或业务载荷。维护者确认和修复前，请勿公开披露利用细节。

## 当前信任边界

- 使用可信 Control 与 HTTPS；HTTP 风险提示不等于加密保护。
- 本地能力验证和用户权限隔离不防御恶意 Mod、同系统用户代码、受修改 JVM 或管理员。
- 摘要证明字节一致性，不是签名，也不证明发布者身份或游戏兼容性。
- Mod 不关闭专用服务器原有公网监听，不改变原版身份认证或自动接受游戏条款。
- Windows 目标编译／Wine build-info 不证明真实 Windows 文件权限、HMCL 或远程游戏安全性。

具体边界见[Mod 技术参考](minecraft-mod/README.md)、[底层安全模型](docs/explanation/security-model.md)和[隐私说明](PRIVACY.md)。
