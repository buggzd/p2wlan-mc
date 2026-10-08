package io.p2wlan.mc.common;

import java.io.IOException;

public final class WorldHostException extends IOException {
    public enum Code { CAPACITY, WORLD_CHANGED, RUNTIME_CHANGED, BIND_TIMEOUT, BIND_FAILED, INVALID_REPLY, NATIVE_FAILED, CLEANUP_FAILED, INTERRUPTED }
    private final Code code;
    public WorldHostException(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}
