package io.p2wlan.mc.game.server;

import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/** Version-specific command feedback; the caller keeps its original intent scope. */
public final class GameCommandApi {
    private GameCommandApi() {}
    /** withSource preserves object identity only for the exact original source. */
    public static boolean isConsole(CommandSourceStack source) {
        return source.getServer().isDedicatedServer() && source.withSource(source.getServer()) == source;
    }
    public static void success(CommandSourceStack source, Supplier<Component> message, boolean broadcast) {
        source.sendSuccess(message, broadcast);
    }
}
