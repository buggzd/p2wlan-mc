package io.p2wlan.mc.common;

/** Pure local failure presentation; no IPC/health request is needed to use it. */
public final class DiagnosticPresentation {
    private DiagnosticPresentation() {}
    public static String explanation(ModDiagnostics.Failure failure) {
        if (failure == null) return "此页面可在助手启动失败时使用。";
        return switch (failure.reason()) {
            case "INITIALIZATION_FAILED / PROFILE_IO" -> "本机配置文件操作失败，不是账号或房间登录失败。";
            case "INITIALIZATION_FAILED / PROFILE_INVALID" -> "配置目录或会话文件未通过安全 / 格式检查；不要放宽权限。";
            case "INITIALIZATION_FAILED / PROFILE_BUSY" -> "本机配置正在被另一个助手占用。";
            case "RESOURCE_MISSING", "BINARY_MISMATCH" -> "原生助手缺失或校验失败，请核对 Mod 安装包。";
            case "PLATFORM_UNSUPPORTED" -> "当前系统 / Java 架构不在此安装包支持范围内。";
            default -> "请复制下方诊断；错误码不能单独确定根因。";
        };
    }
    public static boolean canOpenLog(ModDiagnostics.Snapshot snapshot) {
        return snapshot.directory() != null && (snapshot.diskState().equals("WRITING") || snapshot.diskState().equals("SAVED"));
    }
    public static String diskLabel(ModDiagnostics.Snapshot snapshot) {
        return canOpenLog(snapshot) ? "日志已启用 · 自动轮转"
                : "文件日志不可用（" + snapshot.diskState() + "）；仍可复制内存诊断";
    }
}
