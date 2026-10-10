package io.p2wlan.mc.fabric.client.screen;

import io.p2wlan.mc.common.MenuLogoLayout;
import java.util.List;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

public final class MenuLogo {
    private MenuLogo() {}
    public static void install(MinecraftClient client,Screen screen,int width,int height) {
        if (!(screen instanceof TitleScreen || screen instanceof MultiplayerScreen || screen instanceof GameMenuScreen)) return;
        var buttons=Screens.getButtons(screen);
        if (buttons.stream().anyMatch(button->button instanceof LogoButton)) return;
        List<String> keys=screen instanceof TitleScreen?List.of("menu.multiplayer","menu.options")
                :screen instanceof GameMenuScreen?List.of("menu.options","menu.returnToGame"):List.of("gui.cancel","gui.back","selectServer.select");
        for (String key:keys) {
            var anchor=buttons.stream().filter(button->button.getMessage().equals(Text.translatable(key))).findFirst().orElse(null);
            if (anchor==null) continue;
            var occupied=buttons.stream().filter(button->button!=anchor).map(MenuLogo::rect).toList();
            var placement=MenuLogoLayout.beside(rect(anchor),occupied,width,height);
            if (placement.isEmpty()) continue;
            var value=placement.get();anchor.setWidth(value.anchorWidth());
            buttons.add(new LogoButton(value.icon().x(),value.icon().y(),button->client.setScreen(AccountScreen.entry(screen))));
            return;
        }
        // Other mods may entirely replace the menu. Do not overlap their controls;
        // /p2wlan-client remains available rather than guessing a corner position.
    }
    private static MenuLogoLayout.Rect rect(ClickableWidget button) { return new MenuLogoLayout.Rect(button.getX(),button.getY(),button.getWidth(),button.getHeight()); }
}
