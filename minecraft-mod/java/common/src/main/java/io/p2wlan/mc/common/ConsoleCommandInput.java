package io.p2wlan.mc.common;

import java.util.ArrayList;
import java.util.List;

/** Bounded console grammar independent of Brigadier's input-bearing parse errors. */
public final class ConsoleCommandInput {
    private ConsoleCommandInput() {}
    /** Bounded shell-like fields; input is never copied into diagnostics or errors. */
    public static List<String> parse(String input, int expected) throws AgentException {
        if (input == null || expected < 1 || expected > 2 || input.length() > 8192
                || input.chars().anyMatch(value -> value < 32 || value == 127)) throw new AgentException(AgentException.Code.INVALID_REQUEST);
        for (int index = 0; index < input.length(); index++) {
            char value = input.charAt(index);
            if (Character.isHighSurrogate(value)) {
                if (++index == input.length() || !Character.isLowSurrogate(input.charAt(index)))
                    throw new AgentException(AgentException.Code.INVALID_REQUEST);
            } else if (Character.isLowSurrogate(value)) throw new AgentException(AgentException.Code.INVALID_REQUEST);
        }
        List<String> result = new ArrayList<>();
        int cursor = 0;
        while (cursor < input.length()) {
            while (cursor < input.length() && Character.isWhitespace(input.charAt(cursor))) cursor++;
            if (cursor == input.length()) break;
            if (result.size() == expected) throw new AgentException(AgentException.Code.INVALID_REQUEST);
            StringBuilder value = new StringBuilder();
            boolean quoted = input.charAt(cursor) == '"';
            if (quoted) cursor++;
            boolean closed = !quoted;
            while (cursor < input.length()) {
                char next = input.charAt(cursor++);
                if (quoted && next == '"') { closed = true; break; }
                if (!quoted && Character.isWhitespace(next)) break;
                if (quoted && next == '\\') {
                    if (cursor == input.length()) throw new AgentException(AgentException.Code.INVALID_REQUEST);
                    next = input.charAt(cursor++);
                    if (next != '"' && next != '\\') throw new AgentException(AgentException.Code.INVALID_REQUEST);
                }
                if (next < 32 || next == 127) throw new AgentException(AgentException.Code.INVALID_REQUEST);
                value.append(next);
            }
            if (!closed || value.length() == 0 || quoted && cursor < input.length() && !Character.isWhitespace(input.charAt(cursor)))
                throw new AgentException(AgentException.Code.INVALID_REQUEST);
            result.add(value.toString());
        }
        if (result.size() != expected) throw new AgentException(AgentException.Code.INVALID_REQUEST);
        return result;
    }
}
