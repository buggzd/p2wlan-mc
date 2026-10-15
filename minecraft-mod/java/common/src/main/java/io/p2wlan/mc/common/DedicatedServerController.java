package io.p2wlan.mc.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Server-thread workflow owner for operator intents. Native owns account,
 * membership and runtime facts; WorldHost owns publication. One pending request,
 * no queue/mutation replay/worker. Restoration has one 40-second readiness budget.
 * Every request, completion, saved intent and host action checks its exact scope.
 */
public final class DedicatedServerController implements AutoCloseable {
    interface Session {
        boolean current();
        boolean owns(AgentReply reply);
        JsonObject snapshot();
        CompletableFuture<AgentReply> request(String operation, JsonObject arguments, BooleanSupplier fence);
    }
    interface Host {
        boolean current();
        void host(RuntimeView runtime) throws WorldHostException;
        void unhost();
        WorldHost.Status status();
        default boolean publicationAvailable() { return true; }
    }
    private record Pending(Session session, long revision, CompletableFuture<AgentReply> future, Consumer<AgentReply> completion) {}
    private record Account(String server, String user) {}
    private final Host world;
    private final Supplier<Session> sessions;
    private final Runnable start;
    private final BooleanSupplier onThread;
    private final DedicatedServerSettings store;
    private final Consumer<String> output;
    private final LongSupplier clock;
    private DedicatedServerSettings.Value settings;
    private String reason;
    private String phase = "WAITING";
    private boolean bootHandled;
    private volatile boolean closed;
    private volatile long revision;
    private Pending pending;
    private Session waitingSession;
    private RuntimeView waitingRuntime;
    private boolean runtimeObserved;
    private long deadline;
    private boolean watchingHost;

