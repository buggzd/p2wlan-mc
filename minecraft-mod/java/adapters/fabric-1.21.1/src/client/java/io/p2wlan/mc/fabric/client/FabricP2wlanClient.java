package io.p2wlan.mc.fabric.client;

import io.p2wlan.mc.fabric.FabricP2wlan;
import net.fabricmc.api.ClientModInitializer;

/** The common entry owns initialization and selects this game's client hooks. */
public final class FabricP2wlanClient implements ClientModInitializer {
    @Override public void onInitializeClient() { FabricP2wlan.initializeClient(); }
}
