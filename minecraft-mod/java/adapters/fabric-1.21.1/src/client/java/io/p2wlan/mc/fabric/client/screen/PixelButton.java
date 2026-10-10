package io.p2wlan.mc.fabric.client.screen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
/** Native focus/narration/input, flat square pixel surfaces and a one-pixel bevel. */
final class PixelButton extends ButtonWidget {
    PixelButton(Text text, int x, int y, int width, PressAction action) { super(x, y, width, 20, text, action, DEFAULT_NARRATION_SUPPLIER); }
    @Override protected void renderWidget(DrawContext draw, int mouseX, int mouseY, float delta) {
        int x=getX(), y=getY(), w=getWidth(), h=getHeight(); boolean hover=isHovered() || isFocused();
        draw.fill(x, y, x+w, y+h, active ? hover ? 0xff294366 : 0xff202a3a : 0xff19212d);
        int edge = active ? hover ? 0xff60a5fa : 0xff46536a : 0xff293241;
        draw.fill(x,y,x+w,y+1,edge); draw.fill(x,y+h-1,x+w,y+h,0xff101721);
        draw.fill(x,y,x+1,y+h,edge); draw.fill(x+w-1,y,x+w,y+h,edge);
        var font=MinecraftClient.getInstance().textRenderer;
        draw.drawCenteredTextWithShadow(font, font.trimToWidth(getMessage().getString(), Math.max(1,w-12)), x+w/2,y+6,active?0xfff7f9fc:0xff647085);
    }
}
