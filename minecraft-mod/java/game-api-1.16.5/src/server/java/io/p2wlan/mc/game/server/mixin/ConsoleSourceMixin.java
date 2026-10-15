package io.p2wlan.mc.game.server.mixin;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exact original source identity for local-console-only account input on 1.16.5. */
@Mixin(CommandSourceStack.class)
public interface ConsoleSourceMixin {
    @Accessor("source") CommandSource p2wlan$source();
}
