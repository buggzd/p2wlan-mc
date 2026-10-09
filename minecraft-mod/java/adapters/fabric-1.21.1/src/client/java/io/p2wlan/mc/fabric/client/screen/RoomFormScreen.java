package io.p2wlan.mc.fabric.client.screen;

import com.google.gson.JsonObject;
import io.p2wlan.mc.common.RoomPassword;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

final class RoomFormScreen extends AgentScreen {
    private final boolean create;
    private final RoomsScreen rooms;
    private TextFieldWidget value;
    private PasswordField password;
    private boolean done;
    RoomFormScreen(RoomsScreen parent, boolean create) { super(parent, create ? "新建 P2WLAN 房间 / Create" : "加入 P2WLAN 房间成员 / Join"); this.create = create; this.rooms = parent; }
    @Override protected void init() {
        if (password != null) password.setText("");
        super.init();
        value = field(top + 10, create ? "房间名 / Name" : "8 位房间码 / 8-digit code", create ? 80 : 8);
        password = addDrawableChild(new PasswordField(textRenderer, left, top + 38, formWidth, Text.literal("房间密码 / Room password (8–72 UTF-8 bytes)")));
        password.setPlaceholder(Text.literal("房间密码 / 8–72 UTF-8 bytes"));
        action(create ? "创建成员房间 / Create" : "加入成员房间 / Join", left, top + 68, formWidth, button -> {
            String secret = password.getText(); password.setText("");
            if (!RoomPassword.isValid(secret)) {
                message = "房间密码须为 8–72 UTF-8 字节 / Password: 8–72 UTF-8 bytes"; return;
            }
            JsonObject args = new JsonObject(); args.addProperty(create ? "name" : "code", value.getText());
            args.addProperty("password", secret);
            request(create ? "create_room" : "join_room", args, reply -> done = true);
        });
        back(top + 96);
    }
    @Override public void tick() { super.tick(); if (done && !REQUESTS.busy()) { done = false; rooms.refreshMembership(); client.setScreen(rooms); } }
    @Override public void removed() { if (password != null) password.setText(""); super.removed(); }
}
