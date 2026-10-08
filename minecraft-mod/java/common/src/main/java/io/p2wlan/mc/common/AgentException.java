package io.p2wlan.mc.common;

import java.io.IOException;

/** Stable local process/IPC failures; never expose raw stderr or credential text. */
public final class AgentException extends IOException {
    public enum Code {
        START_FAILED, HANDSHAKE_TIMEOUT, PROTOCOL_MISMATCH, INVALID_REPLY,
        UNEXPECTED_EXIT, CAPACITY, NOT_READY, REQUEST_TIMEOUT, CLOSED, IO,
        INVALID_REQUEST, SCOPE_CHANGED, IDENTITY_EXHAUSTED, REMOTE_REJECTED
    }
    private final Code code;
    private final String remoteCode;

    public AgentException(Code code) { this(code, null); }
    public AgentException(Code code, String remoteCode) {
        super(code.name());
        this.code = code;
        this.remoteCode = remoteCode;
    }
    public Code code() { return code; }
    public String remoteCode() { return remoteCode; }
}
