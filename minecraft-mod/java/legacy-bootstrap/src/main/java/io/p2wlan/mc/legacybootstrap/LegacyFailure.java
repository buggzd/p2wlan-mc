package io.p2wlan.mc.legacybootstrap;

/** Stable artifact-loader failures; no path, account or remote text is emitted. */
public final class LegacyFailure extends RuntimeException {
    public enum Code {
        SOURCE_NOT_LOCAL, SOURCE_IDENTITY, INDEX_CONTRACT, CAPACITY, ARCHIVE_CONTRACT,
        OWNER_NAMESPACE, CONTENT_IDENTITY, RESOURCE_MISSING, RUNTIME_PROFILE, EXTRACTION_IO,
        RESOURCE_IO, OWNER_CHANGED, PROVENANCE
    }
    private final Code code;
    public LegacyFailure(Code code) { super("P2WLAN_LEGACY_" + code.name()); this.code = code; }
    public LegacyFailure(Code code, Throwable cause) { super("P2WLAN_LEGACY_" + code.name(),cause); this.code = code; }
    public Code code() { return code; }
}