    DedicatedServerController(WorldSessions.World<?> world, AgentLifecycle agent, BooleanSupplier onThread, Consumer<String> output) {
        this(new Host() {
            public boolean current() { return world.isCurrent(); }
            public void host(RuntimeView runtime) throws WorldHostException { world.host(runtime); }
            public void unhost() { world.unhost(); }
            public WorldHost.Status status() { return world.status(); }
            public boolean publicationAvailable() { return world.publicationAvailable(); }
        }, () -> agent.current().map(client -> (Session) new Session() {
            public boolean current() { return agent.isCurrent(client); }
            public boolean owns(AgentReply reply) { return client.isCurrent(reply); }
            public JsonObject snapshot() { return client.snapshot().map(StatusSnapshot::data).orElse(null); }
            public CompletableFuture<AgentReply> request(String op, JsonObject args, BooleanSupplier fence) { return client.request(op, args, fence); }
        }).orElse(null), agent::start, onThread,
                new DedicatedServerSettings(agent.profilePath().resolveSibling("p2wlan-server.json")), output, System::nanoTime);
    }
    DedicatedServerController(Host world, Supplier<Session> sessions, Runnable start, BooleanSupplier onThread,
            DedicatedServerSettings store, Consumer<String> output, LongSupplier clock) {
        this.world = world; this.sessions = sessions; this.start = start; this.onThread = onThread;
        this.store = store; this.output = output; this.clock = clock;
        var loaded = store.load(); settings = loaded.value();
        if (!"OK".equals(loaded.reason())) { reason = loaded.reason(); bootHandled = true; phase = "FAILED"; }
    }
    public boolean busy() { return pending != null; }
    public String status() {
        return "P2WLAN 恢复: " + phase + (reason == null ? "" : " / " + reason)
                + "; 保存房间: " + (settings.room().isEmpty() ? "未选择" : settings.room())
                + "; 自动共享: " + (settings.share() ? "开启" : "关闭");
    }
    private void scope() throws AgentException {
        if (closed || !world.current() || !onThread.getAsBoolean()) throw failure("WORLD_CHANGED");
    }
    private Session session() throws AgentException {
        Session session = sessions.get();
        if (session == null || !session.current()) throw new AgentException(AgentException.Code.NOT_READY);
        return session;
    }
    private Session available() throws AgentException {
        scope();
        if (busy()) throw new AgentException(AgentException.Code.CAPACITY);
        return session();
    }
    private void begin() {
        bootHandled = true; waitingRuntime = null; waitingSession = null; watchingHost = false;
        revision++; reason = null;
    }
    private Session admit() throws AgentException {
        Session session = available(); begin();
        return session;
    }
    private boolean current(Session session, long token) { return !closed && world.current() && revision == token && session.current(); }
    private void save(DedicatedServerSettings.Value value, Session session) throws AgentException {
        scope();
        if (session != null && !session.current()) throw failure("PROCESS_CHANGED");
        try { store.save(value); settings = value; }
        catch (AgentException error) {
            waitingRuntime = null; waitingSession = null; watchingHost = false;
            phase = "FAILED"; reason = UiCompletionGate.reason(error); throw error;
        }
    }
    private static AgentException failure(String code) { return new AgentException(AgentException.Code.REMOTE_REJECTED, code); }
    private static JsonObject args(String key, String value) { var args = new JsonObject(); args.addProperty(key, value); return args; }
    private static void text(String value, int maximum) throws AgentException {
        if (value == null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > maximum
                || value.codePoints().anyMatch(Character::isISOControl)) throw failure("INVALID_ARGUMENTS");
    }
    private static void password(String value, boolean room) throws AgentException {
        text(value, room ? 72 : 256);
        if (room && value.getBytes(StandardCharsets.UTF_8).length < 8) throw failure("INVALID_ARGUMENTS");
    }
    private Account account(Session session) throws AgentException {
        JsonObject snapshot = session.snapshot();
        var projected = snapshot == null ? null : snapshot.get("account");
        if (AccountPresentation.state(projected) != AccountPresentation.State.SAVED_SESSION) throw failure("AUTH_REQUIRED");
        var value = projected.getAsJsonObject();
        var account = new Account(value.get("server").getAsString(), value.get("user_id").getAsString());
        // An account mutation reply may precede the corresponding status event.
        // A prior user's snapshot cannot bind a newly selected room to that user.
        if (!settings.server().isEmpty() && !settings.server().equals(account.server())
                || !settings.userId().isEmpty() && !settings.userId().equals(account.user())) throw failure("ACCOUNT_CHANGED");
        return account;
    }
    private void submit(Session session, String operation, JsonObject arguments, Consumer<AgentReply> completion) throws AgentException {
        scope();
        if (pending != null) throw new AgentException(AgentException.Code.CAPACITY);
        long token = revision;
        if (!current(session, token)) throw failure("PROCESS_CHANGED");
        pending = new Pending(session, token, session.request(operation, arguments, () -> current(session, token)), completion);
    }
    private void failed(Throwable error) {
        boolean selecting = "SELECTING".equals(phase);
        waitingRuntime = null; waitingSession = null; watchingHost = false;
        reason = UiCompletionGate.reason(error); phase = "FAILED";
        output.accept("P2WLAN: " + reason + "；使用 p2wlan status 查看状态。");
        if (selecting) output.accept("P2WLAN 房间操作已停止；使用 p2wlan rooms 确认结果后再选择房间，不会自动重复创建或加入。");
    }
    private void afterStop(Session session, Runnable action) throws AgentException {
        world.unhost();
        // A connect reply may arrive before its status event. Read through the
        // serialized native owner so disconnect cannot miss that new runtime.
        phase = "STOPPING";
        submit(session, "status", new JsonObject(), reply -> run(() -> {
            JsonObject snapshot = reply.data();
            String runtime = RuntimeView.text(snapshot, "runtime"), state = RuntimeView.text(snapshot, "phase");
            if (runtime != null && ("ready".equals(state) || "starting".equals(state))) {
                submit(session, "stop", args("runtime", runtime), stopped -> action.run());
            } else if ("idle".equals(state)) action.run();
            else throw new AgentException(AgentException.Code.INVALID_REPLY);
        }));
    }
    private void run(Action action) { try { action.run(); } catch (AgentException error) { failed(error); } }
    @FunctionalInterface private interface Action { void run() throws AgentException; }

