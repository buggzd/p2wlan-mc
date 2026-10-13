package io.p2wlan.mc.game.fixture;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;

/** Test-only server packet effect; contains no client-side game type. */
public final class GameFixtureServerApi {
    private GameFixtureServerApi() {}
    public static void message(ServerPlayer player, Component message) {
        player.connection.send(new ClientboundSystemChatPacket(message, false));
    }
}
