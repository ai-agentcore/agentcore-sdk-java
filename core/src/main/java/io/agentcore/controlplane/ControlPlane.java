package io.agentcore.controlplane;

import io.agentcore.Json;
import io.agentcore.auth.CredentialProvider;
import io.agentcore.internal.PopRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public final class ControlPlane {
    private final String workspaceId;
    private final String regionId;
    private final URI endpoint;
    private final CredentialProvider credentials;
    private volatile URI discoveredEndpoint;

    public ControlPlane(String workspaceId, String regionId, URI endpoint, CredentialProvider credentials) {
        this.workspaceId = Json.text(workspaceId, "workspaceId"); this.regionId = Json.text(regionId, "regionId");
        this.endpoint = endpoint; this.credentials = credentials;
    }
    public String workspacePath() { return "/workspaces/" + encode(workspaceId); }
    public String workspaceId() { return workspaceId; }
    public String regionId() { return regionId; }
    public Mono<Map<String, Object>> request(String action, String method, String path,
            Map<String, String> query, Map<String, Object> body, boolean form, int timeoutMillis) {
        return credentials.get("highcode_sdk").flatMap(credential -> Mono.fromCallable(() -> {
            URI selected = endpoint != null ? endpoint : discoveredEndpoint;
            return PopRequest.call(credential, regionId, selected, action, method, path, query, body,
                form, timeoutMillis, endpoint == null, value -> discoveredEndpoint = value, () -> {});
        }).subscribeOn(Schedulers.boundedElastic()));
    }
    public Mono<Map<String, Object>> request(String action, String method, String path, Map<String, String> query) {
        return request(action, method, workspacePath() + path, query, null, false, 30_000);
    }
    public Mono<List<Map<String, Object>>> list(String action, String path, Map<String, String> query) {
        var initial = new LinkedHashMap<>(query); initial.put("maxResults", "100");
        return request(action, "GET", path, initial).expand(page -> {
            if (!(page.get("nextToken") instanceof String next) || next.isEmpty()) return Mono.empty();
            var nextQuery = new LinkedHashMap<>(initial); nextQuery.put("nextToken", next);
            return request(action, "GET", path, nextQuery);
        }).collectList().map(pages -> {
            List<Map<String, Object>> items = new ArrayList<>();
            for (var page : pages) for (var item : (List<?>) page.getOrDefault("items", List.of())) items.add(Json.object(item));
            return List.copyOf(items);
        });
    }
    public static Map<String, Object> exact(List<Map<String, Object>> items, String field, String value) {
        var matches = items.stream().filter(item -> value.equals(item.get(field))).toList();
        if (matches.isEmpty()) throw new ResourceNotFoundException(field, value);
        if (matches.size() != 1) throw new IllegalArgumentException("Ambiguous resource name: " + value);
        return matches.get(0);
    }
    public static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
}
