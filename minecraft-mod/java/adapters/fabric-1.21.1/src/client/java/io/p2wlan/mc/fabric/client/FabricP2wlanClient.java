package io.p2wlan.mc.fabric.client;

import io.p2wlan.mc.fabric.FabricP2wlan;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvType;

/** The common entry owns initialization and selects this game's client hooks. */
@Environment(EnvType.CLIENT)
public final class FabricP2wlanClient implements ClientModInitializer {
    @Override public void onInitializeClient() { FabricP2wlan.initializeClient(); }
}
