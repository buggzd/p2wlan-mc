package io.p2wlan.mc.neoforge.fixture;
import io.p2wlan.mc.game.fixture.ClientHookChecks;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.common.Mod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
/** Test-only client event glue; no assertions are duplicated by loader. */
@Mod(value="p2wlan_client_hook_test", dist=Dist.CLIENT)
public final class ClientHookFixture {
    private final AtomicBoolean started = new AtomicBoolean();
    public ClientHookFixture() { NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
        if (started.compareAndSet(false,true)) new ClientHookChecks().start(Minecraft.getInstance());
    }); }
}
