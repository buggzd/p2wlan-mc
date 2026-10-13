package io.p2wlan.mc.game.server;

import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/** Minecraft 1.16.5 expects the component at command-dispatch time. */
public final class GameCommandApi {
    private GameCommandApi() {}
    public static void success(CommandSourceStack source, Supplier<Component> text, boolean broadcast) { source.sendSuccess(text.get(), broadcast); }
}
