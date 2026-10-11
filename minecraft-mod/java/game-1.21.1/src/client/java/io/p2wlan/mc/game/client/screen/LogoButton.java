package io.p2wlan.mc.game.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Code-native pixel LAN mark. Narration and tooltip retain the product name. */
public final class LogoButton extends Button {
    public LogoButton(int x,int y,OnPress action) {
        super(x,y,20,20,Component.literal("P2WLAN"),action,DEFAULT_NARRATION);
        setTooltip(Tooltip.create(Component.literal("P2WLAN · 虚拟局域网")));
    }
    @Override protected void renderWidget(GuiGraphics draw,int mouseX,int mouseY,float delta) {
        int x=getX(),y=getY(); boolean hover=isHovered() || isFocused();
        draw.fill(x,y,x+20,y+20,hover?0xff294366:0xff202a3a);
        int edge=hover?0xff60a5fa:0xff7f8ba0;
        draw.fill(x,y,x+20,y+1,edge);draw.fill(x,y,x+1,y+20,edge);
        draw.fill(x+19,y,x+20,y+20,0xff101721);draw.fill(x,y+19,x+20,y+20,0xff101721);
        // Three monitors linked by a pixel bus. Integral coordinates only.
        draw.fill(x+9,y+6,x+11,y+14,0xff60a5fa);
        draw.fill(x+5,y+11,x+15,y+13,0xff60a5fa);
        monitor(draw,x+7,y+3,0xfff7f9fc);monitor(draw,x+2,y+12,0xff60a5fa);monitor(draw,x+12,y+12,0xff8de2b1);
    }
    private static void monitor(GuiGraphics draw,int x,int y,int color) {
        draw.fill(x,y,x+6,y+4,color);draw.fill(x+1,y+1,x+5,y+3,0xff131a25);
        draw.fill(x+2,y+4,x+4,y+5,color);
    }
}
