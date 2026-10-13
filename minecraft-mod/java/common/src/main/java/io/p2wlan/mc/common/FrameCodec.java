package io.p2wlan.mc.common;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** One length-prefixed UTF-8 JSON object per IPC message. No MC dependencies. */
public final class FrameCodec {
    public static final int VERSION = 1;
    public static final int MAX_FRAME_BYTES = 64 * 1024;
    private static final int MAX_DEPTH = 16;
    private static final int MAX_ITEMS = 128;
    private static final Gson GSON = new Gson();

    private FrameCodec() {}

    /** Null is a clean EOF before any header byte, never a partial frame. */
    public static JsonObject read(DataInputStream input) throws IOException {
        int first = input.read();
        if (first == -1) return null;
        byte[] tail = new byte[3];
        try { input.readFully(tail); }
        catch (EOFException error) { throw new ProtocolException(ProtocolException.Code.TRUNCATED_FRAME); }
        int size = (first << 24) | ((tail[0] & 255) << 16) | ((tail[1] & 255) << 8) | (tail[2] & 255);
        if (size <= 0 || size > MAX_FRAME_BYTES) throw new ProtocolException(ProtocolException.Code.FRAME_SIZE);
        byte[] frame = new byte[size];
        try { input.readFully(frame); }
        catch (EOFException error) { throw new ProtocolException(ProtocolException.Code.TRUNCATED_FRAME); }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(frame)).toString();
        } catch (CharacterCodingException error) {
            throw new ProtocolException(ProtocolException.Code.INVALID_UTF8);
        }
        return parse(text);
    }

    public static void write(DataOutputStream output, JsonObject object) throws IOException {
        // Detach within the same bounds before Gson traverses the value.
        JsonObject detached = copy(object);
        ByteBuffer encoded;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(GSON.toJson(detached)));
        } catch (CharacterCodingException error) {
            throw new ProtocolException(ProtocolException.Code.INVALID_UTF8);
        }
        int size = encoded.remaining();
        if (size <= 0 || size > MAX_FRAME_BYTES) throw new ProtocolException(ProtocolException.Code.FRAME_SIZE);
        byte[] frame = new byte[size];
        encoded.get(frame);
        output.writeInt(size);
        output.write(frame);
        output.flush();
    }

    static JsonObject parse(String text) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            JsonElement root = readValue(reader, 0, new int[] {0});
            if (!root.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) {
                throw new ProtocolException(ProtocolException.Code.INVALID_JSON);
            }
            return root.getAsJsonObject();
        } catch (ProtocolException error) {
            throw error;
        } catch (IOException | IllegalStateException | NumberFormatException error) {
            // Parse exceptions can contain credential text; preserve only code.
            throw new ProtocolException(ProtocolException.Code.INVALID_JSON);
        }
    }

    private static JsonElement readValue(JsonReader reader, int depth, int[] nodes) throws IOException {
        if (depth > MAX_DEPTH) throw new ProtocolException(ProtocolException.Code.DEPTH);
        if (++nodes[0] > 4096) throw new ProtocolException(ProtocolException.Code.CAPACITY);
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    if (object.size() >= MAX_ITEMS) throw new ProtocolException(ProtocolException.Code.CAPACITY);
                    String key = reader.nextName();
                    checkString(key);
                    if (object.has(key)) throw new ProtocolException(ProtocolException.Code.INVALID_JSON);
                    object.add(key, readValue(reader, depth + 1, nodes));
                }
                reader.endObject();
                return object;
            case BEGIN_ARRAY:
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) {
                    if (array.size() >= MAX_ITEMS) throw new ProtocolException(ProtocolException.Code.CAPACITY);
                    array.add(readValue(reader, depth + 1, nodes));
                }
                reader.endArray();
                return array;
            case STRING:
                String value = reader.nextString();
                checkString(value);
                return new JsonPrimitive(value);
            case NUMBER:
                String number = reader.nextString();
                if (number.length() > 64) throw new ProtocolException(ProtocolException.Code.CAPACITY);
                BigDecimal decimal = new BigDecimal(number);
                if (decimal.scale() < -128 || decimal.scale() > 128) {
                    throw new ProtocolException(ProtocolException.Code.CAPACITY);
                }
                return new JsonPrimitive(decimal);
            case BOOLEAN: return new JsonPrimitive(reader.nextBoolean());
            case NULL: reader.nextNull(); return JsonNull.INSTANCE;
            default: throw new ProtocolException(ProtocolException.Code.INVALID_JSON);
        }
    }

    private static void checkString(String value) throws ProtocolException {
        if (value.length() > MAX_FRAME_BYTES / 2) throw new ProtocolException(ProtocolException.Code.CAPACITY);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new ProtocolException(ProtocolException.Code.INVALID_UTF8);
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new ProtocolException(ProtocolException.Code.INVALID_UTF8);
            }
        }
    }

    static JsonObject copy(JsonObject value) throws ProtocolException {
        if (value == null) throw new ProtocolException(ProtocolException.Code.INVALID_JSON);
        return copyValue(value, 0, new int[] {0, 0}).getAsJsonObject();
    }

    private static JsonElement copyValue(JsonElement element, int depth, int[] budget) throws ProtocolException {
        if (depth > MAX_DEPTH) throw new ProtocolException(ProtocolException.Code.DEPTH);
        if (++budget[0] > 4096) throw new ProtocolException(ProtocolException.Code.CAPACITY);
        if (element.isJsonObject()) {
            if (element.getAsJsonObject().size() > MAX_ITEMS) throw new ProtocolException(ProtocolException.Code.CAPACITY);
            JsonObject copy = new JsonObject();
            for (var entry : element.getAsJsonObject().entrySet()) {
                checkString(entry.getKey());
                budget[1] += entry.getKey().length();
                if (budget[1] > MAX_FRAME_BYTES) throw new ProtocolException(ProtocolException.Code.CAPACITY);
                copy.add(entry.getKey(), copyValue(entry.getValue(), depth + 1, budget));
            }
            return copy;
        } else if (element.isJsonArray()) {
            if (element.getAsJsonArray().size() > MAX_ITEMS) throw new ProtocolException(ProtocolException.Code.CAPACITY);
            JsonArray copy = new JsonArray();
            for (var child : element.getAsJsonArray()) copy.add(copyValue(child, depth + 1, budget));
            return copy;
        } else if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            String value = primitive.getAsString();
            checkString(value);
            budget[1] += value.length();
            if (budget[1] > MAX_FRAME_BYTES) throw new ProtocolException(ProtocolException.Code.CAPACITY);
            if (primitive.isNumber()) {
                try {
                    BigDecimal number = new BigDecimal(value);
                    if (value.length() > 64 || number.scale() < -128 || number.scale() > 128) {
                        throw new ProtocolException(ProtocolException.Code.CAPACITY);
                    }
                } catch (NumberFormatException error) {
                    throw new ProtocolException(ProtocolException.Code.INVALID_JSON);
                }
                return new JsonPrimitive(new BigDecimal(value));
            }
            return primitive.isBoolean() ? new JsonPrimitive(primitive.getAsBoolean()) : new JsonPrimitive(value);
        }
        if (element.isJsonNull()) return JsonNull.INSTANCE;
        throw new ProtocolException(ProtocolException.Code.INVALID_JSON);
    }
}
