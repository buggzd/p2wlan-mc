package io.p2wlan.mc.game.client.screen;

import io.p2wlan.mc.common.ScreenLayout;
import io.p2wlan.mc.game.client.GameUi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Local-only presentation screens stay usable without a healthy Native helper. */
abstract class LocalPanelScreen extends Screen {
    protected final Screen parent;
    protected ScreenLayout layout;
    protected String feedback="";
    LocalPanelScreen(Screen parent,String title) { super(Component.literal(title));this.parent=parent; }
    @Override protected void init() { layout=ScreenLayout.of(width,height); }
    protected Button button(String text,int x,int y,int width,Button.OnPress callback) {
        return addRenderableWidget(new PixelButton(Component.literal(text),x,y,width,callback));
    }
    protected String summary() { return feedback; }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void render(GuiGraphics draw,int mouseX,int mouseY,float delta) {
        GameUi.background(this, draw, mouseX, mouseY, delta);
        int x=layout.left(),y=layout.top()-62,end=layout.messageY()+30;
        draw.fill(x-10,y,x+layout.width()+10,end,0xf2131a25);
        super.render(draw,mouseX,mouseY,delta);
        // Vanilla applies its background pass in render; foreground labels must
        // be drawn afterwards, otherwise the blur also affects this title.
        draw.fill(x-10,y,x+layout.width()+10,y+2,0xff60a5fa);
        draw.drawString(font,"P2WLAN",x,y+6,0xff60a5fa);
        draw.drawString(font,title,x,y+22,0xfff7f9fc);
        var lines=font.split(Component.literal(summary()),layout.width());
        for (int i=0;i<Math.min(3,lines.size());i++) draw.drawString(font,lines.get(i),x,layout.messageY()+i*11,0xffb5c0d0);
    }
}
