package io.p2wlan.mc.game.client.screen;

import com.google.gson.JsonObject;
import io.p2wlan.mc.common.RoomPassword;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import io.p2wlan.mc.common.AccountPresentation;

final class RoomFormScreen extends AgentScreen {
    private final boolean create;
    private final RoomsScreen rooms;
    private EditBox value;
    private PasswordField password;
    private boolean done;
    RoomFormScreen(RoomsScreen parent, boolean create) { super(parent, create ? "新建 P2WLAN 房间 / Create" : "加入 P2WLAN 房间 / Join"); this.create = create; this.rooms = parent; }
    @Override protected boolean actionsEnabled() { return AccountPresentation.canUseAccount(accountState(), idle(), REQUESTS.busy()); }
    @Override protected void init() {
        String entered = value == null ? "" : value.getValue();
        if (password != null) password.setValue("");
        super.init();
        value = field(layout.row(0), create ? "房间名" : "8 位房间码", create ? 80 : 8); value.setValue(entered);
        password = passwordField(layout.row(1), "房间密码");
        password.setHint(Component.literal("8–72 UTF-8 字节"));
        action(create ? "创建房间 / Create" : "加入房间 / Join", left, layout.row(2), formWidth, this::validInput, button -> {
            String secret = password.getValue(); password.setValue("");
            if (!RoomPassword.isValid(secret)) { message = "房间密码须为 8–72 UTF-8 字节。"; return; }
            JsonObject args = new JsonObject(); args.addProperty(create ? "name" : "code", value.getValue()); args.addProperty("password", secret);
            request(create ? "create_room" : "join_room", args, reply -> done = true);
        });
        back(); setInitialFocus(value);
    }
    private boolean validInput() {
        return value != null && password != null && (create ? !value.getValue().isBlank() : value.getValue().matches("[0-9]{8}"))
                && RoomPassword.isValid(password.getValue());
    }
    @Override protected String hint() { return "房间密码须为 8–72 UTF-8 字节；成功后返回房间列表。"; }
    @Override public void tick() { super.tick(); if (done && !REQUESTS.busy()) { done = false; rooms.refreshMembership(); minecraft.setScreen(rooms); } }
    @Override public void removed() { if (password != null) password.setValue(""); super.removed(); }
}
