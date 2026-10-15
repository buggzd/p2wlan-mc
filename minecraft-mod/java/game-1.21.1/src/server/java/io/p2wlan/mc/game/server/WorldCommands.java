package io.p2wlan.mc.game.server;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.p2wlan.mc.common.AgentException;
import io.p2wlan.mc.common.AgentLifecycle;
import io.p2wlan.mc.common.DedicatedServerController;
import io.p2wlan.mc.common.ConsoleCommandInput;
import io.p2wlan.mc.common.RuntimeView;
import io.p2wlan.mc.common.UiCompletionGate;
import io.p2wlan.mc.common.WorldHost;
import io.p2wlan.mc.common.WorldHostException;
import io.p2wlan.mc.common.WorldSessions;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/** One command frontend. Account secrets are accepted only by the local server console. */
public final class WorldCommands {
    private final AgentLifecycle agent;
    private final Function<MinecraftServer, WorldSessions.World<MinecraftServer>> worlds;
    private final BooleanSupplier transport;
    private WorldCommands(AgentLifecycle agent, Function<MinecraftServer, WorldSessions.World<MinecraftServer>> worlds, BooleanSupplier transport) {
        this.agent = Objects.requireNonNull(agent);
        this.worlds = Objects.requireNonNull(worlds);
        this.transport = Objects.requireNonNull(transport);
    }
    /** Immutable bindings only: Native and WorldSessions keep their original authority. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
            AgentLifecycle agent, Function<MinecraftServer, WorldSessions.World<MinecraftServer>> worlds, BooleanSupplier transport) {
        new WorldCommands(agent, worlds, transport).register(dispatcher);
    }
    private void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("p2wlan").requires(source -> source.hasPermission(4))
                .executes(context -> { say(context.getSource(), "P2WLAN: init / login / register / rooms / create / join / connect / host / unhost / disconnect / restore / logout / status"); return 1; })
                .then(Commands.literal("status").executes(context -> status(context.getSource())));
        add(root);
        for (String operation : List.of("init", "login", "register", "create", "join")) {
            // Parse every field inside the handler: malformed quotes and missing
            // fields produce fixed feedback instead of Brigadier echoing secrets.
            root.then(Commands.literal(operation).requires(GameCommandApi::isConsole)
                    .executes(context -> account(context.getSource(), operation, ""))
                    .then(Commands.argument("input", StringArgumentType.greedyString())
                            .executes(context -> account(context.getSource(), operation, StringArgumentType.getString(context, "input")))));
        }
        for (String operation : List.of("rooms", "logout", "restore"))
            root.then(Commands.literal(operation).executes(context -> dedicated(context.getSource(), operation, null, null)));
        dispatcher.register(root);
    }
    private int status(CommandSourceStack source) {
        var world = worlds.apply(source.getServer());
        var status = agent.status();
        say(source, "P2WLAN helper: " + status.phase() + (status.reason() == null ? "" : " / " + status.reason())
                + "; world transport hooks: " + (transport.getAsBoolean() ? "linked" : "missing"));
        if (world != null && world.dedicated() != null) say(source, world.dedicated().status());
        return status.phase() == AgentLifecycle.Phase.READY ? 1 : 0;
    }
    private void add(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("runtime").executes(context -> {
            var client = agent.current().orElse(null);
            String phase = client == null ? "NOT_READY" : client.snapshot()
                    .map(value -> RuntimeView.text(value.data(), "phase"))
                    .filter(value -> java.util.Set.of("idle", "starting", "ready").contains(value)).orElse("NOT_READY");
            say(context.getSource(), "P2WLAN native runtime: " + phase + "; use /p2wlan host after ready to share the world");
            return "ready".equals(phase) ? 1 : 0;
        }));
        root.then(Commands.literal("connect").then(Commands.argument("room", StringArgumentType.word())
                .executes(context -> connect(context.getSource(), StringArgumentType.getString(context, "room")))));
        root.then(Commands.literal("disconnect").executes(context -> disconnect(context.getSource())));
        root.then(Commands.literal("host").executes(context -> {
            var source = context.getSource(); var world = worlds.apply(source.getServer());
            if (world == null) return fail(source, "WORLD_CHANGED");
            if (world.dedicated() != null) return dedicated(source, "host", null, null);
            try { world.host(); say(source, "正在共享世界"
                    + (source.getServer().isSingleplayer() && !source.getServer().isDedicatedServer() ? "（支持离线账号）" : "")
                    + "；使用 /p2wlan world 查看状态。"); return 1; }
            catch (WorldHostException error) { return fail(source, error.code().name()); }
        }));
        root.then(Commands.literal("unhost").executes(context -> {
            var source = context.getSource(); var world = worlds.apply(source.getServer());
            if (world == null) return fail(source, "WORLD_CHANGED");
            if (world.dedicated() != null) return dedicated(source, "unhost", null, null);
            world.unhost(); say(source, "P2WLAN local world revoked"); return 1;
        }));
        root.then(Commands.literal("world").executes(context -> {
            var source = context.getSource(); var world = worlds.apply(source.getServer());
            WorldHost.Status status = world == null ? null : world.status();
            say(source, "P2WLAN world: " + (status == null ? "NOT_PUBLISHED" : status.phase()
                    + (status.reason() == null ? "" : " / " + status.reason())));
            return status != null && status.phase() == WorldHost.Phase.HOSTED ? 1 : 0;
        }));
    }
    private int account(CommandSourceStack source, String operation, String input) {
        if (!GameCommandApi.isConsole(source)) return fail(source, "LOCAL_CONSOLE_REQUIRED");
        try {
            List<String> fields = ConsoleCommandInput.parse(input, "init".equals(operation) ? 1 : 2);
            return dedicated(source, operation, fields.get(0), fields.size() > 1 ? fields.get(1) : null);
        } catch (AgentException error) { return fail(source, UiCompletionGate.reason(error)); }
    }
    private int dedicated(CommandSourceStack source, String operation, String first, String second) {
        var world = worlds.apply(source.getServer());
        DedicatedServerController server = world == null ? null : world.dedicated();
        if (server == null) return fail(source, "DEDICATED_SERVER_REQUIRED");
        try {
            switch (operation) {
                case "init" -> server.init(first);
                case "login", "register" -> server.login(first, second, "register".equals(operation));
                case "logout" -> server.logout();
                case "rooms" -> server.rooms();
                case "create" -> server.create(first, second);
                case "join" -> server.join(first, second);
                case "connect" -> server.connect(first);
                case "disconnect" -> server.disconnect();
                case "host" -> server.host();
                case "unhost" -> server.unhost();
                case "restore" -> server.restore();
                default -> { return fail(source, "INVALID_REQUEST"); }
            }
            return 1;
        } catch (AgentException error) { return fail(source, UiCompletionGate.reason(error)); }
    }
    private int disconnect(CommandSourceStack source) {
        var world = worlds.apply(source.getServer()); var client = agent.current().orElse(null);
        if (world != null && world.dedicated() != null) return dedicated(source, "disconnect", null, null);
        var runtime = client == null ? null : RuntimeView.active(client).orElse(null);
        if (world == null || runtime == null) return fail(source, "NOT_READY");
        // All command admission is serialized on this exact server thread.
        if (world.commands().busy()) return fail(source, "CAPACITY");
        world.unhost(); // Fence game I/O before requesting native runtime teardown.
        try {
            world.commands().submit(client, "stop", runtime.arguments(), () -> agent.isCurrent(client), world::isCurrent,
                    task -> source.getServer().execute(task), (reply, error) -> {
                        if (error != null) fail(source, "World revoked; room stop: " + UiCompletionGate.reason(error));
                        else say(source, "P2WLAN world revoked and room runtime stopped; membership retained");
                    });
            return 1;
        } catch (AgentException error) { return fail(source, "World revoked; room stop: " + UiCompletionGate.reason(error)); }
    }
    private int connect(CommandSourceStack source, String room) {
        var world = worlds.apply(source.getServer()); var client = agent.current().orElse(null);
        if (world != null && world.dedicated() != null) return dedicated(source, "connect", room, null);
        if (world == null || client == null) return fail(source, "NOT_READY");
        if (!room.matches("([0-9]{8}|room-[a-zA-Z0-9-]{1,120})")) return fail(source, "INVALID_ARGUMENTS");
        JsonObject args = new JsonObject(); args.addProperty("room", room);
        try {
            world.commands().submit(client, "connect_room", args, () -> agent.isCurrent(client), world::isCurrent,
                    task -> source.getServer().execute(task), (reply, error) -> {
                        if (error != null) fail(source, UiCompletionGate.reason(error));
                        else say(source, "P2WLAN room runtime starting; after ready, use /p2wlan host to share the world");
                    });
            return 1;
        } catch (AgentException error) { return fail(source, UiCompletionGate.reason(error)); }
    }
    private static void say(CommandSourceStack source, String text) {
        GameCommandApi.success(source, () -> Component.literal(text), false);
    }
    private static int fail(CommandSourceStack source, String reason) { source.sendFailure(Component.literal(reason)); return 0; }
}
