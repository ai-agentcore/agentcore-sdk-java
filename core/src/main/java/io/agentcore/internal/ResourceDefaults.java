package io.agentcore.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/** Internal resource selection defaults; independent of runtime authentication. */
public final class ResourceDefaults {
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Map<String, String> values = new HashMap<>();
    public record Model(String name, String model) {}
    public record VersionedResource(String name, String version) {}
    public ResourceDefaults() { this(System.getenv()); }
    public ResourceDefaults(Map<String, String> environment) {
        for (String key : List.of("AGENTCORE_MODEL_CONNECTION_NAME", "AGENTCORE_MODEL_NAME",
                "AGENTCORE_AGENT_SPEC_NAME", "AGENTCORE_AGENT_SPEC_VERSION", "AGENTCORE_MCP_NAMES", "AGENTCORE_SKILLS"))
            values.put(key, environment.get(key));
    }
    public Model model(String name, String model) {
        return name != null ? new Model(name, model)
            : new Model(required("AGENTCORE_MODEL_CONNECTION_NAME"), model != null ? model : optional("AGENTCORE_MODEL_NAME"));
    }
    public VersionedResource agentSpec(String name, String version) {
        return name != null ? new VersionedResource(name, version)
            : new VersionedResource(required("AGENTCORE_AGENT_SPEC_NAME"), version != null ? version : optional("AGENTCORE_AGENT_SPEC_VERSION"));
    }
    public List<String> mcpNames() {
        String key = "AGENTCORE_MCP_NAMES";
        return list(key).stream().map(value -> text(value, key)).toList();
    }
    public List<VersionedResource> skills() {
        String key = "AGENTCORE_SKILLS";
        return list(key).stream().map(value -> {
            if (!(value instanceof Map<?, ?> item))
                throw new IllegalArgumentException(key + " must contain objects with a name and optional version");
            return new VersionedResource(text(item.get("name"), key), item.containsKey("version") ? text(item.get("version"), key) : null);
        }).toList();
    }
    private String optional(String key) {
        String value = values.get(key);
        return value == null || value.isBlank() ? null : value.strip();
    }
    private String required(String key) {
        String value = optional(key);
        if (value == null) throw new IllegalArgumentException("Set " + key + " or pass an explicit resource name");
        return value;
    }
    private List<?> list(String key) {
        String raw = optional(key);
        if (raw == null) return List.of();
        Object value;
        try { value = JSON.readValue(raw, Object.class); }
        catch (java.io.IOException error) { throw new IllegalArgumentException(key + " must be a JSON array", error); }
        if (!(value instanceof List<?> result)) throw new IllegalArgumentException(key + " must be a JSON array");
        return result;
    }
    private static String text(Object value, String key) {
        if (!(value instanceof String text) || text.isBlank())
            throw new IllegalArgumentException(key + " resource names and versions must be non-empty strings");
        return text.strip();
    }
}
