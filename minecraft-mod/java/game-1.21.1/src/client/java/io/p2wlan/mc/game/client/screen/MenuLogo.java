package io.p2wlan.mc.game.client.screen;

import io.p2wlan.mc.common.MenuLogoLayout;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

public final class MenuLogo {
    private MenuLogo() {}
    public static void install(Minecraft client,Screen screen,int width,int height,List<? extends AbstractWidget> buttons,java.util.function.Consumer<AbstractWidget> add) {
        if (!(screen instanceof TitleScreen || screen instanceof JoinMultiplayerScreen || screen instanceof PauseScreen)) return;
        if (buttons.stream().anyMatch(button->button instanceof LogoButton)) return;
        List<String> keys=screen instanceof TitleScreen?List.of("menu.multiplayer","menu.options")
                :screen instanceof PauseScreen?List.of("menu.options","menu.returnToGame"):List.of("gui.cancel","gui.back","selectServer.select");
        for (String key:keys) {
            var anchor=buttons.stream().filter(button->button.getMessage().equals(Component.translatable(key))).findFirst().orElse(null);
            if (anchor==null) continue;
            var occupied=buttons.stream().filter(button->button!=anchor).map(MenuLogo::rect).toList();
            var placement=MenuLogoLayout.beside(rect(anchor),occupied,width,height);
            if (placement.isEmpty()) continue;
            var value=placement.get();anchor.setWidth(value.anchorWidth());
            add.accept(new LogoButton(value.icon().x(),value.icon().y(),button->client.setScreen(AccountScreen.entry(screen))));
            return;
        }
        // Other mods may entirely replace the menu. Do not overlap their controls;
        // /p2wlan-client remains available rather than guessing a corner position.
    }
    private static MenuLogoLayout.Rect rect(AbstractWidget button) { return new MenuLogoLayout.Rect(button.getX(),button.getY(),button.getWidth(),button.getHeight()); }
}
