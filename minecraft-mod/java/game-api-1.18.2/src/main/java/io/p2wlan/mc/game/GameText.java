package io.p2wlan.mc.game;

import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;

/** Exact pre-1.19 component construction; no retained presentation state. */
public final class GameText {
    private GameText() {}
    public static MutableComponent literal(String value) { return new TextComponent(value); }
    public static MutableComponent translatable(String key, Object... arguments) { return new TranslatableComponent(key, arguments); }
}
