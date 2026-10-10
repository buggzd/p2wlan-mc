package io.p2wlan.mc.common;

import java.io.IOException;

/** Stable local process/IPC failures; never expose raw stderr or credential text. */
public final class AgentException extends IOException {
    public enum Code {
        START_FAILED, INITIALIZATION_FAILED, HANDSHAKE_TIMEOUT, PROTOCOL_MISMATCH, INVALID_REPLY,
        UNEXPECTED_EXIT, CAPACITY, NOT_READY, REQUEST_TIMEOUT, CLOSED, IO,
        INVALID_REQUEST, SCOPE_CHANGED, IDENTITY_EXHAUSTED, REMOTE_REJECTED
    }
    private final Code code;
    private final String remoteCode;
    private final Integer exitCode;
    private final StartupDiagnostic startupDiagnostic;

    public AgentException(Code code) { this(code, null); }
    public AgentException(Code code, String remoteCode) { this(code, remoteCode, null, null); }
    private AgentException(Code code, String remoteCode, Integer exitCode, StartupDiagnostic startupDiagnostic) {
        super(code.name());
        this.code = code;
        this.remoteCode = remoteCode;
        this.exitCode = exitCode;
        this.startupDiagnostic = startupDiagnostic;
    }
    static AgentException exited(int status) { return new AgentException(Code.UNEXPECTED_EXIT, null, status, null); }
    static AgentException startup(String code, StartupDiagnostic detail) {
        return new AgentException(Code.INITIALIZATION_FAILED, code, null, detail);
    }
    public StartupDiagnostic startupDiagnostic() { return startupDiagnostic; }
    public Code code() { return code; }
    public String remoteCode() { return remoteCode; }
    public Integer exitCode() { return exitCode; }
    /** Only closed protocol reason codes or the unsigned OS exit status; no logs. */
    public String diagnostic() {
        if (code == Code.INITIALIZATION_FAILED && AgentMessages.startupCode(remoteCode)) return code.name() + " / " + remoteCode;
        if (exitCode != null) return code.name() + " / " + String.format(java.util.Locale.ROOT, "0x%08X", exitCode);
        return code.name();
    }
}
