package io.p2wlan.mc.fabric.client.screen;

import com.google.gson.JsonObject;
import io.p2wlan.mc.common.AccountPresentation;
import io.p2wlan.mc.common.AgentClient;
import io.p2wlan.mc.common.AgentException;
import io.p2wlan.mc.common.AgentReply;
import io.p2wlan.mc.common.ControlTransport;
import io.p2wlan.mc.common.ScreenLayout;
import io.p2wlan.mc.common.UiCompletionGate;
import io.p2wlan.mc.common.UiFeedback;
import io.p2wlan.mc.fabric.FabricP2wlan;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Screen/view lifetime only. Native peer/room/runtime truth is not duplicated. */
abstract class AgentScreen extends Screen {
    static final UiCompletionGate REQUESTS = new UiCompletionGate();
    protected final Screen parent;
    protected String message = "";
    protected int left, top, formWidth;
    protected ScreenLayout layout;
    private record Action(ButtonWidget button, BooleanSupplier allowed) {}
    private record Label(String text, int y) {}
    private final List<Action> actions = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private AtomicBoolean view = new AtomicBoolean(false);
    protected AgentScreen(Screen parent, String title) { super(Text.literal(title)); this.parent = parent; }
    @Override protected void init() {
        view.set(false);
        view = new AtomicBoolean(true);
        actions.clear(); labels.clear();
        layout = ScreenLayout.of(width, height);
        formWidth = layout.width(); left = layout.left(); top = layout.top();
    }
    private int labelWidth() { return Math.min(84, formWidth / 3); }
    protected TextFieldWidget field(int y, String name, int maximum) {
        labels.add(new Label(name, y + 6));
        TextFieldWidget field = addDrawableChild(new TextFieldWidget(textRenderer, left + labelWidth() + 8,
                y, formWidth - labelWidth() - 8, 20, Text.literal(name)));
        field.setMaxLength(maximum); field.setPlaceholder(Text.literal(name));
        return field;
    }
    protected PasswordField passwordField(int y, String name) {
        labels.add(new Label(name, y + 6));
        PasswordField field = addDrawableChild(new PasswordField(textRenderer, left + labelWidth() + 8,
                y, formWidth - labelWidth() - 8, Text.literal(name)));
        return field;
    }
    protected ButtonWidget action(String label, int x, int y, int size, ButtonWidget.PressAction callback) {
        return action(label, x, y, size, () -> true, callback);
    }
    protected ButtonWidget action(String label, int x, int y, int size, BooleanSupplier allowed, ButtonWidget.PressAction callback) {
        ButtonWidget button = addDrawableChild(new PixelButton(Text.literal(label), x, y, size, pressed -> {
            // A rendered enabled flag is only a hint; recheck before invoking.
            if (eligible(allowed)) callback.onPress(pressed);
        }));
        actions.add(new Action(button, allowed)); button.active = eligible(allowed);
        return button;
    }
    protected void footerAction(String label, BooleanSupplier allowed, ButtonWidget.PressAction callback) {
        action(label, left, layout.footerY(), layout.halfWidth(), allowed, callback);
    }
    protected void back() { back(false); }
    protected void back(boolean paired) {
        addDrawableChild(new PixelButton(Text.literal("返回游戏"), paired ? layout.right() : left, layout.footerY(), paired ? layout.halfWidth() : formWidth, button -> close()));
    }
    protected AgentClient agent() { return FabricP2wlan.AGENT.current().orElse(null); }
    protected boolean idle() {
        AgentClient current = agent();
        if (current == null) return false;
        boolean idle = current.snapshot().map(value -> "idle".equals(io.p2wlan.mc.common.RuntimeView.text(value.data(), "phase"))).orElse(false);
        return idle && FabricP2wlan.AGENT.isCurrent(current) && !current.isClosed();
    }
    protected AccountPresentation.State accountState() {
        AgentClient current = agent();
        if (current == null) return AccountPresentation.State.UNKNOWN;
        var snapshot = current.snapshot().orElse(null);
        var state = snapshot == null ? AccountPresentation.State.UNKNOWN : AccountPresentation.state(snapshot.data().get("account"));
        return FabricP2wlan.AGENT.isCurrent(current) && !current.isClosed() ? state : AccountPresentation.State.UNKNOWN;
    }
    protected boolean savedAccount() { return accountState() == AccountPresentation.State.SAVED_SESSION; }
    private boolean eligible(BooleanSupplier allowed) {
        AgentClient current = agent();
        return view.get() && current != null && FabricP2wlan.AGENT.isCurrent(current) && !current.isClosed()
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
            REQUESTS.submitTask(source, () -> FabricP2wlan.AGENT.isCurrent(agent) && scope.getAsBoolean(), captured::get,
                action -> client.execute(() -> { if (client.currentScreen != this) captured.set(false); action.run(); }),
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
            REQUESTS.submit(agent, operation, arguments, () -> FabricP2wlan.AGENT.isCurrent(agent) && scope.getAsBoolean(), captured::get,
                task -> client.execute(() -> { if (client.currentScreen != this) captured.set(false); task.run(); }),
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
                && FabricP2wlan.AGENT.isCurrent(current) && !current.isClosed();
    }
    protected String hint() { return "连接房间后，在 Minecraft 多人游戏中选择服务器。"; }
    protected String liveSummary() { return ""; }
    @Override public void removed() { view.set(false); }
    @Override public void close() { client.setScreen(parent); }
    @Override public boolean shouldPause() { return false; }
    private void summary(DrawContext context, String value, int y, int color) {
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(value, formWidth)), width / 2, y, color);
    }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        int header = top - 56, bottom = layout.messageY() + 33;
        context.fill(left - 9, header - 5, left + formWidth + 11, bottom + 2, 0x70000000);
        context.fill(left - 10, header - 6, left + formWidth + 10, bottom, 0xf2131a25);
        context.fill(left - 10, header - 6, left + formWidth + 10, header - 4, 0xff60a5fa);
        super.render(context, mouseX, mouseY, delta);
        context.drawTextWithShadow(textRenderer, "P2WLAN", left, header, 0xff60a5fa);
        context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(title.getString(), formWidth - 95), left, header + 16, 0xfff7f9fc);
        var helper = FabricP2wlan.AGENT.status();
        String helperText = "IPC: " + helper.phase() + (helper.reason() == null ? " / " + "native" : " / " + helper.reason());
        boolean ready = helper.phase() == io.p2wlan.mc.common.AgentLifecycle.Phase.READY;
        String state = !ready ? helper.reason() == null ? "助手启动中" : "启动失败" : savedAccount() ? "已登录 · 本机" : "未登录";
        int tone = !ready && helper.reason() != null ? 0xffffa39a : savedAccount() ? 0xff8de2b1 : 0xffb5c0d0;
        int badge = left + formWidth - textRenderer.getWidth(state);
        context.fill(badge - 8, header + 3, badge - 4, header + 7, tone);
        context.drawTextWithShadow(textRenderer, state, badge, header, tone);
        if (insecureControl()) context.drawTextWithShadow(textRenderer, "HTTP · 未加密", left, header + 40, 0xfff1c96a);
        for (Label label : labels) context.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(label.text(), labelWidth())), left, label.y(), 0xd0d0d0);
        String feedback = REQUESTS.busy() ? "正在处理…" : message.isEmpty() ? hint() : message;
        // Runtime/world truth remains visible even after a request result.
        String live = liveSummary();
        int reserved = live.isEmpty() ? 0 : 1;
        if (reserved != 0) context.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(live, formWidth)), left, layout.messageY(), 0xc1dfdf);
        var lines = textRenderer.wrapLines(Text.literal(feedback), formWidth);
        int count = Math.min(3 - reserved, lines.size());
        for (int index = 0; index < count; index++) context.drawTextWithShadow(textRenderer, lines.get(index), left, layout.messageY() + (index + reserved) * (textRenderer.fontHeight + 2), (REQUESTS.busy() || !message.isEmpty()) ? 0xfff1c96a : 0xff7f8ba0);
        if (mouseX >= left && mouseX < left + formWidth) {
            String tooltip = mouseY >= header && mouseY < header + 10 ? helperText
                    : mouseY >= header + 16 && mouseY < header + 26 ? "登录会话保存在本机私有目录，不保存密码。返回游戏或断开房间不会退出账号；Control 会在房间操作时重新检查授权。"
                    : insecureControl() && mouseY >= header + 40 && mouseY < header + 49 ? ControlTransport.BANNER
                    : mouseY >= layout.messageY() && (reserved != 0 || lines.size() > 3) ? (reserved == 0 ? feedback : live + "\n" + feedback) : null;
            if (tooltip != null) setTooltip(Text.literal(tooltip));
        }
    }
    static void shutdown() { REQUESTS.close(); }
}
