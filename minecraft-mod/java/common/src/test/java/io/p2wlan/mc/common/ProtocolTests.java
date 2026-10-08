package io.p2wlan.mc.common;

import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ProtocolTests {
    private static int checks;
    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("protocol check " + checks);
    }
    private static byte[] frame(String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        var stream = new ByteArrayOutputStream();
        var output = new DataOutputStream(stream);
        output.writeInt(bytes.length); output.write(bytes);
        return stream.toByteArray();
    }
    private static void reject(byte[] bytes, ProtocolException.Code code) throws IOException {
        try {
            FrameCodec.read(new DataInputStream(new ByteArrayInputStream(bytes)));
            throw new AssertionError("accepted invalid frame");
        } catch (ProtocolException error) { check(error.code() == code); }
    }

    public static void main(String[] args) throws IOException {
        JsonObject object = new JsonObject();
        object.addProperty("id", 7); object.addProperty("world", "你好\n🌲");
        var bytes = new ByteArrayOutputStream();
        FrameCodec.write(new DataOutputStream(bytes), object);
        check(FrameCodec.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))).equals(object));
        check(FrameCodec.read(new DataInputStream(new ByteArrayInputStream(new byte[0]))) == null);
        reject(new byte[] {0, 0}, ProtocolException.Code.TRUNCATED_FRAME);
        reject(new byte[] {-1, -1, -1, -1}, ProtocolException.Code.FRAME_SIZE);
        reject(new byte[] {0, 1, 0, 1}, ProtocolException.Code.FRAME_SIZE);
        reject(new byte[] {0, 0, 0, 3, '{'}, ProtocolException.Code.TRUNCATED_FRAME);
        reject(new byte[] {0, 0, 0, 1, (byte) 0xff}, ProtocolException.Code.INVALID_UTF8);
        reject(frame("{\"id\":1,\"id\":2}"), ProtocolException.Code.INVALID_JSON);
        reject(frame("{} {}"), ProtocolException.Code.INVALID_JSON);
        reject(frame("{unquoted:1}"), ProtocolException.Code.INVALID_JSON);
        reject(frame("{/*comment*/}"), ProtocolException.Code.INVALID_JSON);
        reject(frame("{\"s\":\"\\ud800\"}"), ProtocolException.Code.INVALID_UTF8);
        reject(frame("{\"x\":" + "[".repeat(20) + "0" + "]".repeat(20) + "}"), ProtocolException.Code.DEPTH);
        reject(frame("{\"x\":1e9999}"), ProtocolException.Code.CAPACITY);
        check(FrameCodec.read(new DataInputStream(new ByteArrayInputStream(frame("{\"null\":null}")))).has("null"));

        String oldOwner = "a".repeat(32), newOwner = "b".repeat(32);
        SessionFence fence = new SessionFence();
        fence.begin(oldOwner);
        check(fence.accept(new StatusSnapshot(oldOwner, 1, object)));
        check(!fence.accept(new StatusSnapshot(oldOwner, 1, object)));
        check(!fence.accept(new StatusSnapshot(newOwner, 999, object)));
        object.addProperty("world", "mutated");
        check(!fence.snapshot().orElseThrow().data().get("world").getAsString().equals("mutated"));
        fence.snapshot().orElseThrow().data().addProperty("world", "another mutation");
        check(!fence.snapshot().orElseThrow().data().get("world").getAsString().equals("another mutation"));
        fence.begin(newOwner);
        check(!fence.accept(new StatusSnapshot(oldOwner, Long.MAX_VALUE, object)));
        check(fence.accept(new StatusSnapshot(newOwner, 0, object)));
        fence.close(oldOwner);
        check(fence.snapshot().isPresent());
        fence.close(newOwner);
        check(fence.snapshot().isEmpty());
        System.out.println("PASS protocol: " + checks + " explicit checks");
    }
}
