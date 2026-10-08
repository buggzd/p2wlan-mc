package io.p2wlan.mc.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

public final class AccountCatalogTests {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static JsonObject room(String id) {
        JsonObject value = new JsonObject(); value.addProperty("id", "room-" + id); value.addProperty("code", "12345678");
        value.addProperty("name", "fixture room"); value.addProperty("cidr", "10.0.0.0/24"); value.add("role", JsonNull.INSTANCE); return value;
    }
    private static AgentReply reply(JsonArray values) {
        JsonObject data = new JsonObject(); data.add("rooms", values); return new AgentReply("a".repeat(32), 1, data);
    }
    private static void invalid(AgentReply reply) throws Exception {
        try { AccountCatalog.parse(reply); throw new AssertionError("malformed catalog admitted"); }
        catch (AgentException error) { check(error.code() == AgentException.Code.INVALID_REPLY); }
    }
    public static void main(String[] arguments) throws Exception {
        JsonArray values = new JsonArray(); values.add(room("one"));
        AgentReply evidence = reply(values);
        AccountCatalog catalog = AccountCatalog.parse(evidence);
        check(catalog.evidence() == evidence);
        check(catalog.rooms().get(0).code().equals("12345678"));
        check(catalog.rooms().get(0).role() == null);
        values.get(0).getAsJsonObject().addProperty("name", "mutated");
        check(catalog.rooms().get(0).name().equals("fixture room"));
        try { catalog.rooms().clear(); throw new AssertionError("mutable catalog"); }
        catch (UnsupportedOperationException expected) { checks++; }
        invalid(null);
        JsonArray duplicated = new JsonArray(); duplicated.add(room("one")); duplicated.add(room("one")); invalid(reply(duplicated));
        JsonArray maximum = new JsonArray(); for (int index = 0; index < 64; index++) maximum.add(room(Integer.toString(index)));
        check(AccountCatalog.parse(reply(maximum)).rooms().size() == 64);
        maximum.add(room("overflow")); invalid(reply(maximum));
        for (String field : new String[] {"id", "code", "name", "cidr", "role"}) {
            JsonObject value = room("one"); value.addProperty(field, 12); JsonArray items = new JsonArray(); items.add(value); invalid(reply(items));
        }
        for (String cidr : new String[] {"999.0.0.0/24", "10.0.0.0/33", "example.com/24", "10.0.0.0/999", "10.0.0.0/", "10.0.0.0/-1"}) {
            JsonObject value = room("one"); value.addProperty("cidr", cidr); JsonArray items = new JsonArray(); items.add(value); invalid(reply(items));
        }
        for (String name : new String[] {"x".repeat(257), "界".repeat(86)}) {
            JsonObject value = room("one"); value.addProperty("name", name); JsonArray items = new JsonArray(); items.add(value); invalid(reply(items));
        }
        JsonObject extra = room("one"); extra.addProperty("token", "must not be a room field"); JsonArray items = new JsonArray(); items.add(extra); invalid(reply(items));
        check(AccountCatalog.parse(reply(new JsonArray())).rooms().isEmpty());
        System.out.println("PASS account catalog: " + checks + " explicit checks");
    }
}
