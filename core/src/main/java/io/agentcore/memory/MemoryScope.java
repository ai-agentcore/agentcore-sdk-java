package io.agentcore.memory;

import java.util.LinkedHashMap;
import java.util.Map;

/** Application partitions, not credentials or access-control identities. */
public record MemoryScope(String userId, String teamId, String agentId, String sessionId) {
    public MemoryScope(String userId, String agentId, String sessionId) {
        this(userId, null, agentId, sessionId);
    }
    public MemoryScope { validate(userId, "userId"); validate(teamId, "teamId"); validate(agentId, "agentId"); validate(sessionId, "sessionId"); }
    public Map<String, Object> toMap() {
        var values = new LinkedHashMap<String, Object>();
        put(values, "userId", userId); put(values, "teamId", teamId); put(values, "agentId", agentId); put(values, "sessionId", sessionId);
        return values;
    }
    private static void put(Map<String, Object> values, String field, String value) {
        if (value == null) return;
        values.put(field, value);
    }
    private static void validate(String value, String field) {
        if (value != null && (value.isBlank() || value.equals("*") || value.equals("__default__")))
            throw new IllegalArgumentException("Invalid memory scope: " + field);
    }
}
