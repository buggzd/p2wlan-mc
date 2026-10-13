package io.p2wlan.mc.game.client.connect;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.p2wlan.mc.common.LocalEndpoint;
import io.p2wlan.mc.transport.ProxyPrelude;
import io.p2wlan.mc.transport.ScopedClientConnect;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.Varint21FrameDecoder;
import net.minecraft.network.Varint21LengthFieldPrepender;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;

/** Version-specific Vanilla codec bootstrap. The supplied connection stays
 * owned by GameJoins; the shared scope installs its capability before async I/O.
 * Vanilla's old blocking connectToServer factory is never called here. */
public final class MinecraftConnect {
    private MinecraftConnect() {}
    public static boolean supported() { return ScopedClientConnect.Hooks.class.isAssignableFrom(Connection.class); }
    public static ChannelFuture open(Connection connection, LocalEndpoint endpoint, ProxyPrelude prelude) throws ScopedClientConnect.FailureException {
        return ScopedClientConnect.open(connection, endpoint, prelude, () -> {
            ChannelInitializer<Channel> vanilla = new ChannelInitializer<>() {
                @Override protected void initChannel(Channel channel) {
                    channel.pipeline().addLast("timeout", new ReadTimeoutHandler(30))
                            .addLast("splitter", new Varint21FrameDecoder())
                            .addLast("decoder", new PacketDecoder(PacketFlow.CLIENTBOUND))
                            .addLast("prepender", new Varint21LengthFieldPrepender())
                            .addLast("encoder", new PacketEncoder(PacketFlow.SERVERBOUND))
                            .addLast("packet_handler", connection);
                }
            };
            return new Bootstrap().group(Connection.NETWORK_WORKER_GROUP.get()).channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(ScopedClientConnect.wrap(connection, endpoint.address(), vanilla))
                    .connect(endpoint.address());
        });
    }
    public static void login(Minecraft client, Connection connection, LocalEndpoint endpoint, Screen parent, Consumer<Component> progress) {
        GameLogin.login(client, connection, endpoint, parent, progress);
    }
}
