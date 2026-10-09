package io.p2wlan.mc.fabric.client.screen;

import com.google.gson.JsonObject;
import io.p2wlan.mc.common.AccountPresentation;
import io.p2wlan.mc.common.AgentException;
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
    public AccountScreen(Screen parent) { super(parent, "P2WLAN 账号 / Account"); }
    public static Screen entry(Screen parent) {
        var runtime = RuntimeView.active(FabricP2wlan.AGENT.current().orElse(null));
        return runtime.<Screen>map(value -> new RuntimeScreen(parent, value)).orElseGet(() -> new AccountScreen(parent));
    }
    @Override protected void init() {
        // Preserve only non-secret input across resize/back. Passwords are cleared.
        String enteredServer = server == null ? "" : server.getText();
        String enteredIdentifier = identifier == null ? "" : identifier.getText();
        if (password != null) password.setText("");
        super.init();
        server = field(layout.row(0), "Control URL", 2048);
        server.setPlaceholder(Text.literal("http:// 或 https://")); server.setText(enteredServer);
        identifier = field(layout.row(1), "账号邮箱", 320);
        identifier.setPlaceholder(Text.literal("P2WLAN 邮箱 / Email")); identifier.setText(enteredIdentifier);
        password = passwordField(layout.row(2), "密码");
        password.setPlaceholder(Text.literal("P2WLAN 密码 / Password"));
        action("登录 / Sign in", left, layout.row(3), layout.halfWidth(), this::canLogin, button -> authenticate("login"));
        action("注册 / Register", layout.right(), layout.row(3), layout.halfWidth(), this::canRegister, button -> authenticate("register"));
        footerAction("我的房间 / Rooms", this::savedAccount, button -> client.setScreen(new RoomsScreen(this)));
        back(true); setInitialFocus(server);
    }
    private boolean filled() {
        return server != null && identifier != null && password != null && !server.getText().isBlank()
                && !identifier.getText().isBlank() && !password.getText().isEmpty();
    }
    private boolean canLogin() { return AccountPresentation.canAuthenticate(accountState(), idle(), REQUESTS.busy(), filled()); }
    private boolean canRegister() { return AccountPresentation.canRegister(accountState(), idle(), REQUESTS.busy(), filled()); }
    @Override protected boolean insecureControl() {
        return server != null && ControlTransport.isPlaintext(server.getText()) || super.insecureControl();
    }
    @Override protected String hint() {
        return savedAccount() ? "本地会话已保存，服务器有效性未核验；登录可更新会话，新账号请先退出。"
                : "先填 Control URL、邮箱和密码。已注册请登录；注册结果不明时不要重复注册。";
    }
    private void authenticate(String operation) {
        if (!("register".equals(operation) ? canRegister() : canLogin())) return;
        JsonObject arguments = new JsonObject();
        arguments.addProperty("server", server.getText()); arguments.addProperty("identifier", identifier.getText());
        arguments.addProperty("password", password.getText()); password.setText("");
        request(operation, arguments, reply -> {
            if (AccountPresentation.state(reply.data()) != AccountPresentation.State.SAVED_SESSION) throw new AgentException(AgentException.Code.INVALID_REPLY);
            message = ("register".equals(operation) ? "注册" : "登录") + "成功，会话已保存；点击“我的房间”继续。";
        });
    }
    @Override public void tick() {
        super.tick();
        var runtime = RuntimeView.active(agent());
        if (runtime.isPresent()) client.setScreen(new RuntimeScreen(parent, runtime.get()));
    }
    @Override public void removed() { if (password != null) password.setText(""); super.removed(); }
    /** Application shutdown cancels presentation, not persisted native intent. */
    public static void shutdownRequests() { shutdown(); }
}
