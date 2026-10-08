package io.p2wlan.mc.common;

import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Immutable local endpoint capability. Not a room or network-session owner. */
public final class LocalEndpoint {
    public static final int PRELUDE_BYTES = 40;
    public static final byte READY = 1;
    private static final byte[] MAGIC = {'P', '2', 'M', 'C', 0, 0, 0, 1};
    private final InetSocketAddress address;
    private final byte[] key;

    private LocalEndpoint(InetSocketAddress address, byte[] key) {
        this.address = address;
        this.key = key.clone();
    }
    static LocalEndpoint generate(InetSocketAddress address, SecureRandom random) {
        byte[] key = new byte[32];
        random.nextBytes(key);
        return new LocalEndpoint(address, key);
    }
    public static LocalEndpoint from(InetSocketAddress address, String keyHex) throws WorldGuardException {
        validateAddress(address);
        if (keyHex == null || !keyHex.matches("[a-f0-9]{64}")) {
            throw new WorldGuardException(WorldGuardException.Code.KEY_MISMATCH);
        }
        return new LocalEndpoint(address, HexFormat.of().parseHex(keyHex));
    }
    static void validateAddress(InetSocketAddress address) throws WorldGuardException {
        if (address == null || address.isUnresolved() || address.getPort() <= 0
                || !address.getAddress().getHostAddress().equals("127.0.0.1")) {
            throw new WorldGuardException(WorldGuardException.Code.INVALID_TARGET);
        }
    }
    public InetSocketAddress address() { return address; }
    /** For the private Agent IPC only; never place this in process arguments or logs. */
    public String keyHex() { return HexFormat.of().formatHex(key); }
    public byte[] prelude() {
        byte[] bytes = new byte[PRELUDE_BYTES];
        System.arraycopy(MAGIC, 0, bytes, 0, MAGIC.length);
        System.arraycopy(key, 0, bytes, MAGIC.length, key.length);
        return bytes;
    }
    boolean accepts(byte[] prelude) { return MessageDigest.isEqual(prelude(), prelude); }
    @Override public String toString() { return "LocalEndpoint[capability=redacted]"; }
}