    public void init(String server) throws AgentException {
        String endpoint = DedicatedServerSettings.canonicalServer(server);
        scope(); if (busy()) throw new AgentException(AgentException.Code.CAPACITY);
        Session session = sessions.get();
        if (session != null && session.current()) {
            JsonObject snapshot = session.snapshot();
            if (snapshot == null || !"idle".equals(RuntimeView.text(snapshot, "phase"))) throw failure("RUNTIME_BUSY");
        }
        bootHandled = true; revision++; waitingRuntime = null; waitingSession = null;
        save(endpoint.equals(settings.server()) ? settings : new DedicatedServerSettings.Value(endpoint, "", "", false), session);
        reason = null; phase = "IDLE"; start.run();
        if (endpoint.startsWith("http://")) output.accept("P2WLAN 当前 Control 使用 HTTP；账号和房间密码将通过明文连接发送。");
        output.accept("P2WLAN 已初始化；使用 p2wlan login <账号> <密码> 登录，或 p2wlan register <账号> <密码> 注册。");
    }
    public void login(String identifier, String password, boolean register) throws AgentException {
        text(identifier, 256); password(password, false);
        if (settings.server().isEmpty()) throw failure("SERVER_NOT_INITIALIZED");
        Session session = admit(); String endpoint = settings.server();
        afterStop(session, () -> run(() -> {
            var arguments = args("server", endpoint); arguments.addProperty("identifier", identifier); arguments.addProperty("password", password);
            phase = "AUTHENTICATING";
            submit(session, register ? "register" : "login", arguments, reply -> run(() -> {
                if (AccountPresentation.state(reply.data()) != AccountPresentation.State.SAVED_SESSION) throw new AgentException(AgentException.Code.INVALID_REPLY);
                String user = RuntimeView.text(reply.data(), "user_id"), server = RuntimeView.text(reply.data(), "server");
                boolean same = server.equals(settings.server()) && user.equals(settings.userId());
                save(new DedicatedServerSettings.Value(server, user, same ? settings.room() : "", same && settings.share()), session);
                phase = "IDLE"; output.accept("P2WLAN 登录会话已保存；使用 p2wlan rooms、p2wlan create 或 p2wlan join 选择房间。");
            }));
        }));
    }
    public void logout() throws AgentException {
        Session session = admit();
        save(new DedicatedServerSettings.Value(settings.server(), "", "", false), session);
        afterStop(session, () -> run(() -> submit(session, "logout", new JsonObject(), reply -> {
            phase = "IDLE"; output.accept("P2WLAN 已退出登录，自动恢复已关闭。");
        })));
    }
    public void rooms() throws AgentException {
        scope(); if (busy()) throw new AgentException(AgentException.Code.CAPACITY);
        Session session = session();
        account(session);
        submit(session, "rooms", new JsonObject(), reply -> run(() -> {
            var catalog = AccountCatalog.parse(reply);
            output.accept("P2WLAN 房间数: " + catalog.rooms().size());
            for (var room : catalog.rooms()) output.accept("P2WLAN 房间: " + room.code() + " / " + room.id());
        }));
    }
    public void create(String name, String password) throws AgentException { membership("create_room", "name", name, password); }
    public void join(String code, String password) throws AgentException {
        if (code == null || !code.matches("[0-9]{8}")) throw failure("INVALID_ARGUMENTS");
        membership("join_room", "code", code, password);
    }
    private void membership(String operation, String key, String value, String password) throws AgentException {
        text(value, 256); password(password, true);
        Session session = available(); Account account = account(session); begin();
        afterStop(session, () -> run(() -> {
            JsonObject arguments = args(key, value); arguments.addProperty("password", password); phase = "SELECTING";
            submit(session, operation, arguments, reply -> run(() -> {
                var room = room(reply);
                save(new DedicatedServerSettings.Value(account.server(), account.user(), room.id(), settings.share()), session);
                output.accept("P2WLAN 房间已保存: " + room.code() + "；正在连接。");
                connectInternal(session, account, room.id());
            }));
        }));
    }
    public void connect(String selected) throws AgentException {
        if (selected == null || !selected.matches("([0-9]{8}|room-[a-zA-Z0-9-]{1,120})")) throw failure("INVALID_ARGUMENTS");
        Session session = available(); Account account = account(session); begin();
        afterStop(session, () -> run(() -> connectInternal(session, account, selected)));
    }
    private static AccountCatalog.Room room(AgentReply reply) throws AgentException {
        JsonObject data = reply.data();
        if (!data.has("room") || !data.get("room").isJsonObject()) throw new AgentException(AgentException.Code.INVALID_REPLY);
        var projected = new JsonObject(); var rooms = new JsonArray(); rooms.add(data.get("room")); projected.add("rooms", rooms);
        return AccountCatalog.parse(new AgentReply(reply.owner(), reply.id(), projected)).rooms().get(0);
    }
    private void connectInternal(Session session, Account account, String selected) throws AgentException {
        phase = "CONNECTING";
        submit(session, "connect_room", args("room", selected), reply -> run(() -> {
            var room = room(reply); String id = RuntimeView.text(reply.data(), "runtime");
            if (id == null || !id.matches("[a-f0-9]{32}")) throw new AgentException(AgentException.Code.INVALID_REPLY);
            save(new DedicatedServerSettings.Value(account.server(), account.user(), room.id(), settings.share()), session);
            waitingRuntime = new RuntimeView(reply.owner(), id); waitingSession = session;
            runtimeObserved = false;
            deadline = clock.getAsLong() + Duration.ofSeconds(40).toNanos(); phase = "WAITING_RUNTIME";
        }));
    }
    public void disconnect() throws AgentException {
        scope(); if (busy()) throw new AgentException(AgentException.Code.CAPACITY);
        bootHandled = true; revision++; waitingRuntime = null; waitingSession = null; watchingHost = false;
        Session session = sessions.get();
        if (session != null && !session.current()) session = null;
        save(new DedicatedServerSettings.Value(settings.server(), settings.userId(), "", false), session);
        world.unhost();
        Runnable complete = () -> { phase = "IDLE"; reason = null; output.accept("P2WLAN 已断开房间，自动恢复已关闭；房间成员资格保留。"); };
        if (session == null) complete.run(); else afterStop(session, complete);
    }
    public void host() throws AgentException {
        Session session = available();
        account(session);
        if (settings.room().isEmpty()) throw failure("ROOM_UNKNOWN");
        WorldHost.Status published = world.status();
        if (published != null && published.phase() == WorldHost.Phase.HOSTED) {
            save(new DedicatedServerSettings.Value(settings.server(), settings.userId(), settings.room(), true), session);
            phase = "HOSTED"; reason = null; output.accept("P2WLAN 世界已共享，自动共享设置已保存。"); return;
        }
        if (waitingRuntime != null) {
            // The accepted connect reply already selected the exact runtime.
            // A lagging idle snapshot must not erase that pending selection.
            save(new DedicatedServerSettings.Value(settings.server(), settings.userId(), settings.room(), true), waitingSession);
            output.accept("P2WLAN 自动共享已保存；当前房间就绪并完成旧共享清理后将发布世界。");
            return;
        }
        JsonObject snapshot = session.snapshot(); String runtime = snapshot == null ? null : RuntimeView.text(snapshot, "runtime");
        if (runtime == null || !runtime.matches("[a-f0-9]{32}")) throw failure("RUNTIME_NOT_READY");
        begin();
        // A current snapshot carries its process owner in RuntimeView; the
        // connect reply is not required when an operator re-enables sharing.
        submit(session, "status", new JsonObject(), reply -> run(() -> {
            if (!runtime.equals(RuntimeView.text(reply.data(), "runtime"))) throw failure("RUNTIME_CHANGED");
            save(new DedicatedServerSettings.Value(settings.server(), settings.userId(), settings.room(), true), session);
            waitingRuntime = new RuntimeView(reply.owner(), runtime); waitingSession = session;
            runtimeObserved = false;
            deadline = clock.getAsLong() + Duration.ofSeconds(40).toNanos(); phase = "WAITING_RUNTIME";
        }));
    }
    public void unhost() throws AgentException {
        scope(); if (busy()) throw new AgentException(AgentException.Code.CAPACITY);
        bootHandled = true; revision++; waitingRuntime = null; waitingSession = null; watchingHost = false;
        world.unhost();
        save(new DedicatedServerSettings.Value(settings.server(), settings.userId(), settings.room(), false), null);
        phase = "IDLE"; reason = null; output.accept("P2WLAN 已取消共享；重启仍会连接已保存房间。");
    }
    public void restore() throws AgentException {
        Session session = available();
        if (settings.room().isEmpty()) throw failure("ROOM_UNKNOWN");
        Account account = account(session);
        if (!account.server().equals(settings.server()) || !account.user().equals(settings.userId())) throw failure("ACCOUNT_CHANGED");
        begin();
        afterStop(session, () -> run(() -> connectInternal(session, account, settings.room())));
    }
    public void tick() {
        if (closed || !world.current() || !onThread.getAsBoolean()) return;
        if (pending != null && pending.future().isDone()) {
            Pending item = pending; pending = null;
            if (!current(item.session(), item.revision())) { failed(failure("PROCESS_CHANGED")); return; }
            try {
                AgentReply reply = item.future().join();
                if (!item.session().owns(reply)) throw failure("PROCESS_CHANGED");
                item.completion().accept(reply);
            } catch (RuntimeException | AgentException error) { failed(error); }
        }
        if (!bootHandled && pending == null) {
            if (reason != null) { bootHandled = true; failed(failure(reason)); return; }
            Session session = sessions.get();
            if (session != null && session.current() && session.snapshot() != null) {
                bootHandled = true;
                if (!settings.room().isEmpty()) run(this::restore);
                else { phase = "IDLE"; output.accept("P2WLAN 已就绪；使用 p2wlan init <Control地址> 开始配置。"); }
            }
        }
        if (waitingRuntime != null && pending == null) {
            Session session = waitingSession; RuntimeView selected = waitingRuntime;
            JsonObject snapshot = session.snapshot(); String state = snapshot == null ? null : RuntimeView.text(snapshot, "phase");
            String runtime = snapshot == null ? null : RuntimeView.text(snapshot, "runtime");
            if (selected.runtime().equals(runtime)) runtimeObserved = true;
            if (!session.current() || clock.getAsLong() - deadline >= 0 || runtimeObserved && !selected.runtime().equals(runtime)) {
                waitingRuntime = null; waitingSession = null;
                String failure = snapshot == null ? null : RuntimeView.text(snapshot, "failure");
                failed(failure(!session.current() ? "PROCESS_CHANGED"
                        : runtimeObserved && !selected.runtime().equals(runtime) ? "RUNTIME_CHANGED"
                        : "WAITING_PUBLICATION".equals(phase) ? "WORLD_CLEANUP_TIMEOUT"
                        : failure != null && failure.matches("[A-Z][A-Z0-9_]{0,47}") ? failure : "RUNTIME_NOT_READY"));
            } else if ("ready".equals(state) && selected.runtime().equals(runtime)) {
                if (settings.share()) {
                    // Revocation is immediate, but the old guard/target keeps
                    // its publication slot until exact cleanup has completed.
                    // Retain this intent within its original deadline.
                    if (!world.publicationAvailable()) { phase = "WAITING_PUBLICATION"; return; }
                    try {
                        if (!session.current() || !world.current()) throw failure("PROCESS_CHANGED");
                        world.host(selected); watchingHost = true; phase = "HOSTING";
                    } catch (WorldHostException error) {
                        // Capacity rejection occurs before any host work is
                        // submitted; it can also belong to a retiring world.
                        if (error.code() == WorldHostException.Code.CAPACITY) { phase = "WAITING_PUBLICATION"; return; }
                        failed(error);
                    } catch (AgentException error) { failed(error); }
                } else { phase = "CONNECTED"; output.accept("P2WLAN 房间已连接；使用 p2wlan host 共享并保存自动共享设置。"); }
                waitingRuntime = null; waitingSession = null;
            }
        }
        if (watchingHost) {
            WorldHost.Status status = world.status();
            if (status != null && status.phase() != WorldHost.Phase.STARTING) {
                watchingHost = false;
                if (status.phase() == WorldHost.Phase.HOSTED) { phase = "HOSTED"; output.accept("P2WLAN 世界已共享；服务器重启后将自动恢复房间和共享。"); }
                else failed(failure(status.reason() == null ? "WORLD_CHANGED" : status.reason()));
            }
        }
        if ("HOSTED".equals(phase)) {
            WorldHost.Status status = world.status();
            if (status == null || status.phase() != WorldHost.Phase.HOSTED) {
                failed(failure(status == null || status.reason() == null ? "WORLD_CHANGED" : status.reason()));
            }
        }
    }
    @Override public void close() { closed = true; revision++; pending = null; waitingRuntime = null; waitingSession = null; watchingHost = false; }
}
