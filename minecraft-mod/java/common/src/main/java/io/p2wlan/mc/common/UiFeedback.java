package io.p2wlan.mc.common;

/** Presentation only: stable failure codes, never exception text/server bodies.
 * An ambiguous registration receipt must not claim the account was not created. */
public final class UiFeedback {
    private UiFeedback() {}
    public static String operation(String operation) {
        return switch (operation) {
            case "login" -> "登录";
            case "register" -> "注册";
            case "logout" -> "退出账号";
            case "rooms" -> "读取房间";
            case "create_room" -> "创建房间";
            case "join_room" -> "加入房间";
            case "leave_room" -> "离开房间";
            case "connect_room" -> "连接房间";
            case "room_peers" -> "读取成员";
            case "discover" -> "发现游戏";
            default -> "操作";
        };
    }
    public static String working(String operation) { return operation(operation) + "：处理中，请勿重复提交"; }
    public static String failure(String operation, Throwable error) {
        String code = UiCompletionGate.reason(error);
        if (!code.matches("[A-Z][A-Z0-9_]{0,47}")) code = "INVALID_REPLY";
        boolean uncertain = switch (code) {
            case "CONTROL_UNAVAILABLE", "CONTROL_TIMEOUT", "CONTROL_RESPONSE", "REQUEST_TIMEOUT", "IO",
                    "UNEXPECTED_EXIT", "CLOSED", "INVALID_REPLY", "SCOPE_CHANGED" -> true;
            default -> false;
        };
        String description;
        if ("register".equals(operation) && ("PROFILE_IO".equals(code) || "PROFILE_INVALID".equals(code))) {
            description = "服务端已返回成功，但本机会话保存失败；请用登录重试，不要再次注册。";
        } else if ("register".equals(operation) && uncertain) {
            description = "结果未确认；是否已建号未知。请勿重复注册，可用登录核实。";
        } else {
            description = switch (code) {
                case "AUTH_REQUIRED" -> "需要登录、登录被拒绝或会话已失效；请在账号页登录。";
                case "CONTROL_UNAVAILABLE" -> "服务器连接或响应失败；不代表本地保存失败。";
                case "CONTROL_TIMEOUT", "REQUEST_TIMEOUT" -> "等待响应超时；操作结果可能尚未确认。";
                case "CONTROL_RESPONSE", "INVALID_REPLY" -> "响应不符合协议，未获得成功确认。";
                case "INVALID_ARGUMENTS", "INVALID_REQUEST" -> "请检查输入格式及服务端要求。";
                case "CONTROL_CONFLICT" -> "服务器报告操作冲突或资源已存在。";
                case "CONTROL_RATE_LIMITED" -> "请求过于频繁，请稍后再试。";
                case "PROFILE_IO", "PROFILE_INVALID" -> "本机会话读写失败；未确认会话已保存。";
                case "FORBIDDEN" -> "服务器拒绝此操作的权限。";
                case "NOT_READY", "CLOSED", "SCOPE_CHANGED" -> "助手或页面状态已变化，请重新打开页面。";
                case "CAPACITY" -> "已有操作正在处理，请勿重复提交。";
                default -> "操作未成功；请检查下方错误码。";
            };
        }
        return operation(operation) + "：" + description + " [" + code + "]";
    }
}
