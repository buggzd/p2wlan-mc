package io.p2wlan.mc.game.client.screen;

import io.p2wlan.mc.common.DiagnosticPresentation;
import io.p2wlan.mc.common.AgentLifecycle;
import io.p2wlan.mc.game.client.ClientPlatform;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Never asks the failed helper for evidence. Clipboard and local log navigation
 * stay usable before native hello, after failure, and without an account. */
public final class DiagnosticsScreen extends LocalPanelScreen {
    private Button open;
    private final AgentLifecycle owner;
    public DiagnosticsScreen(Screen parent) { this(parent, ClientPlatform.agent()); }
    public DiagnosticsScreen(Screen parent, AgentLifecycle owner) {
        super(parent, "启动诊断"); this.owner = java.util.Objects.requireNonNull(owner);
    }
    @Override protected void init() {
        super.init();
        button("复制诊断", layout.left(), layout.row(3), layout.halfWidth(), pressed -> {
            try {
                minecraft.keyboardHandler.setClipboard(owner.diagnostics().snapshot().copyText());
                feedback = "已复制脱敏诊断。可直接粘贴反馈；不含账号密码、令牌或原始日志。";
            } catch (RuntimeException error) { feedback = "剪贴板不可用，请发送 startup.log 文件。"; }
        });
        open = button("打开日志目录", layout.right(), layout.row(3), layout.halfWidth(), pressed -> {
            var snapshot = owner.diagnostics().snapshot();
            if (!DiagnosticPresentation.canOpenLog(snapshot)) { feedback = "文件日志不可用，请点击「复制诊断」。"; return; }
            try { Util.getPlatform().openFile(snapshot.directory().toFile()); }
            catch (RuntimeException error) { feedback = "目录打开失败。请在游戏运行目录查找 logs/p2wlan-mod。"; }
        });
        button("返回", layout.left(), layout.footerY(), layout.width(), pressed -> onClose());
        tick();
    }
    @Override public void tick() {
        super.tick();
        if (open != null) open.active = DiagnosticPresentation.canOpenLog(owner.diagnostics().snapshot());
    }
    @Override protected String summary() {
        return feedback.isEmpty() ? DiagnosticPresentation.explanation(owner.diagnostics().snapshot().failure()) : feedback;
    }
    private void line(GuiGraphics draw, String value, int row, int color, int mouseX, int mouseY) {
        int y = layout.top() + row * 12;
        draw.drawString(font, font.plainSubstrByWidth(value, layout.width()), layout.left(), y, color);
        if (mouseX >= layout.left() && mouseX < layout.left() + layout.width() && mouseY >= y && mouseY < y + 11)
            setTooltipForNextRenderPass(Component.literal(value));
    }
    @Override public void render(GuiGraphics draw, int mouseX, int mouseY, float delta) {
        super.render(draw, mouseX, mouseY, delta);
        var snapshot = owner.diagnostics().snapshot();
        var status = owner.status();
        var failure = snapshot.failure();
        var detail = failure == null ? null : failure.startup();
        line(draw, "助手状态：" + status.phase(), 0, 0xfff7f9fc, mouseX, mouseY);
        line(draw, "错误：" + (failure == null ? "无" : failure.reason()), 1, 0xfff1c96a, mouseX, mouseY);
        line(draw, "阶段 / 操作：" + (detail == null ? "见日志事件" : detail.stage() + " / " + detail.operation()), 2, 0xffb5c0d0, mouseX, mouseY);
        line(draw, "文件类别：" + (detail == null ? "未知" : detail.target()) + (detail == null || detail.pathDepth() == null ? "" : " · 层级 " + detail.pathDepth()), 3, 0xffb5c0d0, mouseX, mouseY);
        line(draw, "系统错误码：" + (detail == null || detail.osCode() == null ? "未知 / 不适用" : detail.osCode()), 4, 0xffb5c0d0, mouseX, mouseY);
        line(draw, DiagnosticPresentation.diskLabel(snapshot), 5, 0xff7f8ba0, mouseX, mouseY);
    }
}
