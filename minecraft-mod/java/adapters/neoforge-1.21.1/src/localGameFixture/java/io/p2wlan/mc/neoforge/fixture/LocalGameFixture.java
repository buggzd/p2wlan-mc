package io.p2wlan.mc.neoforge.fixture;

import io.p2wlan.mc.neoforge.NeoForgeP2wlan;
import io.p2wlan.mc.game.fixture.GameServerChecks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Dedicated loader events only; shared game checks are the authority for results. */
@Mod("p2wlan_local_game_test")
public final class LocalGameFixture {
    public LocalGameFixture() {
        if(FMLEnvironment.dist!=Dist.DEDICATED_SERVER) return;
        var checks=new GameServerChecks(NeoForgeP2wlan.AGENT,NeoForgeP2wlan::world,NeoForgeP2wlan::shutdownWorlds);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event)->checks.commands(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event)->checks.started());
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event)->checks.tick(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event)->checks.stopped(event.getServer()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event)->checks.joined());
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event)->checks.disconnected());
    }
}
