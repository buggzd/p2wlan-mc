package io.p2wlan.mc.fabric.client.screen;

import io.p2wlan.mc.common.DisplayPreferences;
import io.p2wlan.mc.common.DisplayPreferencesStore;
import io.p2wlan.mc.fabric.client.ClientDisplaySettings;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class DisplaySettingsScreen extends LocalPanelScreen {
    private DisplayPreferences draft=ClientDisplaySettings.current();
    private ButtonWidget hud,corner;
    private final ButtonWidget[] levels=new ButtonWidget[3];
    public DisplaySettingsScreen(Screen parent) { super(parent,"显示设置"); }
    @Override protected void init() {
        super.init();
        if (ClientDisplaySettings.reason()!=DisplayPreferencesStore.Reason.OK) feedback="设置文件不可用，当前使用默认 / 上次有效设置："+ClientDisplaySettings.reason();
        hud=button("",layout.left(),layout.row(0),layout.halfWidth(),pressed->{draft=new DisplayPreferences(!draft.hudVisible(),draft.detail(),draft.corner());labels();});
        hud.setTooltip(Tooltip.of(Text.literal("只控制游戏内浮层；默认关闭。房间面板和延迟统计页始终可查看。F1 / F3 时不显示浮层。")));
        button("延迟统计",layout.right(),layout.row(0),layout.halfWidth(),pressed->client.setScreen(new LatencyStatsScreen(this)));
        int size=(layout.width()-16)/3;
        for (var level:DisplayPreferences.Detail.values()) {
            levels[level.ordinal()]=button(level.label,layout.left()+level.ordinal()*(size+8),layout.row(1),size,pressed->{draft=new DisplayPreferences(draft.hudVisible(),level,draft.corner());labels();});
            String description=switch(level) { case BASIC->"基础：当前 RTT + 直连 / 中继。";case DETAILED->"详细：基础 + Min / Avg / Max、抖动、样本数。";case ADVANCED->"高级：详细 + P50 / P90 / P95 / P99、路径 MTU、累计收发量。"; };
            levels[level.ordinal()].setTooltip(Tooltip.of(Text.literal(description)));
        }
        corner=button("",layout.left(),layout.row(2),layout.width(),pressed->{draft=new DisplayPreferences(draft.hudVisible(),draft.detail(),draft.corner().next());labels();});
        button("取消",layout.left(),layout.footerY(),layout.halfWidth(),pressed->close());
        button("保存并返回",layout.right(),layout.footerY(),layout.halfWidth(),pressed->{
            if (ClientDisplaySettings.save(draft)) close();
            else feedback="保存失败，未应用修改："+ClientDisplaySettings.reason();
        });
        labels();
    }
    private void labels() {
        hud.setMessage(Text.literal("游戏浮层："+(draft.hudVisible()?"开启":"关闭")));
        corner.setMessage(Text.literal("浮层位置："+draft.corner().label));
        for (var level:DisplayPreferences.Detail.values()) levels[level.ordinal()].setMessage(Text.literal(draft.detail()==level?"[ "+level.label+" ]":level.label));
    }
    @Override protected String summary() { return feedback.isEmpty()?"设置仅保存在本机，与登录和房间连接无关。":feedback; }
    @Override public void render(DrawContext draw,int mouseX,int mouseY,float delta) {
        super.render(draw,mouseX,mouseY,delta);
        String note=switch(draft.detail()) {case BASIC->"当前 RTT · 连接方式";case DETAILED->"+ Min / Avg / Max · 抖动 · 样本数";case ADVANCED->"+ P50 / P90 / P95 / P99 · MTU · 收发量";};
        draw.drawTextWithShadow(textRenderer,textRenderer.trimToWidth(note,layout.width()),layout.left(),layout.row(3)+2,0xffb5c0d0);
        draw.drawTextWithShadow(textRenderer,"统计窗口：60 秒，最多 256 个真实样本。",layout.left(),layout.row(3)+14,0xff7f8ba0);
    }
}
