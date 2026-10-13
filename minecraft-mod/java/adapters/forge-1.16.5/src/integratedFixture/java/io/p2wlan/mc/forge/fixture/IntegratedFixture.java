package io.p2wlan.mc.forge.fixture;
import io.p2wlan.mc.game.fixture.IntegratedHostChecks;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.TickEvent.ClientTickEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppedEvent;
/** Client JVM + local server events feed one shared bounded host fixture. */
@Mod("p2wlan_integrated_test")
public final class IntegratedFixture {
    public IntegratedFixture() {
        if(net.minecraftforge.fml.loading.FMLEnvironment.dist!=Dist.CLIENT) throw new IllegalStateException("INTEGRATED_FIXTURE_CLIENT_ONLY");
        InstalledArchiveChecks.verify();
        var checks=new IntegratedHostChecks();
        var started=new java.util.concurrent.atomic.AtomicBoolean();
        MinecraftForge.EVENT_BUS.addListener((ClientTickEvent event)->{if(event.phase!=TickEvent.Phase.END)return;if(started.compareAndSet(false,true))checks.start();checks.tick(Minecraft.getInstance());});
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event)->checks.commands(event.getDispatcher()));
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event)->checks.joined(event.getPlayer().getGameProfile().getName()));
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event)->checks.disconnected(event.getPlayer().getGameProfile().getName()));
        MinecraftForge.EVENT_BUS.addListener((FMLServerStoppedEvent event)->checks.stopped(event.getServer()));
    }
}
