package io.agentcore.runtime;

import io.agentcore.Headers;
import io.agentcore.Json;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public record AgentConfig(String runtimeName, String workspaceId, String regionId,
                          URI modelGateway, URI mcpGateway, Map<String, String> headers) {
    public AgentConfig { headers = Headers.merge(headers); }

    public static AgentConfig parse(String yaml) {
        Map<String, Object> root;
        try {
            var options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            root = Json.object(new Yaml(new SafeConstructor(options)).load(yaml));
        } catch (Exception e) { throw new IllegalArgumentException("Invalid agent.yaml"); }
        if (!"AgentConfig".equals(root.get("kind")) || !"agentteams.io/v1alpha1".equals(root.get("apiVersion")))
            throw new IllegalArgumentException("Unsupported agent.yaml kind or apiVersion");
        var metadata = Json.object(root.get("metadata"));
        var spec = Json.object(root.get("spec"));
        var headers = new LinkedHashMap<String, String>();
        Object entries = Json.object(spec.get("credentials")).get("header");
        if (!(entries instanceof List<?> list)) throw new IllegalArgumentException("spec.credentials.header must be a list");
        for (Object entry : list) {
            var item = Json.object(entry);
            String key = Json.text(item.get("key"), "header.key");
            String value = Json.text(item.get("value"), "header.value");
            if (headers.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate gateway header");
        }
        var normalized = Headers.merge(headers);
        if (!normalized.getOrDefault("authorization", "").matches("(?i)Bearer\\s+\\S+"))
            throw new IllegalArgumentException("Gateway Bearer Authorization is required");
        return new AgentConfig(Json.text(metadata.get("runtimeName"), "runtimeName"),
            Json.text(metadata.get("workspaceId"), "workspaceId"), Json.text(metadata.get("regionId"), "regionId"),
            httpUrl(Json.object(spec.get("model")).get("gatewayUrl")),
            httpUrl(Json.object(spec.get("mcp")).get("gatewayUrl")), normalized);
    }
    public static URI httpUrl(Object value) {
        URI uri = URI.create(Json.text(value, "URL"));
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
            || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Expected an HTTP(S) URL without credentials, query or fragment");
        return uri;
    }
    @Override public String toString() { return "AgentConfig(workspaceId=" + workspaceId + ", runtimeName=" + runtimeName + ")"; }
}
