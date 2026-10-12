package io.p2wlan.mc.game.server.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the actual connection's capability scope; no separate login state. */
@Mixin(Connection.class)
public interface ServerLoginConnectionMixin {
    @Accessor("channel")
    Channel p2wlan$channel();
}
