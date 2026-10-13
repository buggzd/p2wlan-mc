package io.p2wlan.mc.game.fixture;

import net.minecraft.Util;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Dedicated-safe 1.16.5 fixture reply; no client-only class references. */
public final class GameFixtureServerApi {
    private GameFixtureServerApi() {}
    public static void message(ServerPlayer player, Component message) { player.sendMessage(message, ChatType.SYSTEM, Util.NIL_UUID); }
}
