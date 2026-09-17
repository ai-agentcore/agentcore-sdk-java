package io.agentcore.server;

import java.util.List;
import java.util.Map;

public record AgentRequest(String protocol, String requestId, String sessionId, String runId,
                           List<Map<String, Object>> messages, Map<String, String> headers, Map<String, Object> body) {
    public AgentRequest {
        messages = List.copyOf(messages); headers = Map.copyOf(headers);
        body = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(body));
    }
}
