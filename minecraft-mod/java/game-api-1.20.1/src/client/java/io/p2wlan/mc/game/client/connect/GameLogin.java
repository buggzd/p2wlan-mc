package io.p2wlan.mc.game.client.connect;

import io.p2wlan.mc.common.LocalEndpoint;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.chat.report.ReportEnvironment;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;

/** Minecraft 1.20.1 login effects. The shared join owner admits the exact
 * connection on the game thread after the protected capability handoff. */
public final class GameLogin {
    private GameLogin() {}

    public static void login(Minecraft client, Connection connection, LocalEndpoint endpoint,
            Screen parent, Consumer<Component> progress) {
        // Runtime identities and local proxy ports stay out of saved server and
        // reporting addresses. Vanilla owns authentication and pack consent.
        ServerData info = new ServerData("P2WLAN", "p2wlan.invalid", false);
        client.prepareForMultiplayer();
        client.updateReportEnvironment(ReportEnvironment.thirdParty(info.ip));
        connection.setListener(new ClientHandshakePacketListenerImpl(
                connection, client, info, parent, false, null, progress));
        connection.send(new ClientIntentionPacket(endpoint.address().getAddress().getHostAddress(),
                endpoint.address().getPort(), ConnectionProtocol.LOGIN));
        connection.send(new ServerboundHelloPacket(client.getUser().getName(),
                Optional.ofNullable(client.getUser().getProfileId())));
    }
}
