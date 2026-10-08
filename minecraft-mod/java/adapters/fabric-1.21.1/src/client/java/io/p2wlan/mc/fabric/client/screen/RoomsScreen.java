package io.p2wlan.mc.fabric.client.screen;

import com.google.gson.JsonObject;
import io.p2wlan.mc.common.AccountCatalog;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;

/** At most 64 fenced rows, paged four at a time. No membership/path authority. */
final class RoomsScreen extends AgentScreen {
    private AccountCatalog catalog;
    private int page;
    private int rows = 4;
    private boolean refresh = true;
    private String selected;
    RoomsScreen(Screen parent) { super(parent, "P2WLAN 房间成员 / Room membership"); }
    @Override protected void init() {
        super.init();
        rows = Math.max(1, Math.min(4, (height - top - 186) / 24));
        if (catalog != null) page = Math.min(page, Math.max(0, (catalog.rooms().size() - 1) / rows));
        if (catalog != null && (agent() == null || !agent().isCurrent(catalog.evidence()))) catalog = null;
        if (catalog != null) {
            for (int row = 0; row < rows && page * rows + row < catalog.rooms().size(); row++) {
                var room = catalog.rooms().get(page * rows + row);
                String label = room.code() + "  " + room.name();
                action(textRenderer.trimToWidth(label, formWidth - 10), left, top + row * 24, formWidth, button -> {
                    if (agent() == null || !agent().isCurrent(catalog.evidence())) { message = "CLOSED"; return; }
                    selected = room.id(); message = room.code() + " / " + room.cidr() + " / " + (room.role() == null ? "" : room.role());
                });
            }
        }
        int controls = top + rows * 24 + 4;
        action("<", left, controls, 35, button -> { if (page > 0) { page--; clearAndInit(); } });
        action(">", left + 40, controls, 35, button -> { if (catalog != null && (page + 1) * rows < catalog.rooms().size()) { page++; clearAndInit(); } });
        action("刷新 / Refresh", left + 80, controls, formWidth - 80, button -> refresh = true);
        action("新房间 / Create", left, controls + 26, formWidth / 2 - 3, button -> client.setScreen(new RoomFormScreen(this, true)));
        action("加入成员 / Join", left + formWidth / 2 + 3, controls + 26, formWidth / 2 - 3, button -> client.setScreen(new RoomFormScreen(this, false)));
        action("离开选中房间 / Leave selected", left, controls + 52, formWidth, button -> leave());
        action("退出账号 / Sign out", left, controls + 78, formWidth, button -> request("logout", new JsonObject(), reply -> { catalog = null; selected = null; refresh = false; message = "已退出 / Signed out"; }));
        back(controls + 104);
    }
    void refreshMembership() { refresh = true; }
    @Override public void tick() {
        super.tick();
        if (refresh && idle() && !REQUESTS.busy()) {
            refresh = false;
            request("rooms", new JsonObject(), reply -> { catalog = AccountCatalog.parse(reply); page = 0; selected = null; clearAndInit(); message = "房间数 / Rooms: " + catalog.rooms().size(); });
        }
    }
    private void leave() {
        if (selected == null || catalog == null || agent() == null || !agent().isCurrent(catalog.evidence())) { message = "请先选房间 / Select a room"; return; }
        JsonObject arguments = new JsonObject(); arguments.addProperty("room", selected);
        request("leave_room", arguments, reply -> { selected = null; refresh = true; });
    }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        if (catalog != null && agent() != null && agent().isCurrent(catalog.evidence())) {
            context.drawCenteredTextWithShadow(textRenderer, "Page " + (page + 1), width / 2, top - 15, 0xa0a0a0);
        }
    }
}
