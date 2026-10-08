package io.p2wlan.mc.common;

import java.nio.charset.StandardCharsets;

/** Immutable remote-world selection, not an authorization grant or live status.
 * Proxy admission reacquires the original daemon's peer permission. */
public record GameService(RuntimeView runtime, String peer, int port, String identity) {
    public GameService {
        if (runtime == null || !validPeer(peer) || port < 1 || port > 65535
                || identity == null || !identity.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("INVALID_GAME_SERVICE");
        }
    }
    static boolean validPeer(String peer) {
        return peer != null && !peer.isEmpty() && peer.getBytes(StandardCharsets.UTF_8).length <= 256
                && peer.codePoints().noneMatch(value -> Character.isISOControl(value) || value >= 0xd800 && value <= 0xdfff);
    }
}
