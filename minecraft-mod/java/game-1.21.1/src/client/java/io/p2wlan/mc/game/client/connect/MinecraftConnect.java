package io.p2wlan.mc.game.client.connect;

import io.netty.channel.ChannelFuture;
import io.p2wlan.mc.common.LocalEndpoint;
import io.p2wlan.mc.transport.ProxyPrelude;
import io.p2wlan.mc.transport.ScopedClientConnect;
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

/** Exact-version Minecraft effects. Uses Vanilla codecs, login/authentication,
 * configuration, compression, encryption and resource-pack consent unchanged. */
public final class MinecraftConnect {
    private MinecraftConnect() {}
    public static boolean supported() { return ScopedClientConnect.Hooks.class.isAssignableFrom(Connection.class); }
    public static ChannelFuture open(Connection connection, LocalEndpoint endpoint, ProxyPrelude prelude) throws ScopedClientConnect.FailureException {
        return ScopedClientConnect.open(connection, endpoint, prelude, () -> Connection.connect(endpoint.address(), false, connection));
    }
    /** Called on the game thread only after the capability/Vanilla handoff. */
    public static void login(Minecraft client, Connection connection, LocalEndpoint endpoint, Screen parent, Consumer<Component> progress) {
        // Do not persist runtime/peer IDs or local proxy ports in quick-play or
        // reporting addresses. This is not a DNS target or alternate auth mode.
        ServerData info = new ServerData("P2WLAN", "p2wlan.invalid", ServerData.Type.OTHER);
        client.prepareForMultiplayer();
        client.updateReportEnvironment(ReportEnvironment.thirdParty(info.ip));
        connection.setBandwidthLogger(client.getDebugOverlay().getBandwidthLogger());
        client.getDownloadedPackSource().configureForServerControl(connection, ServerPackManager.PackPromptStatus.PENDING);
        connection.initiateServerboundPlayConnection(endpoint.address().getAddress().getHostAddress(), endpoint.address().getPort(), LoginProtocols.SERVERBOUND, LoginProtocols.CLIENTBOUND,
                new ClientHandshakePacketListenerImpl(connection, client, info, parent, false, Duration.ZERO, progress, null), false);
        connection.send(new ServerboundHelloPacket(client.getUser().getName(), client.getUser().getProfileId()));
    }
}
