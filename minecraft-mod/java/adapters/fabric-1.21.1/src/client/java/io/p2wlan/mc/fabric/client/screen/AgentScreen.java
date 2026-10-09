package io.p2wlan.mc.fabric.client.screen;

import com.google.gson.JsonObject;
import io.p2wlan.mc.common.AgentClient;
import io.p2wlan.mc.common.AgentException;
import io.p2wlan.mc.common.AgentReply;
import io.p2wlan.mc.common.ControlTransport;
import io.p2wlan.mc.common.UiCompletionGate;
import io.p2wlan.mc.fabric.FabricP2wlan;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final List<ButtonWidget> actions = new ArrayList<>();
    private AtomicBoolean view = new AtomicBoolean(false);
    protected AgentScreen(Screen parent, String title) { super(Text.literal(title)); this.parent = parent; }
    @Override protected void init() {
        view.set(false);
        view = new AtomicBoolean(true);
        actions.clear();
        formWidth = Math.min(320, Math.max(160, width - 30));
        left = (width - formWidth) / 2;
        top = Math.max(38, (height - 250) / 2);
    }
    protected TextFieldWidget field(int y, String name, int maximum) {
        TextFieldWidget field = addDrawableChild(new TextFieldWidget(textRenderer, left, y, formWidth, 20, Text.literal(name)));
        field.setMaxLength(maximum);
        field.setPlaceholder(Text.literal(name));
        return field;
    }
    protected ButtonWidget action(String label, int x, int y, int size, ButtonWidget.PressAction callback) {
        ButtonWidget button = addDrawableChild(ButtonWidget.builder(Text.literal(label), callback).dimensions(x, y, size, 20).build());
        actions.add(button);
        return button;
    }
    protected void back(int y) { addDrawableChild(ButtonWidget.builder(Text.literal("返回 / Back"), button -> close()).dimensions(left, y, formWidth, 20).build()); }
    protected AgentClient agent() { return FabricP2wlan.AGENT.current().orElse(null); }
    protected boolean idle() {
        AgentClient agent = agent();
        if (agent == null) return false;
        return agent.snapshot().map(value -> {
            var phase = value.data().get("phase");
            return phase != null && phase.isJsonPrimitive() && phase.getAsJsonPrimitive().isString()
                    && phase.getAsString().equals("idle");
        }).orElse(false);
    }
    protected java.util.function.BooleanSupplier viewFence() { AtomicBoolean captured = view; return captured::get; }
    protected interface TaskAction<T> { void accept(T value) throws AgentException; }
    protected <T> void task(java.util.function.Supplier<java.util.concurrent.CompletableFuture<T>> source, java.util.function.BooleanSupplier scope, TaskAction<T> callback) {
        AgentClient agent = agent();
        if (agent == null) { message = "NOT_READY"; return; }
        AtomicBoolean captured = view;
        message = "处理中 / Working…";
        try {
            REQUESTS.submitTask(source, () -> FabricP2wlan.AGENT.isCurrent(agent) && scope.getAsBoolean(), captured::get,
                action -> client.execute(() -> { if (client.currentScreen != this) captured.set(false); action.run(); }),
                (value, error) -> {
                    if (error != null) { message = UiCompletionGate.reason(error); return; }
                    try { callback.accept(value); } catch (AgentException invalid) { message = UiCompletionGate.reason(invalid); }
                });
        } catch (AgentException invalid) { message = UiCompletionGate.reason(invalid); }
    }
    protected boolean actionsEnabled() { return idle(); }
    protected interface ReplyAction { void accept(AgentReply reply) throws AgentException; }
    protected void request(String operation, JsonObject arguments, ReplyAction callback) {
        request(operation, arguments, () -> true, callback);
    }
    protected void request(String operation, JsonObject arguments, java.util.function.BooleanSupplier scope, ReplyAction callback) {
        AgentClient agent = agent();
        if (agent == null) { message = "助手未就绪 / Helper not ready"; return; }
        AtomicBoolean captured = view;
        message = "处理中 / Working…";
        try {
            REQUESTS.submit(agent, operation, arguments, () -> FabricP2wlan.AGENT.isCurrent(agent) && scope.getAsBoolean(), captured::get,
                task -> client.execute(() -> { if (client.currentScreen == this) task.run(); else { captured.set(false); task.run(); } }),
                (reply, error) -> {
                    if (error != null) { message = UiCompletionGate.reason(error); return; }
                    try { callback.accept(reply); }
                    catch (AgentException invalid) { message = UiCompletionGate.reason(invalid); }
                });
        } catch (AgentException error) { message = UiCompletionGate.reason(error); }
    }
    @Override public void tick() {
        super.tick();
        boolean enabled = agent() != null && actionsEnabled() && !REQUESTS.busy();
        for (ButtonWidget button : actions) button.active = enabled;
    }
    /** Risk display comes from the exact current native snapshot, not a cached
     * UI account flag. Revalidate the lifecycle owner at the display boundary. */
    protected boolean insecureControl() {
        AgentClient current = agent();
        if (current == null) return false;
        var snapshot = current.snapshot().orElse(null);
        return snapshot != null && ControlTransport.accountIsPlaintext(snapshot.data().get("account"))
                && FabricP2wlan.AGENT.isCurrent(current) && !current.isClosed();
    }
    @Override public void removed() { view.set(false); }
    @Override public void close() { client.setScreen(parent); }
    @Override public boolean shouldPause() { return false; }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 15, 0xffffff);
        if (insecureControl()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(ControlTransport.BANNER, width - 20)), width / 2, 26, 0xffaa00);
            if (mouseY >= 26 && mouseY < 26 + textRenderer.fontHeight) {
                context.drawTooltip(textRenderer, Text.literal(ControlTransport.BANNER), mouseX, mouseY);
            }
        }
        var state = FabricP2wlan.AGENT.status();
        String helper = "IPC: " + state.phase() + (state.reason() == null ? "" : " / " + state.reason());
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(helper, width - 20)), width / 2, height - 34, 0xa0a0a0);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(message, width - 20)), width / 2, height - 20, 0xffcc66);
    }
    static void shutdown() { REQUESTS.close(); }
}
