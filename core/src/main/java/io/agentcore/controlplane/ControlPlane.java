package io.agentcore.controlplane;

import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teaopenapi.models.OpenApiRequest;
import com.aliyun.teaopenapi.models.Params;
import com.aliyun.teautil.models.RuntimeOptions;
import io.agentcore.AgentCoreException;
import io.agentcore.Json;
import io.agentcore.auth.CredentialProvider;
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
            var config = new Config().setAccessKeyId(credential.accessKeyId()).setAccessKeySecret(credential.accessKeySecret())
                .setSecurityToken(credential.securityToken()).setRegionId(regionId);
            URI selected = endpoint != null ? endpoint : discoveredEndpoint;
            if (selected != null) config.setEndpoint(selected.getAuthority()).setProtocol(selected.getScheme());
            var client = new com.aliyun.agentcore20260804.Client(config);
            var params = new Params().setAction(action).setVersion("2026-08-04").setProtocol("HTTPS")
                .setPathname(path).setMethod(method).setAuthType("AK").setStyle("ROA")
                .setReqBodyType(form ? "formData" : "json").setBodyType("json");
            var request = new OpenApiRequest().setQuery(query).setHeaders(Map.of());
            if (body != null) request.setBody(form ? Map.of("body", Json.write(body)) : body);
            var options = new RuntimeOptions().setAutoretry(false).setConnectTimeout(timeoutMillis).setReadTimeout(timeoutMillis);
            var log = org.slf4j.LoggerFactory.getLogger(ControlPlane.class);
            log.debug("agentcore.control_plane.request operation={} host={} path={}", action, client._endpoint, path);
            try {
                try { return responseBody(client.callApi(params, request, options)); }
                catch (Exception first) {
                    URI fallback = EndpointFallback.controlPlane(URI.create("https://" + client._endpoint), regionId, endpoint != null, action, first);
                    if (fallback == null) throw first;
                    log.warn("agentcore.control_plane.endpoint.fallback operation={} host={}", action, fallback.getHost());
                    client._endpoint = fallback.getAuthority();
                    var result = responseBody(client.callApi(params, request, options));
                    discoveredEndpoint = fallback;
                    return result;
                }
            }
            catch (com.aliyun.tea.TeaException error) {
                Map<String, Object> data = error.getData();
                Integer status = data != null && data.get("statusCode") instanceof Number n ? n.intValue() : null;
                Object requestId = data == null ? null : data.getOrDefault("RequestId", data.get("requestId"));
                String id = requestId instanceof String s ? s : null;
                Object message = data == null ? null : data.getOrDefault("Message", data.get("message"));
                var failure = new AgentCoreException(action, status, id, error.getCode(),
                    message instanceof String s ? s : error.getMessage(), error);
                log.warn("agentcore.control_plane.failed operation={} host={} status={} code={} requestId={} message={}",
                    action, client._endpoint, status, error.getCode(), id, failure.serviceMessage());
                throw failure;
            } catch (Exception error) {
                log.warn("agentcore.control_plane.failed operation={} host={} error_type={}", action, client._endpoint, error.getClass().getSimpleName());
                throw new AgentCoreException(action, null, null, null, error);
            }
        }).subscribeOn(Schedulers.boundedElastic()));
    }
    private static Map<String, Object> responseBody(Map<String, ?> response) {
        var body = new LinkedHashMap<>(Json.object(response.get("body")));
        if (body.get("requestId") == null && body.get("RequestId") == null) {
            String id = headerRequestId(response.get("headers"));
            if (id != null) body.put("requestId", id);
        }
        return body;
    }
    private static String headerRequestId(Object headers) {
        if (headers instanceof Map<?, ?> map) for (var entry : map.entrySet()) {
            if ("x-acs-request-id".equalsIgnoreCase(String.valueOf(entry.getKey())) && entry.getValue() instanceof String id) return id;
        }
        return null;
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
