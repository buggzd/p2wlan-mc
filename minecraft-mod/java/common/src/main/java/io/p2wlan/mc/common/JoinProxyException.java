package io.p2wlan.mc.common;

import java.io.IOException;

public final class JoinProxyException extends IOException {
    public enum Code { CAPACITY, ATTEMPT_CHANGED, RUNTIME_CHANGED, INVALID_REPLY, NATIVE_FAILED, CLEANUP_FAILED, INTERRUPTED }
    private final Code code;
    public JoinProxyException(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}
