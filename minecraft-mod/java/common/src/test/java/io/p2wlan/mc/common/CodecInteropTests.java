package io.p2wlan.mc.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Exchanges actual framed bytes with the Rust codec, not a Java-only mock. */
public final class CodecInteropTests {
    private static int exchange(Process peer) throws Exception {
        var input = new DataInputStream(peer.getInputStream());
        var output = new DataOutputStream(peer.getOutputStream());
        int checks = 0;
        for (int index = 0; index < 4; index++) {
            JsonObject request = new JsonObject();
            request.addProperty("id", index + 1);
            request.addProperty("world", index == 3 ? "汉".repeat(20000) : "你好\n🌲");
            request.addProperty("integer", new BigDecimal("18446744073709551615"));
            request.addProperty("decimal", new BigDecimal("1.234567890123456789"));
            request.addProperty("scale", new BigDecimal(index % 2 == 0 ? "1e128" : "1e-128"));
            JsonArray array = new JsonArray();
            array.add(true); array.add("\\ud800"); array.add((String) null);
            request.add("array", array);
            FrameCodec.write(output, request);
            JsonObject response = FrameCodec.read(input);
            if (response == null || !request.equals(response)
                    || !response.get("integer").getAsBigDecimal().equals(request.get("integer").getAsBigDecimal())
                    || !response.get("decimal").getAsBigDecimal().equals(request.get("decimal").getAsBigDecimal())) {
                throw new AssertionError("native codec changed payload");
            }
            checks++;
        }
        output.close();
        if (FrameCodec.read(input) != null || !peer.waitFor(2, TimeUnit.SECONDS) || peer.exitValue() != 0) {
            throw new AssertionError("native codec did not terminate on EOF");
        }
        return checks + 1;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("NATIVE_PROBE_REQUIRED");
        Process peer = new ProcessBuilder(args[0]).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "p2wlan-codec-interop");
            thread.setDaemon(true);
            return thread;
        });
        try {
            int checks = executor.submit(() -> exchange(peer)).get(15, TimeUnit.SECONDS);
            System.out.println("PASS Java/Rust codec: " + checks + " actual process exchanges and EOF checks");
        } finally {
            peer.destroyForcibly();
            executor.shutdownNow();
            peer.waitFor(2, TimeUnit.SECONDS);
        }
    }
}
