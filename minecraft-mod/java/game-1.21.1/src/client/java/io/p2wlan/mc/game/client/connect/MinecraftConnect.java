package io.p2wlan.mc.game.client.connect;

import io.netty.channel.ChannelFuture;
import io.p2wlan.mc.common.LocalEndpoint;
import io.p2wlan.mc.transport.ProxyPrelude;
import io.p2wlan.mc.transport.ScopedClientConnect;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;

/** Shared protected connection handoff. The selected game API supplies
 * Vanilla login, authentication and resource-pack effects. */
public final class MinecraftConnect {
    private MinecraftConnect() {}
    public static boolean supported() { return ScopedClientConnect.Hooks.class.isAssignableFrom(Connection.class); }
    public static ChannelFuture open(Connection connection, LocalEndpoint endpoint, ProxyPrelude prelude) throws ScopedClientConnect.FailureException {
        return ScopedClientConnect.open(connection, endpoint, prelude, () -> Connection.connect(endpoint.address(), false, connection));
    }
    /** Called on the game thread only after the capability/Vanilla handoff. */
    public static void login(Minecraft client, Connection connection, LocalEndpoint endpoint, Screen parent, Consumer<Component> progress) {
        GameLogin.login(client, connection, endpoint, parent, progress);
    }
}
