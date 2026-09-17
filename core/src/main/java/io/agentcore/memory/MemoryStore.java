package io.agentcore.memory;

import io.agentcore.AgentCoreException;
import io.agentcore.Json;
import io.agentcore.controlplane.ControlPlane;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

public final class MemoryStore {
    private final String name;
    private final Supplier<Mono<ControlPlane>> controlPlane;
    public MemoryStore(String name, Supplier<Mono<ControlPlane>> controlPlane) {
        this.name = Json.text(name, "memoryStoreName"); this.controlPlane = controlPlane;
    }
    String name() { return name; }
    public Mono<Map<String, Object>> addMemories(String text, MemoryScope scope) {
        return addMemories(text, scope, null);
    }
    public Mono<Map<String, Object>> addMemories(String text, MemoryScope scope, Map<String, String> metadata) {
        var body = new LinkedHashMap<String, Object>(); body.put("text", Json.text(text, "text"));
        if (metadata != null) body.put("metadata", Map.copyOf(metadata));
        return request("AddMemories", "POST", "/memories", Map.of(), withScope(body, scope));
    }
    public Mono<Map<String, Object>> addMessages(List<Map<String, String>> messages, MemoryScope scope) {
        return addMessages(messages, scope, null);
    }
    public Mono<Map<String, Object>> addMessages(List<Map<String, String>> messages, MemoryScope scope, Map<String, String> metadata) {
        if (messages.isEmpty()) return Mono.error(new IllegalArgumentException("messages must not be empty"));
        var body = new LinkedHashMap<String, Object>(); body.put("messages", messages);
        if (metadata != null) body.put("metadata", Map.copyOf(metadata));
        return request("AddMemories", "POST", "/memories", Map.of(), withScope(body, scope));
    }
    public record SearchOptions(Integer topK, Map<String, String> metadata, Boolean enableRerank,
                                Double minSimilarity, Double minScore) {
        public SearchOptions { if (metadata != null) metadata = Map.copyOf(metadata); }
    }
    public Mono<Map<String, Object>> searchMemories(String query, MemoryScope scope, int topK) {
        return searchMemories(query, scope, new SearchOptions(topK, null, null, null, null));
    }
    public Mono<Map<String, Object>> searchMemories(String query, MemoryScope scope, SearchOptions options) {
        if (options.topK() != null && (options.topK() < 1 || options.topK() > 50))
            return Mono.error(new IllegalArgumentException("topK must be between 1 and 50"));
        var body = new LinkedHashMap<String, Object>(); body.put("query", Json.text(query, "query"));
        if (options.topK() != null) body.put("topK", options.topK());
        if (options.metadata() != null) body.put("metadata", options.metadata());
        if (options.enableRerank() != null) body.put("enableRerank", options.enableRerank());
        if (options.minSimilarity() != null) body.put("minSimilarity", options.minSimilarity());
        if (options.minScore() != null) body.put("minScore", options.minScore());
        return request("SearchMemories", "POST", "/memories/search", Map.of(), withScope(body, scope));
    }
    public Mono<Map<String, Object>> listMemories(Map<String, String> query) {
        return request("ListMemories", "GET", "/memories", query, null);
    }
    public Mono<Map<String, Object>> getMemory(String memoryId) {
        return request("GetMemory", "GET", "/memories/" + ControlPlane.encode(memoryId), Map.of(), null);
    }
    public Mono<Map<String, Object>> updateMemory(String memoryId, Map<String, Object> fields) {
        return request("UpdateMemory", "PUT", "/memories/" + ControlPlane.encode(memoryId), Map.of(), fields);
    }
    public Mono<Void> deleteMemory(String memoryId) {
        return request("DeleteMemory", "DELETE", "/memories/" + ControlPlane.encode(memoryId), Map.of(), null).then();
    }
    public Mono<Map<String, Object>> listMemorySessions(Map<String, String> query) {
        return request("ListMemorySessions", "GET", "/sessions", query, null);
    }
    public Mono<Map<String, Object>> listMemorySessionMessages(String sessionId, MemoryScope scope) {
        return listMemorySessionMessages(sessionId, scope, null, null);
    }
    public Mono<Map<String, Object>> listMemorySessionMessages(String sessionId, MemoryScope scope, Integer maxResults, String nextToken) {
        if (scope == null || scope.userId() == null && scope.agentId() == null)
            return Mono.error(new IllegalArgumentException("userId or agentId is required"));
        var query = new LinkedHashMap<String, String>(); scope.toMap().forEach((k, v) -> query.put(k, (String) v));
        query.put("sessionId", Json.text(sessionId, "sessionId"));
        if (maxResults != null) {
            if (maxResults < 1 || maxResults > 100) return Mono.error(new IllegalArgumentException("maxResults must be between 1 and 100"));
            query.put("maxResults", maxResults.toString());
        }
        if (nextToken != null) query.put("nextToken", nextToken);
        return request("ListMemorySessionMessages", "GET", "/messages", query, null);
    }
    public Mono<String> recall(String query, MemoryScope scope, int topK) {
        return searchMemories(query, scope, topK).map(response -> {
            var data = Json.object(response.get("data"));
            var hits = (List<?>) data.getOrDefault("memories", List.of());
            return String.join("\n", hits.stream().map(hit -> {
                var memory = Json.object(Json.object(hit).get("memory"));
                return Json.text(Json.object(memory.get("content")).get("text"), "memory text");
            }).toList());
        });
    }
    private Mono<Map<String, Object>> request(String action, String method, String path,
            Map<String, String> query, Map<String, Object> body) {
        boolean add = action.equals("AddMemories");
        return Mono.defer(controlPlane).flatMap(cp -> cp.request(action, method,
            cp.workspacePath() + "/memorystores/" + ControlPlane.encode(name) + path, query, body, body != null, add ? 120_000 : 30_000))
            .onErrorMap(error -> add && error instanceof AgentCoreException api && api.operation().equals("AddMemories")
                    && (api.status() == null || api.status() >= 500),
                error -> new AddMemoriesOutcomeUnknownException((AgentCoreException) error))
            .map(response -> {
                if (!Boolean.TRUE.equals(response.get("success"))) throw new AgentCoreException(action,
                    response.get("httpStatusCode") instanceof Number n ? n.intValue() : null,
                    (String) response.get("requestId"), (String) response.get("code"), (String) response.get("message"), null);
                return response;
            }).doOnError(AgentCoreException.class, error -> org.slf4j.LoggerFactory.getLogger(MemoryStore.class).warn(
                "agentcore.memory.request.failed operation={} memory_store_name={} status={} service_code={} request_id={} message={} error_type={}",
                action, name, error.status(), error.serviceCode(), error.requestId(), error.serviceMessage(), error.getClass().getSimpleName()));
    }
    private Map<String, Object> withScope(Map<String, Object> body, MemoryScope scope) {
        var result = new LinkedHashMap<>(body);
        if (scope != null) result.put("scope", scope.toMap());
        return result;
    }
    public static final class AddMemoriesOutcomeUnknownException extends AgentCoreException {
        public AddMemoriesOutcomeUnknownException(AgentCoreException cause) {
            super(cause.operation(), cause.status(), cause.requestId(), cause.serviceCode(), cause.serviceMessage(), cause);
        }
        @Override public String getMessage() { return "AddMemories outcome is unknown; do not automatically repeat the write: " + super.getMessage(); }
    }
}
