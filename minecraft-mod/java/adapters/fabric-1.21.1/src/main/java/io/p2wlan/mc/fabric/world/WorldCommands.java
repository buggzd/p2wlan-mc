package io.p2wlan.mc.fabric.world;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.p2wlan.mc.common.AgentException;
import io.p2wlan.mc.common.RuntimeView;
import io.p2wlan.mc.common.UiCompletionGate;
import io.p2wlan.mc.common.WorldHost;
import io.p2wlan.mc.common.WorldHostException;
import io.p2wlan.mc.fabric.FabricP2wlan;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

/** Operator intents only. Credentials never appear in Minecraft commands. */
public final class WorldCommands {
    private WorldCommands() {}
    public static void add(LiteralArgumentBuilder<ServerCommandSource> root) {
        root.then(CommandManager.literal("runtime").executes(context -> {
            var client = FabricP2wlan.AGENT.current().orElse(null);
            String phase = client == null ? "NOT_READY" : client.snapshot()
                    .map(value -> RuntimeView.text(value.data(), "phase"))
                    .filter(value -> java.util.Set.of("idle", "starting", "ready").contains(value)).orElse("NOT_READY");
            context.getSource().sendFeedback(() -> Text.literal("P2WLAN native runtime: " + phase + "; not Minecraft login readiness"), false);
            return "ready".equals(phase) ? 1 : 0;
        }));
        root.then(CommandManager.literal("connect").then(CommandManager.argument("room", StringArgumentType.word())
                .executes(context -> connect(context.getSource(), StringArgumentType.getString(context, "room")))));
        root.then(CommandManager.literal("disconnect").executes(context -> disconnect(context.getSource())));
        root.then(CommandManager.literal("host").executes(context -> {
            var source = context.getSource(); var world = FabricWorlds.current(source.getServer());
            if (world == null) { source.sendError(Text.literal("WORLD_CHANGED")); return 0; }
            try { world.host(); source.sendFeedback(() -> Text.literal("P2WLAN world publication starting; use /p2wlan world"), false); return 1; }
            catch (WorldHostException error) { source.sendError(Text.literal(error.code().name())); return 0; }
        }));
        root.then(CommandManager.literal("unhost").executes(context -> {
            var source = context.getSource(); var world = FabricWorlds.current(source.getServer());
            if (world == null) { source.sendError(Text.literal("WORLD_CHANGED")); return 0; }
            world.unhost(); source.sendFeedback(() -> Text.literal("P2WLAN local world revoked"), false); return 1;
        }));
        root.then(CommandManager.literal("world").executes(context -> {
            var source = context.getSource(); var world = FabricWorlds.current(source.getServer());
            WorldHost.Status status = world == null ? null : world.status();
            source.sendFeedback(() -> Text.literal("P2WLAN world: " + (status == null ? "NOT_PUBLISHED" : status.phase()
                    + (status.reason() == null ? "" : " / " + status.reason()))), false);
            return status != null && status.phase() == WorldHost.Phase.HOSTED ? 1 : 0;
        }));
    }
    private static int disconnect(ServerCommandSource source) {
        var world = FabricWorlds.current(source.getServer()); var client = FabricP2wlan.AGENT.current().orElse(null);
        var runtime = client == null ? null : RuntimeView.ready(client).orElse(null);
        if (world == null || runtime == null) { source.sendError(Text.literal("NOT_READY")); return 0; }
        // All command admission is serialized on this exact server thread.
        if (world.commands().busy()) { source.sendError(Text.literal("CAPACITY")); return 0; }
        world.unhost(); // Fence game I/O before requesting native runtime teardown.
        try {
            world.commands().submit(client, "stop", runtime.arguments(), () -> FabricP2wlan.AGENT.isCurrent(client), world::isCurrent,
                    task -> source.getServer().execute(task), (reply, error) -> {
                        if (error != null) source.sendError(Text.literal("World revoked; room stop: " + UiCompletionGate.reason(error)));
                        else source.sendFeedback(() -> Text.literal("P2WLAN world revoked and room runtime stopped; membership retained"), false);
                    });
            return 1;
        } catch (AgentException error) { source.sendError(Text.literal("World revoked; room stop: " + UiCompletionGate.reason(error))); return 0; }
    }
    private static int connect(ServerCommandSource source, String room) {
        var world = FabricWorlds.current(source.getServer()); var client = FabricP2wlan.AGENT.current().orElse(null);
        if (world == null || client == null) { source.sendError(Text.literal("NOT_READY")); return 0; }
        if (!room.matches("([0-9]{8}|room-[a-zA-Z0-9-]{1,120})")) { source.sendError(Text.literal("INVALID_ARGUMENTS")); return 0; }
        JsonObject args = new JsonObject(); args.addProperty("room", room);
        try {
            world.commands().submit(client, "connect_room", args, () -> FabricP2wlan.AGENT.isCurrent(client), world::isCurrent,
                    task -> source.getServer().execute(task), (reply, error) -> {
                        if (error != null) source.sendError(Text.literal(UiCompletionGate.reason(error)));
                        else source.sendFeedback(() -> Text.literal("P2WLAN room runtime starting; world is NOT published"), false);
                    });
            return 1;
        } catch (AgentException error) { source.sendError(Text.literal(UiCompletionGate.reason(error))); return 0; }
    }
}
