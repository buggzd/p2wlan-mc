package io.p2wlan.mc.account;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.p2wlan.mc.common.AccountCatalog;
import io.p2wlan.mc.common.ControlTransport;
import io.p2wlan.mc.common.AgentClient;
import io.p2wlan.mc.common.AgentException;
import io.p2wlan.mc.common.AgentLifecycle;
import io.p2wlan.mc.common.AgentReply;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/** Bounded terminal receipts, NOT a live account/room cache. Native remains
 * authoritative. Do not print URLs, identifiers, JWTs or arbitrary room names. */
final class AccountReceipt {
    private AccountReceipt() {}
    static List<String> capture(AccountCommand.Action action, AgentLifecycle owner, AgentClient client,
                                AgentReply reply) throws AgentException, AccountCommand.Failure {
        if (!owner.isCurrent(client) || !client.isCurrent(reply)) throw new AccountCommand.Failure("RECEIPT_SCOPE_CHANGED");
        List<String> result = parse(action, reply);
        if (!owner.isCurrent(client) || !client.isCurrent(reply)) throw new AccountCommand.Failure("RECEIPT_SCOPE_CHANGED");
        return result;
    }
    static List<String> parse(AccountCommand.Action action, AgentReply reply) throws AgentException {
        if (reply == null) throw invalid();
        JsonObject data = reply.data();
        return switch (action) {
            case STATUS -> {
                if (!data.keySet().equals(Set.of("phase", "failure", "account"))
                        || !text(data.get("phase"), 4).equals("idle") || !data.get("account").isJsonObject()) throw invalid();
                JsonElement failure = data.get("failure");
                if (!failure.isJsonNull() && !text(failure, 48).matches("[A-Z][A-Z0-9_]{0,47}")) throw invalid();
                boolean signedIn = account(data.getAsJsonObject("account"));
                String receipt = "STATUS " + (signedIn ? "AUTHENTICATED" : "SIGNED_OUT");
                yield ControlTransport.accountIsPlaintext(data.get("account")) ? List.of(ControlTransport.WARNING, receipt) : List.of(receipt);
            }
            case LOGIN, REGISTER, LOGOUT -> {
                if (account(data) != (action != AccountCommand.Action.LOGOUT)) throw invalid();
                String receipt = action.name() + "_OK";
                yield ControlTransport.accountIsPlaintext(data) ? List.of(ControlTransport.WARNING, receipt) : List.of(receipt);
            }
            case ROOMS -> {
                var catalog = AccountCatalog.parse(reply);
                yield catalog.rooms().isEmpty() ? List.of("NO_ROOMS")
                        : catalog.rooms().stream().map(room -> "ROOM " + room.code()).toList();
            }
            case CREATE_ROOM, JOIN_ROOM -> {
                if (!data.keySet().equals(Set.of("room"))) throw invalid();
                JsonObject projection = new JsonObject();
                var rooms = new com.google.gson.JsonArray();
                rooms.add(data.get("room")); projection.add("rooms", rooms);
                var catalog = AccountCatalog.parse(new AgentReply(reply.owner(), reply.id(), projection));
                yield List.of((action == AccountCommand.Action.CREATE_ROOM ? "ROOM_CREATED " : "ROOM_JOINED ")
                        + catalog.rooms().get(0).code());
            }
        };
    }
    private static boolean account(JsonObject value) throws AgentException {
        JsonElement authenticated = value.get("authenticated");
        if (authenticated == null || !authenticated.isJsonPrimitive()
                || !authenticated.getAsJsonPrimitive().isBoolean()) throw invalid();
        boolean signedIn = authenticated.getAsBoolean();
        if (!value.keySet().equals(signedIn ? Set.of("authenticated", "server", "user_id") : Set.of("authenticated"))) throw invalid();
        if (signedIn) { text(value.get("server"), 2048); text(value.get("user_id"), 128); }
        return signedIn;
    }
    private static String text(JsonElement value, int max) throws AgentException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid();
        String text = value.getAsString();
        if (text.isEmpty() || text.getBytes(StandardCharsets.UTF_8).length > max) throw invalid();
        return text;
    }
    private static AgentException invalid() { return new AgentException(AgentException.Code.INVALID_REPLY); }
}
