package io.p2wlan.mc.common;

import java.io.IOException;

/** Stable protocol failures; never classify errors by log messages. */
public final class ProtocolException extends IOException {
    public enum Code { FRAME_SIZE, TRUNCATED_FRAME, INVALID_UTF8, INVALID_JSON, DEPTH, CAPACITY }
    private final Code code;

    public ProtocolException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() { return code; }
}
