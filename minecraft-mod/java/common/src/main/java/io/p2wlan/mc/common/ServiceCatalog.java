package io.p2wlan.mc.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Fenced, immutable one-peer discovery reply; no scan or independently evolving
 * availability cache. The exact opaque remote world is retained for joining. */
public record ServiceCatalog(AgentReply evidence, RuntimeView runtime, String peer, List<GameService> services) {
    public ServiceCatalog { services = List.copyOf(services); }
    public static ServiceCatalog parse(AgentReply reply, RuntimeView runtime, String peer) throws AgentException {
        if (reply == null || runtime == null || !reply.owner().equals(runtime.owner()) || !GameService.validPeer(peer)) throw invalid();
        JsonObject data = reply.data();
        if (!JsonTrees.fields(data, Set.of("runtime", "peer", "services"))
                || !runtime.runtime().equals(RuntimeView.text(data, "runtime")) || !peer.equals(RuntimeView.text(data, "peer"))
                || !data.get("services").isJsonArray() || data.getAsJsonArray("services").size() > 8) throw invalid();
        List<GameService> services = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        int previous = 0;
        for (JsonElement element : data.getAsJsonArray("services")) {
            if (!element.isJsonObject()) throw invalid();
            JsonObject value = element.getAsJsonObject();
            String identity = RuntimeView.text(value, "service");
            JsonElement port = value.get("port");
            if (!JsonTrees.fields(value, Set.of("port", "service")) || identity == null || !identity.matches("[a-f0-9]{64}")
                    || !identities.add(identity) || port == null || !port.isJsonPrimitive() || !port.getAsJsonPrimitive().isNumber()
                    || !port.getAsString().matches("[1-9][0-9]{0,4}")) throw invalid();
            int number = port.getAsInt();
            if (number <= previous || number > 65535) throw invalid();
            services.add(new GameService(runtime, peer, number, identity)); previous = number;
        }
        return new ServiceCatalog(reply, runtime, peer, services);
    }
    private static AgentException invalid() { return new AgentException(AgentException.Code.INVALID_REPLY); }
}
