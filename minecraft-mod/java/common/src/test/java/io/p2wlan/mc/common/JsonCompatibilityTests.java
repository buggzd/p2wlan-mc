package io.p2wlan.mc.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs the production snapshots and strict schemas on actual Gson 2.8.0. */
public final class JsonCompatibilityTests {
    private static int checks;
    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("JSON_COMPATIBILITY_" + checks);
    }
    private static void reject(JsonObject value) {
        try { JsonTrees.copy(value); throw new AssertionError("JSON_COPY_ACCEPTED_INVALID_TREE"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().equals("INVALID_JSON_SNAPSHOT")); }
    }
    public static void main(String[] args) throws Exception {
        var nested = new JsonObject(); nested.addProperty("text", "你好🌲");
        var array = new JsonArray(); array.add(nested);
        var number = new AtomicInteger(7);
        var input = new JsonObject(); input.add("values", array); input.addProperty("number", number);
        var reply = new AgentReply("a".repeat(32), 1, input);
        var snapshot = new StatusSnapshot("a".repeat(32), 2, input);
        nested.addProperty("text", "changed"); number.set(9); array.add(false);
        check(reply.data().getAsJsonArray("values").size() == 1);
        check(reply.data().getAsJsonArray("values").get(0).getAsJsonObject().get("text").getAsString().equals("你好🌲"));
        check(reply.data().get("number").getAsInt() == 7);
        check(snapshot.data().get("number").getAsInt() == 7);
        var exposed = reply.data(); exposed.getAsJsonArray("values").get(0).getAsJsonObject().remove("text");
        check(reply.data().getAsJsonArray("values").get(0).getAsJsonObject().has("text"));
        check(JsonTrees.fields(reply.data(), Set.of("values", "number")));
        check(!JsonTrees.fields(reply.data(), Set.of("values")));
        check(!JsonTrees.fields(reply.data(), Set.of("values", "other")));
        var empty = new JsonObject(); check(JsonTrees.fields(empty, Set.of()));
        var cyclic = new JsonObject(); cyclic.add("self", cyclic); reject(cyclic);
        var huge = new JsonObject(); huge.addProperty("value", "x".repeat(FrameCodec.MAX_FRAME_BYTES)); reject(huge);
        var invalid = new JsonObject(); invalid.addProperty("value", "\ud800"); reject(invalid);
        var invalidNumber = new JsonObject(); invalidNumber.addProperty("value", Double.NaN); reject(invalidNumber);
        var authenticated = new JsonObject(); authenticated.addProperty("authenticated", false);
        check(AccountPresentation.state(authenticated) == AccountPresentation.State.NO_SESSION);
        var rooms = new JsonObject(); rooms.add("rooms", new JsonArray());
        check(AccountCatalog.parse(new AgentReply("a".repeat(32), 3, rooms)).rooms().isEmpty());
        ProtocolTests.main(new String[0]);
        if (checks != 15) throw new AssertionError("JSON_COMPATIBILITY_COUNT_" + checks);
        System.out.println("PASS Gson 2.8.0 compatibility: " + checks + " explicit copy/schema checks plus protocol fencing");
    }
}
