package io.p2wlan.mc.fabric.client;

import io.p2wlan.mc.common.AgentLifecycle;
import io.p2wlan.mc.fabric.FabricP2wlan;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.text.Text;

/** Client lifecycle is application-scoped, not recreated for each local world. */
public final class FabricP2wlanClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> FabricP2wlan.AGENT.start());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> FabricP2wlan.AGENT.close());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                ClientCommandManager.literal("p2wlan-client").then(ClientCommandManager.literal("status").executes(context -> {
                    AgentLifecycle.Status status = FabricP2wlan.AGENT.status();
                    context.getSource().sendFeedback(Text.literal("P2WLAN helper: " + status.phase()
                            + (status.reason() == null ? "" : " / " + status.reason())));
                    return status.phase() == AgentLifecycle.Phase.READY ? 1 : 0;
                }))));
    }
}
