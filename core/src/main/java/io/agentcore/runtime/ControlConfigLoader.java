package io.agentcore.runtime;

import io.agentcore.AgentCoreException;
import io.agentcore.HttpTransport;
import io.agentcore.Json;
import io.agentcore.auth.ControllerCredentials;
import io.agentcore.controlplane.EndpointFallback;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import reactor.core.publisher.Mono;

/** Downloads configuration using the Controller's control-OSS STS; never persists credentials or YAML. */
final class ControlConfigLoader implements AutoCloseable {
    private final ControllerCredentials controller;
    private final HttpTransport http;
    private Map<String, Object> credential;
    private Mono<Map<String, Object>> pending;
    ControlConfigLoader(ControllerCredentials controller, HttpTransport http) { this.controller = controller; this.http = http; }
    Mono<AgentConfig> load() {
        return credentials(false).flatMap(c -> download(c, Json.text(c.get("agent_config_path"), "agent_config_path"))).map(AgentConfig::parse);
    }
    Mono<String> loadTeams() {
        return credentials(false).flatMap(c -> c.get("teams_config_path") == null ? credentials(true) : Mono.just(c))
            .flatMap(c -> c.get("teams_config_path") == null ? Mono.empty() : download(c, Json.text(c.get("teams_config_path"), "teams_config_path")))
            .onErrorResume(AgentCoreException.class, error -> Integer.valueOf(404).equals(error.status()) ? Mono.empty() : Mono.error(error));
    }
    private Mono<Map<String, Object>> credentials(boolean force) {
        return Mono.defer(() -> {
            synchronized (this) {
                if (!force && credential != null && Instant.parse((String) credential.get("expiration")).isAfter(Instant.now().plusSeconds(60)))
                    return Mono.just(credential);
                if (pending == null) pending = controller.request("/api/v1/credentials/sts", Map.of("purpose", "oss", "target", "control"))
                    .doOnNext(value -> {
                        for (String key : java.util.List.of("access_key_id", "access_key_secret", "security_token", "oss_endpoint", "oss_bucket", "agent_config_path"))
                            Json.text(value.get(key), key);
                        if (!Instant.parse(Json.text(value.get("expiration"), "expiration")).isAfter(Instant.now()))
                            throw new IllegalArgumentException("Controller returned expired control OSS credentials");
                        synchronized (this) { credential = new java.util.LinkedHashMap<>(value); }
                    }).doFinally(signal -> { synchronized (this) { pending = null; } }).cache();
                return pending;
            }
        });
    }
    private Mono<String> download(Map<String, Object> c, String key) {
        String endpoint = (String) c.get("oss_endpoint");
        URI root = AgentConfig.httpUrl(endpoint.contains("://") ? endpoint : "https://" + endpoint);
        return download(c, key, root).onErrorResume(error -> {
            boolean unavailable = EndpointFallback.connectionFailure(error)
                || error instanceof AgentCoreException failure && failure.status() != null && failure.status() >= 500;
            if (!unavailable || !root.getHost().matches("oss-[a-z0-9-]+-internal\\.aliyuncs\\.com")) return Mono.error(error);
            return download(c, key, URI.create(root.toString().replace("-internal.aliyuncs.com", ".aliyuncs.com")));
        });
    }
    private Mono<String> download(Map<String, Object> c, String key, URI endpoint) {
        return Mono.defer(() -> {
            String bucket = (String) c.get("oss_bucket");
            boolean pathStyle = endpoint.getHost().equals("localhost") || endpoint.getHost().matches("[0-9.]+") || endpoint.getHost().contains(":");
            String host = pathStyle || endpoint.getHost().startsWith(bucket + ".") ? endpoint.getRawAuthority() : bucket + "." + endpoint.getRawAuthority();
            String encodedKey = java.util.Arrays.stream(key.split("/", -1)).map(io.agentcore.controlplane.ControlPlane::encode)
                .collect(java.util.stream.Collectors.joining("/"));
            URI url = URI.create(endpoint.getScheme() + "://" + host + (pathStyle ? "/" + bucket : "") + "/" + encodedKey);
            String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().atZone(ZoneOffset.UTC));
            String canonical = "GET\n\n\n" + date + "\nx-oss-security-token:" + c.get("security_token") + "\n/" + bucket + "/" + key;
            String signature;
            try {
                Mac mac = Mac.getInstance("HmacSHA1");
                mac.init(new SecretKeySpec(((String) c.get("access_key_secret")).getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
                signature = Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
            } catch (java.security.GeneralSecurityException error) { return Mono.error(error); }
            return http.bytes("control_config.download", url, () -> Mono.just(Map.of("Date", date,
                "Authorization", "OSS " + c.get("access_key_id") + ":" + signature, "x-oss-security-token", (String) c.get("security_token"))))
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8));
        });
    }
    @Override public synchronized void close() { credential = null; }
}
