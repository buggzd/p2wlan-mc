package io.p2wlan.mc.fabric.client.connect;

import io.netty.channel.ChannelFuture;
import io.p2wlan.mc.common.LocalEndpoint;
import io.p2wlan.mc.transport.ProxyPrelude;
import io.p2wlan.mc.transport.ScopedClientConnect;
import java.time.Duration;
import java.util.function.Consumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.resource.server.ServerResourcePackManager;
import net.minecraft.client.session.report.ReporterEnvironment;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.c2s.login.LoginHelloC2SPacket;
import net.minecraft.network.state.LoginStates;
import net.minecraft.text.Text;

/** Exact-version Minecraft effects. Uses Vanilla codecs, login/authentication,
 * configuration, compression, encryption and resource-pack consent unchanged. */
public final class MinecraftConnect {
    private MinecraftConnect() {}
    public static boolean supported() { return ScopedClientConnect.Hooks.class.isAssignableFrom(ClientConnection.class); }
    public static ChannelFuture open(ClientConnection connection, LocalEndpoint endpoint, ProxyPrelude prelude) throws ScopedClientConnect.FailureException {
        return ScopedClientConnect.open(connection, endpoint, prelude, () -> ClientConnection.connect(endpoint.address(), false, connection));
    }
    /** Called on the game thread only after the capability/Vanilla handoff. */
    public static void login(MinecraftClient client, ClientConnection connection, LocalEndpoint endpoint, Screen parent, Consumer<Text> progress) {
        // Do not persist runtime/peer IDs or local proxy ports in quick-play or
        // reporting addresses. This is not a DNS target or alternate auth mode.
        ServerInfo info = new ServerInfo("P2WLAN", "p2wlan.invalid", ServerInfo.ServerType.OTHER);
        client.loadBlockList();
        client.ensureAbuseReportContext(ReporterEnvironment.ofThirdPartyServer(info.address));
        connection.resetPacketSizeLog(client.getDebugHud().getPacketSizeLog());
        client.getServerResourcePackProvider().init(connection, ServerResourcePackManager.AcceptanceStatus.PENDING);
        connection.connect(endpoint.address().getAddress().getHostAddress(), endpoint.address().getPort(), LoginStates.C2S, LoginStates.S2C,
                new ClientLoginNetworkHandler(connection, client, info, parent, false, Duration.ZERO, progress, null), false);
        connection.send(new LoginHelloC2SPacket(client.getSession().getUsername(), client.getSession().getUuidOrNull()));
    }
}
