package io.p2wlan.mc.forge.v1165;

import io.p2wlan.mc.common.BundledAgent;
import io.p2wlan.mc.common.LayerNativeResources;
import java.io.IOException;
import java.io.InputStream;

/** Forge 36's portable resource locator supplies bounded local JAR URLs. */
public final class LocalNativeResources implements BundledAgent.Resources {
    private final LayerNativeResources resources;
    public LocalNativeResources(ClassLoader loader, ModuleLayer ignored) {
        resources = new LayerNativeResources(loader, null, path -> null);
    }
    @Override public InputStream open(String name) throws IOException { return resources.open(name); }
}
