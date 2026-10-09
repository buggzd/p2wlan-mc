package io.p2wlan.mc.fabric.client.screen;

import com.google.gson.JsonObject;
import io.p2wlan.mc.common.AccountCatalog;
import io.p2wlan.mc.common.AccountPresentation;
import io.p2wlan.mc.common.AgentException;
import io.p2wlan.mc.common.RuntimeView;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;

/** At most 64 fenced rows, paged four at a time. No membership/path authority. */
final class RoomsScreen extends AgentScreen {
    private AccountCatalog catalog;
    private int page, rows = 4;
    private boolean refresh = true;
    private String selected;
    RoomsScreen(Screen parent) { super(parent, "P2WLAN 房间 / Rooms"); }
    @Override protected boolean actionsEnabled() { return AccountPresentation.canUseAccount(accountState(), idle(), REQUESTS.busy()); }
    private boolean catalogCurrent() { var current = agent(); return catalog != null && current != null && current.isCurrent(catalog.evidence()); }
    private boolean selectionCurrent() {
        return selected != null && catalogCurrent() && catalog.rooms().stream().anyMatch(room -> room.id().equals(selected));
    }
    @Override protected void init() {
        super.init(); rows = layout.listRows(3, 4);
        if (!catalogCurrent()) { catalog = null; selected = null; }
        if (catalog != null) page = Math.min(page, Math.max(0, (catalog.rooms().size() - 1) / rows));
        if (catalog != null) {
            for (int row = 0; row < rows && page * rows + row < catalog.rooms().size(); row++) {
                var room = catalog.rooms().get(page * rows + row);
                String label = (room.id().equals(selected) ? "▶ " : "") + room.code() + "  " + room.name();
                action(textRenderer.trimToWidth(label, formWidth - 10), left, layout.row(row), formWidth, this::catalogCurrent, button -> {
                    selected = room.id(); message = "已选房间：" + room.code() + " / " + room.cidr(); clearAndInit();
                });
            }
        }
        int controls = layout.controls(rows);
        action("上一页", left, controls, 42, () -> catalogCurrent() && page > 0, button -> { page--; clearAndInit(); });
        action("下一页", left + 50, controls, 42, () -> catalogCurrent() && (page + 1) * rows < catalog.rooms().size(), button -> { page++; clearAndInit(); });
        String pages = catalog == null ? "" : " · " + (page + 1) + "/" + Math.max(1, (catalog.rooms().size() + rows - 1) / rows);
        action("刷新房间" + pages, left + 100, controls, formWidth - 100, button -> refresh = true);
        action("创建房间 / Create", left, controls + 26, layout.halfWidth(), button -> client.setScreen(new RoomFormScreen(this, true)));
        action("加入房间 / Join", layout.right(), controls + 26, layout.halfWidth(), button -> client.setScreen(new RoomFormScreen(this, false)));
        action("连接选中房间", left, controls + 52, layout.halfWidth(), this::selectionCurrent, button -> connect());
        action("离开选中房间", layout.right(), controls + 52, layout.halfWidth(), this::selectionCurrent, button -> leave());
        footerAction("退出账号 / Sign out", this::savedAccount, button -> request("logout", new JsonObject(), reply -> {
            if (AccountPresentation.state(reply.data()) != AccountPresentation.State.NO_SESSION) throw new AgentException(AgentException.Code.INVALID_REPLY);
            catalog = null; selected = null; refresh = false;
            if (parent instanceof AgentScreen screen) screen.message = "退出账号成功，本地登录会话已清除。";
            client.setScreen(parent);
        }));
        back(true);
    }
    void refreshMembership() { refresh = true; }
    @Override public void tick() {
        super.tick();
        if (refresh && idle() && savedAccount() && !REQUESTS.busy()) {
            refresh = false;
            request("rooms", new JsonObject(), reply -> {
                catalog = AccountCatalog.parse(reply); page = 0; selected = null; clearAndInit();
                message = "读取房间成功，共 " + catalog.rooms().size() + " 个；请选择房间再连接。";
            });
        }
    }
    private void connect() {
        if (!selectionCurrent()) { message = "请先选择当前账号的房间。"; return; }
        JsonObject arguments = new JsonObject(); arguments.addProperty("room", selected);
        request("connect_room", arguments, reply -> {
            String identity = RuntimeView.text(reply.data(), "runtime");
            if (identity == null || !identity.matches("[a-f0-9]{32}") || !"starting".equals(RuntimeView.text(reply.data(), "phase"))) throw new AgentException(AgentException.Code.INVALID_REPLY);
            // Intent only: the runtime screen awaits its matching native snapshot.
            client.setScreen(new RuntimeScreen(this, new RuntimeView(reply.owner(), identity)));
        });
    }
    private void leave() {
        if (!selectionCurrent()) { message = "请先选择当前账号的房间。"; return; }
        JsonObject arguments = new JsonObject(); arguments.addProperty("room", selected);
        request("leave_room", arguments, reply -> { selected = null; refresh = true; });
    }
    @Override protected String hint() {
        return !savedAccount() ? "尚无可用的本地登录会话；请返回账号页登录。"
                : "先选择房间再连接；连接房间不代表对方游戏已经就绪。";
    }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        if (!catalogCurrent() || catalog.rooms().isEmpty()) {
            String empty = !catalogCurrent() ? "房间列表待读取 / Not loaded" : "没有房间，可创建或加入 / No rooms";
            context.drawCenteredTextWithShadow(textRenderer, textRenderer.trimToWidth(empty, formWidth), width / 2, top + 6, 0xa0a0a0);
        }
    }
}
