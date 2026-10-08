package io.p2wlan.mc.common;

import java.io.IOException;

/** Stable local/world failures, never exception-message-driven control flow. */
public final class WorldGuardException extends IOException {
    public enum Code {
        INVALID_TARGET, CAPACITY, KEY_MISMATCH, HANDSHAKE_TIMEOUT,
        IDLE_TIMEOUT, TARGET_REJECTED, WORLD_CLOSED, CLOSE_TIMEOUT, INTERRUPTED, IO
    }
    private final Code code;
    public WorldGuardException(Code code) { super(code.name()); this.code = code; }
    public WorldGuardException(Code code, Throwable cause) { super(code.name(), cause); this.code = code; }
    public Code code() { return code; }
}
