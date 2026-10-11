package io.p2wlan.mc.common;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Sole local-world scope owner shared by loaders. The native runtime remains
 * the authorization/path owner. Game objects are opaque identity tokens. */
public final class WorldSessions<S> implements AutoCloseable {
    public static final class World<S> {
        private final WorldSessions<S> owner;
        private final S server;
        private final BooleanSupplier onThread;
        private final WorldHost.TargetFactory targets;
        private final AtomicBoolean live = new AtomicBoolean(true);
        private final ScheduledTaskGate intents = new ScheduledTaskGate(Duration.ofSeconds(3));
        private final UiCompletionGate commands = new UiCompletionGate();
        private volatile WorldHost host;
        private World(WorldSessions<S> owner, S server, BooleanSupplier onThread, WorldHost.TargetFactory targets) {
            this.owner = owner; this.server = server; this.onThread = onThread; this.targets = targets;
        }
        public S server() { return server; }
        public boolean isCurrent() { return live.get() && owner.current.get() == this; }
        public ScheduledTaskGate intents() { return intents; }
        public UiCompletionGate commands() { return commands; }
        public WorldHost.Status status() { WorldHost value = host; return value == null ? null : value.status(); }
        public void host() throws WorldHostException {
            host(RuntimeView.ready(owner.agent.current().orElse(null)).orElse(null));
        }
        /** Never reacquire a replacement runtime for a queued game intent. */
        public void host(RuntimeView runtime) throws WorldHostException {
            if (!isCurrent() || !onThread.getAsBoolean()) throw new WorldHostException(WorldHostException.Code.WORLD_CHANGED);
            if (host != null && !host.isReleased()) throw new WorldHostException(WorldHostException.Code.CAPACITY);
            AgentClient client = owner.agent.current().orElse(null);
            if (runtime == null || !runtime.isCurrent(client)) throw new WorldHostException(WorldHostException.Code.RUNTIME_CHANGED);
            host = WorldHost.start(client, runtime, () -> isCurrent() && owner.agent.isCurrent(client), targets);
        }
        public void unhost() { WorldHost value = host; if (value != null) value.revoke(); }
        public boolean hosted() {
            WorldHost value = host;
            return isCurrent() && value != null && value.status().phase() == WorldHost.Phase.HOSTED && value.isCurrent();
        }
        public void tick() { WorldHost value = host; if (value != null && !value.isCurrent()) value.revoke(); }
        private void retire() { live.set(false); intents.close(); commands.close(); unhost(); }
    }
    private final AgentLifecycle agent;
    private final AtomicReference<World<S>> current = new AtomicReference<>();
    public WorldSessions(AgentLifecycle agent) {
        if (agent == null) throw new IllegalArgumentException("INVALID_WORLD_AGENT");
        this.agent = agent;
    }
    /** Called by a loader's serialized server-started event, never a worker. */
    public World<S> started(S server, BooleanSupplier onThread, WorldHost.TargetFactory targets) {
        if (server == null || onThread == null || targets == null) throw new IllegalArgumentException("INVALID_WORLD_SCOPE");
        World<S> next = new World<>(this, server, onThread, targets);
        World<S> old = current.getAndSet(next);
        if (old != null) old.retire();
        return next;
    }
    public World<S> current(S server) {
        World<S> value = current.get();
        return value != null && value.server == server && value.isCurrent() ? value : null;
    }
    /** Exact scope identity prevents late cleanup retiring a successor. */
    public void stopped(World<S> world) {
        if (world != null && world.owner == this) { world.retire(); current.compareAndSet(world, null); }
    }
    @Override public void close() { World<S> world = current.get(); stopped(world); }
}
