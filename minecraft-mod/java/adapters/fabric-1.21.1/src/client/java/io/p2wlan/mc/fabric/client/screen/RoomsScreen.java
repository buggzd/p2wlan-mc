package io.p2wlan.mc.fabric.client.screen;

import com.google.gson.JsonObject;
import io.p2wlan.mc.common.*;
import net.minecraft.client.gui.screen.Screen;

/** Room membership and overlay intent only. One click connects; no game picker. */
final class RoomsScreen extends AgentScreen {
    private AccountCatalog catalog;
    private int page, rows=4;
    private boolean refresh=true;
    RoomsScreen(Screen parent) { super(parent,"选择局域网房间"); }
    @Override protected boolean actionsEnabled() { return AccountPresentation.canUseAccount(accountState(),idle(),REQUESTS.busy()); }
    private boolean catalogCurrent() { var current=agent(); return catalog!=null && current!=null && current.isCurrent(catalog.evidence()); }
    @Override protected void init() {
        super.init(); rows=layout.listRows(2,4);
        if (!catalogCurrent()) catalog=null;
        if (catalog!=null) {
            page=Math.min(page,Math.max(0,(catalog.rooms().size()-1)/rows));
            for (int row=0; row<rows && page*rows+row<catalog.rooms().size(); row++) {
                var room=catalog.rooms().get(page*rows+row);
                action(textRenderer.trimToWidth("连接 · "+room.name()+"  ["+room.code()+"]",formWidth-12),left,layout.row(row),formWidth,this::catalogCurrent,button->connect(room.id()));
            }
        }
        int controls=layout.controls(rows);
        action("加入房间",left,controls,layout.halfWidth(),button->client.setScreen(new RoomFormScreen(this,false)));
        action("创建房间",layout.right(),controls,layout.halfWidth(),button->client.setScreen(new RoomFormScreen(this,true)));
        action("◀",left,controls+26,32,()->catalogCurrent() && page>0,button->{page--;clearAndInit();});
        action("▶",left+40,controls+26,32,()->catalogCurrent() && (page+1)*rows<catalog.rooms().size(),button->{page++;clearAndInit();});
        String pages=catalog==null?"":" · "+(page+1)+"/"+Math.max(1,(catalog.rooms().size()+rows-1)/rows);
        action("刷新"+pages,left+80,controls+26,formWidth-80,button->refresh=true);
        footerAction("账号设置",this::savedAccount,button->client.setScreen(new AccountScreen(parent))); back(true);
    }
    void refreshMembership() { refresh=true; }
    @Override public void tick() {
        super.tick();
        if (refresh && idle() && savedAccount() && !REQUESTS.busy()) {
            refresh=false;
            request("rooms",new JsonObject(),reply->{catalog=AccountCatalog.parse(reply);page=0;clearAndInit();message=catalog.rooms().isEmpty()?"还没有房间，先加入或创建一个。":"点击房间即可连接虚拟局域网。";});
        }
    }
    private void connect(String selected) {
        if (!catalogCurrent() || catalog.rooms().stream().noneMatch(room->room.id().equals(selected))) { message="房间列表已更新，请重新选择。"; return; }
        JsonObject arguments=new JsonObject();arguments.addProperty("room",selected);
        request("connect_room",arguments,reply->{
            String identity=RuntimeView.text(reply.data(),"runtime");
            if (identity==null || !identity.matches("[a-f0-9]{32}") || !"starting".equals(RuntimeView.text(reply.data(),"phase"))) throw new AgentException(AgentException.Code.INVALID_REPLY);
            client.setScreen(new RuntimeScreen(parent,new RuntimeView(reply.owner(),identity)));
        });
    }
    @Override protected String hint() { return "连接房间后，在多人游戏中选择服务器。"; }
}
