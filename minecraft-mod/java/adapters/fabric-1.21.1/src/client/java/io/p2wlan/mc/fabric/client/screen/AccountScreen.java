package io.p2wlan.mc.fabric.client.screen;

import com.google.gson.JsonObject;
import io.p2wlan.mc.common.*;
import io.p2wlan.mc.fabric.FabricP2wlan;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Login only when needed; saved native tokens, never persisted passwords. */
public final class AccountScreen extends AgentScreen {
    private TextFieldWidget server, identifier;
    private PasswordField password;
    private boolean edit;
    public AccountScreen(Screen parent) { super(parent, "账号设置"); }
    public static Screen entry(Screen parent) {
        for (int depth=0; depth<8 && parent instanceof AgentScreen view; depth++) parent=view.parent;
        var agent = FabricP2wlan.AGENT.current().orElse(null);
        var runtime = RuntimeView.active(agent);
        if (runtime.isPresent()) return new RuntimeScreen(parent, runtime.get());
        var account = agent == null ? null : agent.snapshot().map(value -> value.data().get("account")).orElse(null);
        if (agent != null && FabricP2wlan.AGENT.isCurrent(agent) && !agent.isClosed()
                && AccountPresentation.state(account) == AccountPresentation.State.SAVED_SESSION) return new RoomsScreen(parent);
        return new AccountScreen(parent);
    }
    @Override protected void init() {
        String enteredServer = server == null ? savedServer() : server.getText();
        String enteredIdentifier = identifier == null ? "" : identifier.getText();
        if (password != null) password.setText("");
        super.init();
        if (savedAccount() && !edit) {
            action("重新登录", left, layout.row(2), layout.halfWidth(), button -> { edit=true; clearAndInit(); });
            action("退出账号", layout.right(), layout.row(2), layout.halfWidth(), button -> request("logout", new JsonObject(), reply -> {
                if (AccountPresentation.state(reply.data()) != AccountPresentation.State.NO_SESSION) throw new AgentException(AgentException.Code.INVALID_REPLY);
                edit=true; clearAndInit(); message="已退出账号。";
            }));
            footerAction("我的房间", this::savedAccount, button -> client.setScreen(new RoomsScreen(parent))); back(true); return;
        }
        server = field(layout.row(0), "Control 地址", 2048);
        server.setPlaceholder(Text.literal("http:// 或 https://")); server.setText(enteredServer);
        identifier = field(layout.row(1), "账号邮箱", 320);
        identifier.setPlaceholder(Text.literal("P2WLAN 邮箱")); identifier.setText(enteredIdentifier);
        password = passwordField(layout.row(2), "密码");
        password.setPlaceholder(Text.literal("P2WLAN 密码"));
        action("登录", left, layout.row(3), layout.halfWidth(), this::canLogin, button -> authenticate("login"));
        action("注册", layout.right(), layout.row(3), layout.halfWidth(), this::canRegister, button -> authenticate("register"));
        back(); setInitialFocus(server);
    }
    private String savedServer() {
        var current = agent();
        return current != null && FabricP2wlan.AGENT.isCurrent(current) && !current.isClosed()
                ? current.snapshot().map(value -> AccountPresentation.server(value.data().get("account"))).orElse("") : "";
    }
    private boolean filled() { return server != null && identifier != null && password != null && !server.getText().isBlank() && !identifier.getText().isBlank() && !password.getText().isEmpty(); }
    private boolean canLogin() { return AccountPresentation.canAuthenticate(accountState(), idle(), REQUESTS.busy(), filled()); }
    private boolean canRegister() { return AccountPresentation.canRegister(accountState(), idle(), REQUESTS.busy(), filled()); }
    @Override protected boolean insecureControl() { return server != null && ControlTransport.isPlaintext(server.getText()) || super.insecureControl(); }
    @Override protected String hint() { return savedAccount() ? "返回游戏或断开房间不会退出账号。" : "登录一次即可；密码仅用于这次请求。"; }
    private void authenticate(String operation) {
        if (!("register".equals(operation) ? canRegister() : canLogin())) return;
        JsonObject arguments = new JsonObject();
        arguments.addProperty("server", server.getText()); arguments.addProperty("identifier", identifier.getText());
        arguments.addProperty("password", password.getText()); password.setText("");
        request(operation, arguments, reply -> {
            if (AccountPresentation.state(reply.data()) != AccountPresentation.State.SAVED_SESSION) throw new AgentException(AgentException.Code.INVALID_REPLY);
            client.setScreen(new RoomsScreen(parent));
        });
    }
    @Override public void tick() {
        super.tick();
        if (savedAccount() && !edit && server!=null && !REQUESTS.busy()) { client.setScreen(new RoomsScreen(parent)); return; }
        var runtime = RuntimeView.active(agent());
        if (runtime.isPresent()) client.setScreen(new RuntimeScreen(parent, runtime.get()));
    }
    @Override public void render(DrawContext draw, int x, int y, float delta) {
        super.render(draw,x,y,delta);
        if (savedAccount() && !edit) {
            draw.drawTextWithShadow(textRenderer,"登录会话已保存在本机",left,layout.row(0)+3,0xff8de2b1);
            draw.drawTextWithShadow(textRenderer,textRenderer.trimToWidth(savedServer(),formWidth),left,layout.row(1)+3,0xffb5c0d0);
        }
    }
    @Override public void removed() { if (password != null) password.setText(""); super.removed(); }
    public static boolean requestsBusy() { return REQUESTS.busy(); }
    public static void shutdownRequests() { shutdown(); }
}
