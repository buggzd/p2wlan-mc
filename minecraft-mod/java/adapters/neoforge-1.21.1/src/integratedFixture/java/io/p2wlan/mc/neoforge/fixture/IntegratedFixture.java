package io.p2wlan.mc.neoforge.fixture;
import io.p2wlan.mc.game.fixture.IntegratedHostChecks;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.common.Mod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
/** Client JVM + local server events feed one shared bounded host fixture. */
@Mod(value="p2wlan_integrated_test",dist=Dist.CLIENT)
public final class IntegratedFixture {
    public IntegratedFixture() {
        InstalledArchiveChecks.verify();
        var checks=new IntegratedHostChecks();
        var started=new java.util.concurrent.atomic.AtomicBoolean();
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event)->{if(started.compareAndSet(false,true))checks.start();checks.tick(Minecraft.getInstance());});
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event)->checks.commands(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event)->checks.joined(event.getEntity().getGameProfile().getName()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event)->checks.disconnected(event.getEntity().getGameProfile().getName()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event)->checks.stopped(event.getServer()));
    }
}
