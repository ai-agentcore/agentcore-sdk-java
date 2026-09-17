package io.agentcore.auth;

import io.agentcore.Headers;
import io.agentcore.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BoundCredential {
    private final String value;
    private final Map<String, Object> metadata;
    public BoundCredential(String value, Map<String, Object> metadata) { this.value = value; this.metadata = Map.copyOf(metadata); }
    public String value() { return value; }
    public Map<String, Object> metadata() { return metadata; }
    public Map<String, String> asHeaders() {
        if (!"mcpHeader".equals(metadata.get("credentialType"))) throw new IllegalStateException("Credential is not an MCP Header credential");
        Object values = Json.read(value).get("headers");
        if (!(values instanceof List<?> entries) || entries.isEmpty()) throw new IllegalArgumentException("Invalid MCP Header credential payload");
        var result = new LinkedHashMap<String, String>();
        for (var entry : entries) {
            var item = Json.object(entry);
            String name = Json.text(item.get("name"), "header name");
            if (result.putIfAbsent(name, Json.text(item.get("value"), "header value")) != null)
                throw new IllegalArgumentException("Duplicate credential header");
        }
        return Headers.merge(result);
    }
    @Override public String toString() { return "BoundCredential(<redacted>)"; }
}
