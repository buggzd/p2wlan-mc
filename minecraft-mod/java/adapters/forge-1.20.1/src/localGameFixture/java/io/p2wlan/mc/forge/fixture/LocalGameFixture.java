package io.p2wlan.mc.forge.fixture;

import io.p2wlan.mc.forge.ForgeP2wlan;
import io.p2wlan.mc.game.fixture.GameServerChecks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.TickEvent.ServerTickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;

/** Dedicated loader events only; shared game checks are the authority for results. */
@Mod("p2wlan_local_game_test")
public final class LocalGameFixture {
    public LocalGameFixture() {
        if(FMLEnvironment.dist!=Dist.DEDICATED_SERVER) return;
        InstalledArchiveChecks.verify();
        var checks=new GameServerChecks(ForgeP2wlan.AGENT,ForgeP2wlan::world,ForgeP2wlan::shutdownWorlds);
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event)->checks.commands(event.getDispatcher()));
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent event)->checks.started());
        MinecraftForge.EVENT_BUS.addListener((ServerTickEvent event)->{ if(event.phase==TickEvent.Phase.END) checks.tick(event.getServer()); });
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent event)->checks.stopped(event.getServer()));
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event)->checks.joined());
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event)->checks.disconnected());
    }
}
