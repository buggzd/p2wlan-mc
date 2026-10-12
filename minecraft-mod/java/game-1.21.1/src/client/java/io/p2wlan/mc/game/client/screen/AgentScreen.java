package io.p2wlan.mc.game.client.screen;

import com.google.gson.JsonObject;
import io.p2wlan.mc.common.AccountPresentation;
import io.p2wlan.mc.common.AgentClient;
import io.p2wlan.mc.common.AgentException;
import io.p2wlan.mc.common.AgentReply;
import io.p2wlan.mc.common.ControlTransport;
import io.p2wlan.mc.common.ScreenLayout;
import io.p2wlan.mc.common.UiCompletionGate;
import io.p2wlan.mc.common.UiFeedback;
import io.p2wlan.mc.game.client.ClientPlatform;
import io.p2wlan.mc.game.client.GameUi;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Screen/view lifetime only. Native peer/room/runtime truth is not duplicated. */
abstract class AgentScreen extends Screen {
    static final UiCompletionGate REQUESTS = new UiCompletionGate();
    protected final Screen parent;
    protected String message = "";
    protected int left, top, formWidth;
    protected ScreenLayout layout;
    private record Action(Button button, BooleanSupplier allowed) {}
    private record Label(String text, int y) {}
    private final List<Action> actions = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private AtomicBoolean view = new AtomicBoolean(false);
    protected AgentScreen(Screen parent, String title) { super(Component.literal(title)); this.parent = parent; }
    @Override protected void init() {
        view.set(false);
        view = new AtomicBoolean(true);
        actions.clear(); labels.clear();
        layout = ScreenLayout.of(width, height);
        formWidth = layout.width(); left = layout.left(); top = layout.top();
        // Local settings are usable even when helper initialization failed.
        addRenderableWidget(new PixelButton(Component.literal("诊断日志"), left + formWidth - 160, top - 42, 76,
                button -> minecraft.setScreen(new DiagnosticsScreen(this))));
        addRenderableWidget(new PixelButton(Component.literal("显示设置"), left + formWidth - 76, top - 42, 76,
                button -> minecraft.setScreen(new DisplaySettingsScreen(this))));
    }
    private int labelWidth() { return Math.min(84, formWidth / 3); }
    protected EditBox field(int y, String name, int maximum) {
        labels.add(new Label(name, y + 6));
        EditBox field = addRenderableWidget(new EditBox(font, left + labelWidth() + 8,
                y, formWidth - labelWidth() - 8, 20, Component.literal(name)));
        field.setMaxLength(maximum); field.setHint(Component.literal(name));
        return field;
    }
    protected PasswordField passwordField(int y, String name) {
        labels.add(new Label(name, y + 6));
        PasswordField field = addRenderableWidget(new PasswordField(font, left + labelWidth() + 8,
                y, formWidth - labelWidth() - 8, Component.literal(name)));
        return field;
    }
    protected Button action(String label, int x, int y, int size, Button.OnPress callback) {
        return action(label, x, y, size, () -> true, callback);
    }
    protected Button action(String label, int x, int y, int size, BooleanSupplier allowed, Button.OnPress callback) {
        Button button = addRenderableWidget(new PixelButton(Component.literal(label), x, y, size, pressed -> {
            // A rendered enabled flag is only a hint; recheck before invoking.
            if (eligible(allowed)) callback.onPress(pressed);
        }));
        actions.add(new Action(button, allowed)); button.active = eligible(allowed);
        return button;
    }
    protected void footerAction(String label, BooleanSupplier allowed, Button.OnPress callback) {
        action(label, left, layout.footerY(), layout.halfWidth(), allowed, callback);
    }
    protected void back() { back(false); }
    protected void back(boolean paired) {
        addRenderableWidget(new PixelButton(Component.literal("返回游戏"), paired ? layout.right() : left, layout.footerY(), paired ? layout.halfWidth() : formWidth, button -> onClose()));
    }
    protected AgentClient agent() { return ClientPlatform.agent().current().orElse(null); }
    protected boolean idle() {
        AgentClient current = agent();
        if (current == null) return false;
        boolean idle = current.snapshot().map(value -> "idle".equals(io.p2wlan.mc.common.RuntimeView.text(value.data(), "phase"))).orElse(false);
        return idle && ClientPlatform.agent().isCurrent(current) && !current.isClosed();
    }
    protected AccountPresentation.State accountState() {
        AgentClient current = agent();
        if (current == null) return AccountPresentation.State.UNKNOWN;
        var snapshot = current.snapshot().orElse(null);
        var state = snapshot == null ? AccountPresentation.State.UNKNOWN : AccountPresentation.state(snapshot.data().get("account"));
        return ClientPlatform.agent().isCurrent(current) && !current.isClosed() ? state : AccountPresentation.State.UNKNOWN;
    }
    protected boolean savedAccount() { return accountState() == AccountPresentation.State.SAVED_SESSION; }
    private boolean eligible(BooleanSupplier allowed) {
        AgentClient current = agent();
        return view.get() && current != null && ClientPlatform.agent().isCurrent(current) && !current.isClosed()
                && actionsEnabled() && !REQUESTS.busy() && allowed.getAsBoolean();
    }
    protected BooleanSupplier viewFence() { AtomicBoolean captured = view; return captured::get; }
    protected interface TaskAction<T> { void accept(T value) throws AgentException; }
    protected <T> void task(java.util.function.Supplier<java.util.concurrent.CompletableFuture<T>> source, BooleanSupplier scope, TaskAction<T> callback) {
        AgentClient agent = agent();
        if (agent == null) { message = UiFeedback.failure("task", new AgentException(AgentException.Code.NOT_READY)); return; }
        AtomicBoolean captured = view;
        message = UiFeedback.working("task");
        try {
            REQUESTS.submitTask(source, () -> ClientPlatform.agent().isCurrent(agent) && scope.getAsBoolean(), captured::get,
                action -> minecraft.execute(() -> { if (minecraft.screen != this) captured.set(false); action.run(); }),
                (value, error) -> {
                    if (error != null) { message = UiFeedback.failure("task", error); return; }
                    try { callback.accept(value); } catch (AgentException invalid) { message = UiFeedback.failure("task", invalid); }
                });
        } catch (AgentException invalid) { message = UiFeedback.failure("task", invalid); }
    }
    protected boolean actionsEnabled() { return idle(); }
    protected interface ReplyAction { void accept(AgentReply reply) throws AgentException; }
    protected void request(String operation, JsonObject arguments, ReplyAction callback) { request(operation, arguments, () -> true, callback); }
    protected void request(String operation, JsonObject arguments, BooleanSupplier scope, ReplyAction callback) {
        AgentClient agent = agent();
        if (agent == null) { message = UiFeedback.failure(operation, new AgentException(AgentException.Code.NOT_READY)); return; }
        AtomicBoolean captured = view;
        message = UiFeedback.working(operation);
        try {
            REQUESTS.submit(agent, operation, arguments, () -> ClientPlatform.agent().isCurrent(agent) && scope.getAsBoolean(), captured::get,
                task -> minecraft.execute(() -> { if (minecraft.screen != this) captured.set(false); task.run(); }),
                (reply, error) -> {
                    if (error != null) { message = UiFeedback.failure(operation, error); return; }
                    try { callback.accept(reply); }
                    catch (AgentException invalid) { message = UiFeedback.failure(operation, invalid); }
                });
        } catch (AgentException error) { message = UiFeedback.failure(operation, error); }
    }
    @Override public void tick() {
        super.tick();
        for (Action action : actions) action.button().active = eligible(action.allowed());
    }
    protected boolean insecureControl() {
        AgentClient current = agent();
        if (current == null) return false;
        var snapshot = current.snapshot().orElse(null);
        return snapshot != null && ControlTransport.accountIsPlaintext(snapshot.data().get("account"))
                && ClientPlatform.agent().isCurrent(current) && !current.isClosed();
    }
    protected String hint() { return "连接房间后，在 Minecraft 多人游戏中选择服务器。"; }
    protected String liveSummary() { return ""; }
    @Override public void removed() { view.set(false); }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
    private void summary(GuiGraphics context, String value, int y, int color) {
        context.drawCenteredString(font, Component.literal(font.plainSubstrByWidth(value, formWidth)), width / 2, y, color);
    }
    @Override public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        GameUi.background(this, context, mouseX, mouseY, delta);
        int header = top - 56, bottom = layout.messageY() + 33;
        context.fill(left - 9, header - 5, left + formWidth + 11, bottom + 2, 0x70000000);
        context.fill(left - 10, header - 6, left + formWidth + 10, bottom, 0xf2131a25);
        context.fill(left - 10, header - 6, left + formWidth + 10, header - 4, 0xff60a5fa);
        super.render(context, mouseX, mouseY, delta);
        context.drawString(font, "P2WLAN", left, header, 0xff60a5fa);
        context.drawString(font, font.plainSubstrByWidth(title.getString(), formWidth - 170), left, header + 16, 0xfff7f9fc);
        var helper = ClientPlatform.agent().status();
        String helperText = "IPC: " + helper.phase() + (helper.reason() == null ? " / " + "native" : " / " + helper.reason());
        boolean ready = helper.phase() == io.p2wlan.mc.common.AgentLifecycle.Phase.READY;
        String state = !ready ? helper.reason() == null ? "助手启动中" : "启动失败" : savedAccount() ? "已登录 · 本机" : "未登录";
        int tone = !ready && helper.reason() != null ? 0xffffa39a : savedAccount() ? 0xff8de2b1 : 0xffb5c0d0;
        int badge = left + formWidth - font.width(state);
        context.fill(badge - 8, header + 3, badge - 4, header + 7, tone);
        context.drawString(font, state, badge, header, tone);
        if (insecureControl()) context.drawString(font, "HTTP · 未加密", left, header + 40, 0xfff1c96a);
        for (Label label : labels) context.drawString(font, Component.literal(font.plainSubstrByWidth(label.text(), labelWidth())), left, label.y(), 0xd0d0d0);
        String feedback = REQUESTS.busy() ? "正在处理…" : message.isEmpty()
                ? !ready && helper.reason() != null ? "助手启动失败。点击「诊断日志」查看步骤和系统错误码。" : hint() : message;
        // Runtime/world truth remains visible even after a request result.
        String live = liveSummary();
        int reserved = live.isEmpty() ? 0 : 1;
        if (reserved != 0) context.drawString(font, Component.literal(font.plainSubstrByWidth(live, formWidth)), left, layout.messageY(), 0xc1dfdf);
        var lines = font.split(Component.literal(feedback), formWidth);
        int count = Math.min(3 - reserved, lines.size());
        for (int index = 0; index < count; index++) context.drawString(font, lines.get(index), left, layout.messageY() + (index + reserved) * (font.lineHeight + 2), (REQUESTS.busy() || !message.isEmpty()) ? 0xfff1c96a : 0xff7f8ba0);
        if (mouseX >= left && mouseX < left + formWidth) {
            String tooltip = mouseY >= header && mouseY < header + 10 ? helperText
                    : mouseY >= header + 16 && mouseY < header + 26 && mouseX < left + formWidth - 165 ? "登录会话保存在本机私有目录，输入的密码只用于本次请求。退出世界和断开房间后会话仍保留；Control 在房间操作时检查授权。"
                    : insecureControl() && mouseY >= header + 40 && mouseY < header + 49 ? ControlTransport.BANNER
                    : mouseY >= layout.messageY() && (reserved != 0 || lines.size() > 3) ? (reserved == 0 ? feedback : live + "\n" + feedback) : null;
            if (tooltip != null) GameUi.tooltip(this, Component.literal(tooltip));
        }
    }
    static void shutdown() { REQUESTS.close(); }
}
