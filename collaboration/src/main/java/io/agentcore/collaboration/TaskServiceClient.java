package io.agentcore.collaboration;

import io.agentcore.HttpTransport;
import io.agentcore.Json;
import io.agentcore.controlplane.ControlPlane;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/** Task Service remains authoritative. Uncertain writes are never automatically replayed. */
public final class TaskServiceClient {
    private final Supplier<Mono<Map<String, String>>> environment;
    private final Supplier<Mono<String>> matrixTokens;
    private final HttpTransport http = new HttpTransport();
    public TaskServiceClient(Supplier<Mono<Map<String, String>>> environment) { this(environment, null); }
    public TaskServiceClient(Supplier<Mono<Map<String, String>>> environment, Supplier<Mono<String>> matrixTokens) {
        this.environment = environment; this.matrixTokens = matrixTokens;
    }
    public Mono<Map<String, Object>> request(TeamsProvider.Snapshot teams, String method, String path, Map<String, String> query, Map<String, Object> body, String key) {
        return endpoint(path, query).flatMap(uri -> http.json("Task Service", method, uri,
            () -> headers(teams, key), body, Duration.ofSeconds(30)));
    }
    public Mono<byte[]> readFile(TeamsProvider.Snapshot teams, String path, Map<String, String> query) {
        return endpoint(path, query).flatMap(uri -> http.bytes("Task Service file", uri, () -> headers(teams, null)));
    }
    public Mono<Map<String, Object>> upload(TeamsProvider.Snapshot teams, String path, String remotePath,
            String filename, String contentType, java.net.http.HttpRequest.BodyPublisher body) {
        return endpoint(path, Map.of("path", remotePath)).flatMap(uri ->
            http.upload("Task Service upload", uri, () -> headers(teams, null), filename, contentType, body));
    }
    public Mono<Long> download(TeamsProvider.Snapshot teams, String path, String fileRef, java.nio.file.Path output) {
        return request(teams, "GET", path, Map.of("fileRef", fileRef), null, null).flatMap(reference -> {
            URI url = URI.create(Json.text(reference.get("downloadUrl"), "downloadUrl"));
            if (!("http".equals(url.getScheme()) || "https".equals(url.getScheme())) || url.getHost() == null || url.getUserInfo() != null)
                return Mono.error(new IllegalArgumentException("Invalid file download URL"));
            // The signed URL is neither returned to the model nor sent the Matrix credential.
            return http.downloadTo(url, output);
        });
    }
    private Mono<URI> endpoint(String path, Map<String, String> query) {
        return Mono.defer(environment).flatMap(env -> {
            String endpoint = env.get("AGENTCORE_TASK_SERVICE_ENDPOINT");
            if (endpoint == null) {
                endpoint = Json.text(env.getOrDefault("AGENTCORE_MATRIX_URL", env.get("AGENTTEAMS_MATRIX_URL")), "Matrix URL").replaceAll("/+$", "");
                if (!endpoint.endsWith("/agentteams-app")) endpoint += "/agentteams-app";
            }
            var url = new StringBuilder(io.agentcore.runtime.AgentConfig.httpUrl(endpoint).toString().replaceAll("/+$", "") + path);
            if (!query.isEmpty()) url.append('?').append(String.join("&", query.entrySet().stream()
                .map(e -> ControlPlane.encode(e.getKey()) + "=" + ControlPlane.encode(e.getValue())).toList()));
            return Mono.just(URI.create(url.toString()));
        });
    }
    /** Translate explicit service rejection without exposing raw response bodies to the model. */
    static Map<String, Object> errorResult(io.agentcore.AgentCoreException error) {
        String code = switch (error.status()) {
            case 401, 403 -> "TASK_UNAUTHORIZED";
            case 400, 422 -> "TASK_INVALID";
            case 404 -> "TASK_NOT_FOUND";
            case 409 -> "TASK_CONFLICT";
            case 413 -> "FILE_TOO_LARGE";
            default -> "TASK_UNAVAILABLE";
        };
        return Map.of("ok", false, "code", "COLLABORATION_" + code, "retryable", code.equals("TASK_UNAVAILABLE"),
            "message", error.summary());
    }
    private Mono<Map<String, String>> headers(TeamsProvider.Snapshot teams, String key) {
        if (teams.tokenEnv() == null) return Mono.error(new IllegalStateException("Collaboration identity is not configured"));
        Mono<String> token = matrixTokens == null ? Mono.defer(environment).map(current -> Json.text(current.get(teams.tokenEnv()), "Matrix token"))
            : Mono.defer(matrixTokens);
        return token.map(current -> {
                var headers = new java.util.LinkedHashMap<String, String>();
                headers.put("Authorization", "Bearer " + current);
                if (key != null) headers.put("Idempotency-Key", key);
                return headers;
            });
    }
    public static String id(String value) { return ControlPlane.encode(Json.text(value, "resource ID")); }
    public static String idempotencyKey(String operation, String target, String eventId, Map<String, Object> body) {
        try {
            String value = operation + "\0" + target + "\0" + eventId + "\0" + Json.write(new TreeMap<>(body));
            return "agentcore-" + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
