package io.p2wlan.mc.fabric.client.screen;

import com.google.gson.JsonObject;
import io.p2wlan.mc.common.RuntimeView;
import io.p2wlan.mc.common.ControlTransport;
import io.p2wlan.mc.fabric.FabricP2wlan;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** P2WLAN credentials, never Minecraft/Microsoft credentials or command args. */
public final class AccountScreen extends AgentScreen {
    private TextFieldWidget server, identifier;
    private PasswordField password;
    private boolean roomsNext;
    public AccountScreen(Screen parent) { super(parent, "P2WLAN 账号 / Account"); }
    public static Screen entry(Screen parent) {
        var runtime = RuntimeView.active(FabricP2wlan.AGENT.current().orElse(null));
        return runtime.<Screen>map(value -> new RuntimeScreen(parent, value)).orElseGet(() -> new AccountScreen(parent));
    }
    @Override protected void init() {
        if (password != null) password.setText("");
        super.init();
        server = field(top + 0, "Control URL (http:// or https://)", 2048);
        identifier = field(top + 26, "P2WLAN 邮箱 / Identifier", 320);
        password = addDrawableChild(new PasswordField(textRenderer, left, top + 52, formWidth, Text.literal("P2WLAN 密码 / Password")));
        password.setPlaceholder(Text.literal("P2WLAN 密码 / Password"));
        action("登录 / Sign in", left, top + 78, formWidth / 2 - 3, button -> authenticate("login"));
        action("注册 / Register", left + formWidth / 2 + 3, top + 78, formWidth / 2 - 3, button -> authenticate("register"));
        action("已保存账号的房间 / Saved account rooms", left, top + 104, formWidth, button -> client.setScreen(new RoomsScreen(this)));
        back(top + 130);
        setInitialFocus(server);
    }
    @Override protected boolean insecureControl() {
        return server != null && ControlTransport.isPlaintext(server.getText()) || super.insecureControl();
    }
    private void authenticate(String operation) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("server", server.getText());
        arguments.addProperty("identifier", identifier.getText());
        arguments.addProperty("password", password.getText());
        password.setText("");
        request(operation, arguments, reply -> { roomsNext = true; message = "登录成功 / Signed in"; });
    }
    @Override public void tick() {
        super.tick();
        var runtime = RuntimeView.active(agent());
        if (runtime.isPresent()) { client.setScreen(new RuntimeScreen(parent, runtime.get())); return; }
        if (roomsNext && !REQUESTS.busy()) { roomsNext = false; client.setScreen(new RoomsScreen(this)); }
    }
    @Override public void removed() { if (password != null) password.setText(""); super.removed(); }
    /** Application shutdown cancels presentation, not persisted native intent. */
    public static void shutdownRequests() { shutdown(); }
}
