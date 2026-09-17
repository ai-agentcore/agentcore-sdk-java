package io.agentcore.collaboration;

import io.agentcore.Json;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/** Request-local routing metadata. This is not an authentication mechanism. */
public record CollaborationContext(String sessionId, String teamId, String roomId, String eventId, String roomKind) {
    /** Key used in the framework's native per-invocation context. Never a tool argument. */
    public static final String KEY = "agentcore.collaboration.context";
    public static CollaborationContext fromHeaders(Map<String, String> headers) {
        String encoded = headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("x-agentcore-collaboration-context"))
            .map(Map.Entry::getValue).findFirst().orElse(null);
        if (encoded == null) return null;
        try {
            var value = Json.read(new String(Base64.getDecoder().decode(encoded.replace('-', '+').replace('_', '/')), StandardCharsets.UTF_8));
            if (!(value.get("version") instanceof Number n) || n.intValue() != 1) throw new IllegalArgumentException();
            String kind = Json.text(value.get("roomKind"), "roomKind");
            if (!java.util.List.of("dm", "group", "task").contains(kind)) throw new IllegalArgumentException();
            String team = value.get("teamId") instanceof String text ? text : null;
            if (kind.equals("task")) Json.text(team, "teamId");
            String session = headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("x-agentcore-session-id")).map(Map.Entry::getValue).findFirst().orElse(null);
            return new CollaborationContext(session, team, Json.text(value.get("roomId"), "roomId"), Json.text(value.get("eventId"), "eventId"), kind);
        } catch (IllegalArgumentException error) { throw new IllegalArgumentException("Invalid collaboration context header"); }
    }
    @Override public String toString() { return "CollaborationContext(<redacted>)"; }
}
