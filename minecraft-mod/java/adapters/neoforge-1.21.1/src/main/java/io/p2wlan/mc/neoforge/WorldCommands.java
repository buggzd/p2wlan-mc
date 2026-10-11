package io.p2wlan.mc.neoforge;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.p2wlan.mc.common.AgentException;
import io.p2wlan.mc.common.RuntimeView;
import io.p2wlan.mc.common.UiCompletionGate;
import io.p2wlan.mc.common.WorldHost;
import io.p2wlan.mc.common.WorldHostException;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/** Operator intents only. Credentials never appear in Minecraft commands. */
public final class WorldCommands {
    private WorldCommands() {}
    public static void register(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("p2wlan").requires(source -> source.hasPermission(4))
                .then(Commands.literal("status").executes(context -> {
                    var status = NeoForgeP2wlan.AGENT.status();
                    context.getSource().sendSuccess(() -> Component.literal("P2WLAN helper: " + status.phase()
                            + (status.reason() == null ? "" : " / " + status.reason())
                            + "; world transport hooks: " + (NeoForgeP2wlan.transportSupported() ? "linked" : "missing")), false);
                    return status.phase() == io.p2wlan.mc.common.AgentLifecycle.Phase.READY ? 1 : 0;
                }));
        add(root); dispatcher.register(root);
    }
    private static void add(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("runtime").executes(context -> {
            var client = NeoForgeP2wlan.AGENT.current().orElse(null);
            String phase = client == null ? "NOT_READY" : client.snapshot()
                    .map(value -> RuntimeView.text(value.data(), "phase"))
                    .filter(value -> java.util.Set.of("idle", "starting", "ready").contains(value)).orElse("NOT_READY");
            context.getSource().sendSuccess(() -> Component.literal("P2WLAN native runtime: " + phase + "; use /p2wlan host after ready to share the world"), false);
            return "ready".equals(phase) ? 1 : 0;
        }));
        root.then(Commands.literal("connect").then(Commands.argument("room", StringArgumentType.word())
                .executes(context -> connect(context.getSource(), StringArgumentType.getString(context, "room")))));
        root.then(Commands.literal("disconnect").executes(context -> disconnect(context.getSource())));
        root.then(Commands.literal("host").executes(context -> {
            var source = context.getSource(); var world = NeoForgeP2wlan.world(source.getServer());
            if (world == null) { source.sendFailure(Component.literal("WORLD_CHANGED")); return 0; }
            try { world.host(); source.sendSuccess(() -> Component.literal("P2WLAN world publication starting; use /p2wlan world"), false); return 1; }
            catch (WorldHostException error) { source.sendFailure(Component.literal(error.code().name())); return 0; }
        }));
        root.then(Commands.literal("unhost").executes(context -> {
            var source = context.getSource(); var world = NeoForgeP2wlan.world(source.getServer());
            if (world == null) { source.sendFailure(Component.literal("WORLD_CHANGED")); return 0; }
            world.unhost(); source.sendSuccess(() -> Component.literal("P2WLAN local world revoked"), false); return 1;
        }));
        root.then(Commands.literal("world").executes(context -> {
            var source = context.getSource(); var world = NeoForgeP2wlan.world(source.getServer());
            WorldHost.Status status = world == null ? null : world.status();
            source.sendSuccess(() -> Component.literal("P2WLAN world: " + (status == null ? "NOT_PUBLISHED" : status.phase()
                    + (status.reason() == null ? "" : " / " + status.reason()))), false);
            return status != null && status.phase() == WorldHost.Phase.HOSTED ? 1 : 0;
        }));
    }
    private static int disconnect(CommandSourceStack source) {
        var world = NeoForgeP2wlan.world(source.getServer()); var client = NeoForgeP2wlan.AGENT.current().orElse(null);
        var runtime = client == null ? null : RuntimeView.active(client).orElse(null);
        if (world == null || runtime == null) { source.sendFailure(Component.literal("NOT_READY")); return 0; }
        // All command admission is serialized on this exact server thread.
        if (world.commands().busy()) { source.sendFailure(Component.literal("CAPACITY")); return 0; }
        world.unhost(); // Fence game I/O before requesting native runtime teardown.
        try {
            world.commands().submit(client, "stop", runtime.arguments(), () -> NeoForgeP2wlan.AGENT.isCurrent(client), world::isCurrent,
                    task -> source.getServer().execute(task), (reply, error) -> {
                        if (error != null) source.sendFailure(Component.literal("World revoked; room stop: " + UiCompletionGate.reason(error)));
                        else source.sendSuccess(() -> Component.literal("P2WLAN world revoked and room runtime stopped; membership retained"), false);
                    });
            return 1;
        } catch (AgentException error) { source.sendFailure(Component.literal("World revoked; room stop: " + UiCompletionGate.reason(error))); return 0; }
    }
    private static int connect(CommandSourceStack source, String room) {
        var world = NeoForgeP2wlan.world(source.getServer()); var client = NeoForgeP2wlan.AGENT.current().orElse(null);
        if (world == null || client == null) { source.sendFailure(Component.literal("NOT_READY")); return 0; }
        if (!room.matches("([0-9]{8}|room-[a-zA-Z0-9-]{1,120})")) { source.sendFailure(Component.literal("INVALID_ARGUMENTS")); return 0; }
        JsonObject args = new JsonObject(); args.addProperty("room", room);
        try {
            world.commands().submit(client, "connect_room", args, () -> NeoForgeP2wlan.AGENT.isCurrent(client), world::isCurrent,
                    task -> source.getServer().execute(task), (reply, error) -> {
                        if (error != null) source.sendFailure(Component.literal(UiCompletionGate.reason(error)));
                        else source.sendSuccess(() -> Component.literal("P2WLAN room runtime starting; after ready, use /p2wlan host to share the world"), false);
                    });
            return 1;
        } catch (AgentException error) { source.sendFailure(Component.literal(UiCompletionGate.reason(error))); return 0; }
    }
}
