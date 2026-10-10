package io.p2wlan.mc.fabric.client.screen;

import io.p2wlan.mc.common.ScreenLayout;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** Local-only presentation screens stay usable without a healthy Native helper. */
abstract class LocalPanelScreen extends Screen {
    protected final Screen parent;
    protected ScreenLayout layout;
    protected String feedback="";
    LocalPanelScreen(Screen parent,String title) { super(Text.literal(title));this.parent=parent; }
    @Override protected void init() { layout=ScreenLayout.of(width,height); }
    protected ButtonWidget button(String text,int x,int y,int width,ButtonWidget.PressAction callback) {
        return addDrawableChild(new PixelButton(Text.literal(text),x,y,width,callback));
    }
    protected String summary() { return feedback; }
    @Override public void close() { client.setScreen(parent); }
    @Override public boolean shouldPause() { return false; }
    @Override public void render(DrawContext draw,int mouseX,int mouseY,float delta) {
        renderBackground(draw,mouseX,mouseY,delta);
        int x=layout.left(),y=layout.top()-62,end=layout.messageY()+30;
        draw.fill(x-10,y,x+layout.width()+10,end,0xf2131a25);
        super.render(draw,mouseX,mouseY,delta);
        // Vanilla applies its background pass in render; foreground labels must
        // be drawn afterwards, otherwise the blur also affects this title.
        draw.fill(x-10,y,x+layout.width()+10,y+2,0xff60a5fa);
        draw.drawTextWithShadow(textRenderer,"P2WLAN",x,y+6,0xff60a5fa);
        draw.drawTextWithShadow(textRenderer,title,x,y+22,0xfff7f9fc);
        var lines=textRenderer.wrapLines(Text.literal(summary()),layout.width());
        for (int i=0;i<Math.min(3,lines.size());i++) draw.drawTextWithShadow(textRenderer,lines.get(i),x,layout.messageY()+i*11,0xffb5c0d0);
    }
}
