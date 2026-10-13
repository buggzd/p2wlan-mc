package io.p2wlan.mc.game.server;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.p2wlan.mc.common.AgentException;
import io.p2wlan.mc.common.AgentLifecycle;
import io.p2wlan.mc.common.RuntimeView;
import io.p2wlan.mc.common.UiCompletionGate;
import io.p2wlan.mc.common.WorldHost;
import io.p2wlan.mc.common.WorldHostException;
import io.p2wlan.mc.common.WorldSessions;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/** Operator intents only. Credentials never appear in Minecraft commands. */
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
                .then(Commands.literal("status").executes(context -> {
                    var status = agent.status();
                    GameCommandApi.success(context.getSource(), () -> Component.literal("P2WLAN helper: " + status.phase()
                            + (status.reason() == null ? "" : " / " + status.reason())
                            + "; world transport hooks: " + (transport.getAsBoolean() ? "linked" : "missing")), false);
                    return status.phase() == io.p2wlan.mc.common.AgentLifecycle.Phase.READY ? 1 : 0;
                }));
        add(root); dispatcher.register(root);
    }
    private void add(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("runtime").executes(context -> {
            var client = agent.current().orElse(null);
            String phase = client == null ? "NOT_READY" : client.snapshot()
                    .map(value -> RuntimeView.text(value.data(), "phase"))
                    .filter(value -> java.util.Set.of("idle", "starting", "ready").contains(value)).orElse("NOT_READY");
            GameCommandApi.success(context.getSource(), () -> Component.literal("P2WLAN native runtime: " + phase + "; use /p2wlan host after ready to share the world"), false);
            return "ready".equals(phase) ? 1 : 0;
        }));
        root.then(Commands.literal("connect").then(Commands.argument("room", StringArgumentType.word())
                .executes(context -> connect(context.getSource(), StringArgumentType.getString(context, "room")))));
        root.then(Commands.literal("disconnect").executes(context -> disconnect(context.getSource())));
        root.then(Commands.literal("host").executes(context -> {
            var source = context.getSource(); var world = worlds.apply(source.getServer());
            if (world == null) { source.sendFailure(Component.literal("WORLD_CHANGED")); return 0; }
            try { world.host(); GameCommandApi.success(source, () -> Component.literal("正在共享世界"
                    + (source.getServer().isSingleplayer() && !source.getServer().isDedicatedServer() ? "（支持离线账号）" : "")
                    + "；使用 /p2wlan world 查看状态。"), false); return 1; }
            catch (WorldHostException error) { source.sendFailure(Component.literal(error.code().name())); return 0; }
        }));
        root.then(Commands.literal("unhost").executes(context -> {
            var source = context.getSource(); var world = worlds.apply(source.getServer());
            if (world == null) { source.sendFailure(Component.literal("WORLD_CHANGED")); return 0; }
            world.unhost(); GameCommandApi.success(source, () -> Component.literal("P2WLAN local world revoked"), false); return 1;
        }));
        root.then(Commands.literal("world").executes(context -> {
            var source = context.getSource(); var world = worlds.apply(source.getServer());
            WorldHost.Status status = world == null ? null : world.status();
            GameCommandApi.success(source, () -> Component.literal("P2WLAN world: " + (status == null ? "NOT_PUBLISHED" : status.phase()
                    + (status.reason() == null ? "" : " / " + status.reason()))), false);
            return status != null && status.phase() == WorldHost.Phase.HOSTED ? 1 : 0;
        }));
    }
    private int disconnect(CommandSourceStack source) {
        var world = worlds.apply(source.getServer()); var client = agent.current().orElse(null);
        var runtime = client == null ? null : RuntimeView.active(client).orElse(null);
        if (world == null || runtime == null) { source.sendFailure(Component.literal("NOT_READY")); return 0; }
        // All command admission is serialized on this exact server thread.
        if (world.commands().busy()) { source.sendFailure(Component.literal("CAPACITY")); return 0; }
        world.unhost(); // Fence game I/O before requesting native runtime teardown.
        try {
            world.commands().submit(client, "stop", runtime.arguments(), () -> agent.isCurrent(client), world::isCurrent,
                    task -> source.getServer().execute(task), (reply, error) -> {
                        if (error != null) source.sendFailure(Component.literal("World revoked; room stop: " + UiCompletionGate.reason(error)));
                        else GameCommandApi.success(source, () -> Component.literal("P2WLAN world revoked and room runtime stopped; membership retained"), false);
                    });
            return 1;
        } catch (AgentException error) { source.sendFailure(Component.literal("World revoked; room stop: " + UiCompletionGate.reason(error))); return 0; }
    }
    private int connect(CommandSourceStack source, String room) {
        var world = worlds.apply(source.getServer()); var client = agent.current().orElse(null);
        if (world == null || client == null) { source.sendFailure(Component.literal("NOT_READY")); return 0; }
        if (!room.matches("([0-9]{8}|room-[a-zA-Z0-9-]{1,120})")) { source.sendFailure(Component.literal("INVALID_ARGUMENTS")); return 0; }
        JsonObject args = new JsonObject(); args.addProperty("room", room);
        try {
            world.commands().submit(client, "connect_room", args, () -> agent.isCurrent(client), world::isCurrent,
                    task -> source.getServer().execute(task), (reply, error) -> {
                        if (error != null) source.sendFailure(Component.literal(UiCompletionGate.reason(error)));
                        else GameCommandApi.success(source, () -> Component.literal("P2WLAN room runtime starting; after ready, use /p2wlan host to share the world"), false);
                    });
            return 1;
        } catch (AgentException error) { source.sendFailure(Component.literal(UiCompletionGate.reason(error))); return 0; }
    }
}
