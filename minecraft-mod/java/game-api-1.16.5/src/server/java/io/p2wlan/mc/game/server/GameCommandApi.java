package io.p2wlan.mc.game.server;

import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import io.p2wlan.mc.game.server.mixin.ConsoleSourceMixin;
import net.minecraft.network.chat.Component;

/** Minecraft 1.16.5 expects the component at command-dispatch time. */
public final class GameCommandApi {
    private GameCommandApi() {}
    /** 1.16.5 has no withSource identity test; use the original source field. */
    public static boolean isConsole(CommandSourceStack source) {
        return source.getServer().isDedicatedServer() && ((ConsoleSourceMixin) source).p2wlan$source() == source.getServer();
    }
    public static void success(CommandSourceStack source, Supplier<Component> text, boolean broadcast) { source.sendSuccess(text.get(), broadcast); }
}
