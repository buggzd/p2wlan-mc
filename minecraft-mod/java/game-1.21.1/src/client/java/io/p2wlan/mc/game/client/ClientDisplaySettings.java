package io.p2wlan.mc.game.client;

import io.p2wlan.mc.common.DisplayPreferences;
import io.p2wlan.mc.common.DisplayPreferencesStore;
import io.p2wlan.mc.game.client.ClientPlatform;

/** Game-thread-only preferences. The Native room/account lifecycle is unrelated. */
public final class ClientDisplaySettings {
    private static final DisplayPreferencesStore STORE = new DisplayPreferencesStore(ClientPlatform.config().resolve("p2wlan-display.json"));
    private static DisplayPreferences preferences = DisplayPreferences.defaults();
    private static DisplayPreferencesStore.Reason reason = DisplayPreferencesStore.Reason.OK;
    private ClientDisplaySettings() {}
    public static void load() { var loaded = STORE.load(); preferences = loaded.preferences(); reason = loaded.reason(); }
    public static DisplayPreferences current() { return preferences; }
    public static DisplayPreferencesStore.Reason reason() { return reason; }
    public static boolean save(DisplayPreferences value) {
        var saved = STORE.saveDetailed(value);
        reason = saved.reason();
        ClientPlatform.agent().diagnostics().displaySettingsSave(saved);
        if (reason != DisplayPreferencesStore.Reason.OK) return false;
        preferences = value; return true;
    }
}
