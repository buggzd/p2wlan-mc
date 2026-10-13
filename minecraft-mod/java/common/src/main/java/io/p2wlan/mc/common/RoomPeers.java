package io.p2wlan.mc.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Complete bounded native membership projection. Offline members can appear;
 * no discovery, game-readiness or send permission is inferred from membership. */
public record RoomPeers(AgentReply evidence, RuntimeView runtime, String generation, List<String> peers) {
    public RoomPeers { peers = List.copyOf(peers); }
    public static RoomPeers parse(AgentReply reply, RuntimeView runtime) throws AgentException {
        if (reply == null || runtime == null || !reply.owner().equals(runtime.owner())) throw invalid();
        JsonObject data = reply.data();
        if (!JsonTrees.fields(data, Set.of("runtime", "view")) || !runtime.runtime().equals(RuntimeView.text(data, "runtime"))
                || !data.get("view").isJsonObject()) throw invalid();
        JsonObject view = data.getAsJsonObject("view");
        String generation = RuntimeView.text(view, "network_generation");
        if (!JsonTrees.fields(view, Set.of("network_generation", "peers")) || generation == null
                || !generation.matches("0|[1-9][0-9]{0,19}") || new BigInteger(generation).bitLength() > 64
                || !view.get("peers").isJsonArray() || view.getAsJsonArray("peers").size() > 128) throw invalid();
        List<String> peers = new ArrayList<>();
        byte[] previous = null;
        for (JsonElement value : view.getAsJsonArray("peers")) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid();
            String peer = value.getAsString();
            if (!GameService.validPeer(peer)) throw invalid();
            byte[] bytes = peer.getBytes(StandardCharsets.UTF_8);
            if (previous != null && Arrays.compareUnsigned(previous, bytes) >= 0) throw invalid();
            peers.add(peer); previous = bytes;
        }
        return new RoomPeers(reply, runtime, generation, peers);
    }
    private static AgentException invalid() { return new AgentException(AgentException.Code.INVALID_REPLY); }
}
