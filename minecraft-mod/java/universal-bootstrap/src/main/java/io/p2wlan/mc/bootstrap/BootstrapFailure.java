package io.p2wlan.mc.bootstrap;

/** Startup failures carry a fixed code, never resource paths or metadata text. */
public final class BootstrapFailure extends IllegalStateException {
    public enum Code {
        LOADER_MISSING, LOADER_AMBIGUOUS, GAME_VERSION_UNSUPPORTED,
        VERSION_METADATA_MISSING, VERSION_METADATA_INVALID, VERSION_METADATA_TOO_LARGE,
        RESOURCE_ACCESS_FAILED, RESOURCE_NOT_LOCAL,
        MIXIN_PACKAGE_INVALID, DISPATCH_UNBOUND, DISPATCH_CHANGED, CLIENT_SIDE_UNAVAILABLE
    }
    private final Code code;
    public BootstrapFailure(Code code) {
        super("P2WLAN_UNIVERSAL_" + code.name());
        this.code = code;
    }
    public Code code() { return code; }
}
