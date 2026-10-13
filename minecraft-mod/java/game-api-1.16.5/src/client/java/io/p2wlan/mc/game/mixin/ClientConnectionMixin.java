package io.p2wlan.mc.game.mixin;

import io.p2wlan.mc.transport.ScopedClientConnect;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;

/** Required connection identity marker. The version's nonblocking codec
 * bootstrap consumes the shared exact-connection scope directly. */
@Mixin(Connection.class)
public abstract class ClientConnectionMixin implements ScopedClientConnect.Hooks {}
