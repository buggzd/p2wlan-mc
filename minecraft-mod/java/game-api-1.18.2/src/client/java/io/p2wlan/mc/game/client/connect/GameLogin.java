package io.p2wlan.mc.game.client.connect;

import io.p2wlan.mc.common.LocalEndpoint;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;

/** The shared join owner admits the exact connection before these login effects. */
public final class GameLogin {
    private GameLogin() {}
    public static void login(Minecraft client, Connection connection, LocalEndpoint endpoint,
            Screen parent, Consumer<Component> progress) {
        client.prepareForMultiplayer();
        client.setCurrentServer(new ServerData("P2WLAN", "p2wlan.invalid", false));
        connection.setListener(new ClientHandshakePacketListenerImpl(connection, client, parent, progress));
        connection.send(new ClientIntentionPacket(endpoint.address().getAddress().getHostAddress(),
                endpoint.address().getPort(), ConnectionProtocol.LOGIN));
        connection.send(new ServerboundHelloPacket(client.getUser().getGameProfile()));
    }
}
