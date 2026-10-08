package io.p2wlan.mc.common;

import java.io.IOException;

/** Public stable failures, with no local path, resource URL or child stderr. */
public final class NativeBundleException extends IOException {
    public enum Code {
        PLATFORM_UNSUPPORTED, RESOURCE_MISSING, RESOURCE_AMBIGUOUS, RESOURCE_NOT_LOCAL,
        MANIFEST_INVALID, DEVELOPMENT_DISABLED, BINARY_MISMATCH, FILESYSTEM_UNSUPPORTED,
        FILE_IDENTITY_CHANGED, EXTRACTION_TIMEOUT, CAPACITY, IO, START_FAILED,
        CLEANUP_FAILED, CLEANUP_BUSY
    }
    private final Code code;
    public NativeBundleException(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}
