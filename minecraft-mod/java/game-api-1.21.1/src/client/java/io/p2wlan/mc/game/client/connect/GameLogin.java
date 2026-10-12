package io.p2wlan.mc.game.client.connect;

import io.p2wlan.mc.common.LocalEndpoint;
import java.time.Duration;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.chat.report.ReportEnvironment;
import net.minecraft.client.resources.server.ServerPackManager;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;

/** Minecraft 1.21.1 login effects. The shared join owner admits the exact
 * connection on the game thread after the protected capability handoff. */
public final class GameLogin {
    private GameLogin() {}

    public static void login(Minecraft client, Connection connection, LocalEndpoint endpoint,
            Screen parent, Consumer<Component> progress) {
        // Runtime identities and local proxy ports stay out of saved server and
        // reporting addresses. Vanilla owns authentication and pack consent.
        ServerData info = new ServerData("P2WLAN", "p2wlan.invalid", ServerData.Type.OTHER);
        client.prepareForMultiplayer();
        client.updateReportEnvironment(ReportEnvironment.thirdParty(info.ip));
        connection.setBandwidthLogger(client.getDebugOverlay().getBandwidthLogger());
        client.getDownloadedPackSource().configureForServerControl(connection, ServerPackManager.PackPromptStatus.PENDING);
        connection.initiateServerboundPlayConnection(endpoint.address().getAddress().getHostAddress(),
                endpoint.address().getPort(), LoginProtocols.SERVERBOUND, LoginProtocols.CLIENTBOUND,
                new ClientHandshakePacketListenerImpl(connection, client, info, parent, false,
                        Duration.ZERO, progress, null), false);
        connection.send(new ServerboundHelloPacket(client.getUser().getName(), client.getUser().getProfileId()));
    }
}
