package io.p2wlan.mc.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable native-owned room projection. Keep evidence and revalidate its
 * process owner before displaying or selecting; selection is an intent only. */
public record AccountCatalog(AgentReply evidence, List<Room> rooms) {
    public record Room(String id, String code, String name, String cidr, String role) {}
    public AccountCatalog { rooms = List.copyOf(rooms); }
    public static AccountCatalog parse(AgentReply reply) throws AgentException {
        if (reply == null) throw invalid();
        JsonObject data = reply.data();
        if (!JsonTrees.fields(data, Set.of("rooms")) || !data.get("rooms").isJsonArray()
                || data.getAsJsonArray("rooms").size() > 64) throw invalid();
        List<Room> rooms = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonElement element : data.getAsJsonArray("rooms")) {
            if (!element.isJsonObject()) throw invalid();
            JsonObject value = element.getAsJsonObject();
            if (!JsonTrees.fields(value, Set.of("id", "code", "name", "cidr", "role"))) throw invalid();
            String id = text(value, "id", 128);
            String code = text(value, "code", 8);
            String name = text(value, "name", 256);
            String cidr = text(value, "cidr", 18);
            String role = value.get("role").isJsonNull() ? null : text(value, "role", 64);
            if (!id.matches("room-[a-zA-Z0-9-]+") || !code.matches("[0-9]{8}") || !ids.add(id) || !cidr(cidr)) throw invalid();
            rooms.add(new Room(id, code, name, cidr, role));
        }
        return new AccountCatalog(reply, rooms);
    }
    private static String text(JsonObject value, String key, int maximum) throws AgentException {
        JsonElement field = value.get(key);
        if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isString()) throw invalid();
        String text = field.getAsString();
        if (text.getBytes(StandardCharsets.UTF_8).length > maximum) throw invalid();
        return text;
    }
    private static boolean cidr(String value) {
        if (!value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}/[0-9]{1,2}")) return false;
        String[] parts = value.split("[./]");
        for (int index = 0; index < 4; index++) if (Integer.parseInt(parts[index]) > 255) return false;
        return Integer.parseInt(parts[4]) <= 32;
    }
    private static AgentException invalid() { return new AgentException(AgentException.Code.INVALID_REPLY); }
}
