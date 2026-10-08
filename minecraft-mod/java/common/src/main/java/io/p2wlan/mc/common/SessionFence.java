package io.p2wlan.mc.common;

import java.util.Optional;

/** Pure snapshot admission. This class never computes network/path state. */
public final class SessionFence {
    private String owner;
    private StatusSnapshot snapshot;

    public synchronized void begin(String nextOwner) {
        if (nextOwner == null || !nextOwner.matches("[a-f0-9]{32}")) {
            throw new IllegalArgumentException("INVALID_OWNER");
        }
        owner = nextOwner;
        snapshot = null;
    }

    public synchronized boolean accept(StatusSnapshot next) {
        if (owner == null || !owner.equals(next.owner())
                || (snapshot != null && next.revision() <= snapshot.revision())) return false;
        snapshot = next;
        return true;
    }

    public synchronized void close(String expectedOwner) {
        if (owner != null && owner.equals(expectedOwner)) {
            owner = null;
            snapshot = null;
        }
    }

    public synchronized Optional<StatusSnapshot> snapshot() { return Optional.ofNullable(snapshot); }
}
