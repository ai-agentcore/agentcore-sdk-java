package io.agentcore;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class Headers {
    private static final Set<String> RESERVED = Set.of("authorization", "host", "content-length",
        "connection", "transfer-encoding", "mcp-session-id", "mcp-protocol-version", "upgrade", "last-event-id", "content-type", "accept");
    private Headers() {}
    @SafeVarargs
    public static Map<String, String> merge(Map<String, String> platform, Map<String, String>... additions) {
        var result = new LinkedHashMap<String, String>();
        platform.forEach((name, value) -> add(result, name, value));
        for (var headers : additions) {
            var normalized = new LinkedHashMap<String, String>();
            headers.forEach((name, value) -> add(normalized, name, value));
            normalized.forEach((name, value) -> {
                if (result.containsKey(name) || RESERVED.contains(name))
                    throw new IllegalArgumentException("Conflicting or reserved header: " + name);
                result.put(name, value);
            });
        }
        return Map.copyOf(result);
    }
    private static void add(Map<String, String> result, String name, String value) {
        String key = name.toLowerCase(Locale.ROOT);
        if (!key.matches("[!#$%&'*+.^_`|~0-9a-z-]+") || value == null || value.matches("(?s).*[\\x00-\\x1f\\x7f].*"))
            throw new IllegalArgumentException("Invalid HTTP header");
        if (result.containsKey(key))
            throw new IllegalArgumentException("Conflicting or reserved header: " + name);
        result.put(key, value);
    }
}
