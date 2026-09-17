package io.agentcore.auth;

import io.agentcore.HttpTransport;
import io.agentcore.Json;
import io.agentcore.AgentCoreException;
import io.agentcore.controlplane.ControlPlane;
import io.agentcore.controlplane.EndpointFallback;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** STS and WAT have separate lifetimes. The projected SA file is read for each exchange. */
public final class ControllerCredentials implements CredentialProvider {
    private final String endpoint;
    private final AgentSATokenSource tokens;
    private final HttpTransport http;
    private final Map<String, Cached> cache = new HashMap<>();
    private final Map<String, Mono<AccessKeyCredential>> pending = new HashMap<>();
    private Mono<String> wat;
    private String watValue;
    private record Cached(AccessKeyCredential credential, Instant expiration) {}

    public ControllerCredentials(String endpoint, Path tokenFile, HttpTransport http) {
        this(endpoint, () -> Mono.fromCallable(() -> Files.readString(tokenFile).trim()).subscribeOn(Schedulers.boundedElastic()), http);
    }
    public ControllerCredentials(String endpoint, AgentSATokenSource tokens, HttpTransport http) {
        this.endpoint = endpoint.replaceAll("/+$", ""); this.tokens = tokens; this.http = http;
    }
    @Override public Mono<AccessKeyCredential> get(String purpose) {
        return Mono.defer(() -> resolve(purpose));
    }
    private synchronized Mono<AccessKeyCredential> resolve(String purpose) {
        Cached cached = cache.get(purpose);
        if (cached != null && cached.expiration.isAfter(Instant.now().plusSeconds(300))) return Mono.just(cached.credential);
        Mono<AccessKeyCredential> current = pending.get(purpose);
        if (current != null) return current;
        var request = post("/api/v1/credentials/sts", purpose).map(value -> {
            var credential = new AccessKeyCredential(Json.text(value.get("access_key_id"), "access_key_id"),
                Json.text(value.get("access_key_secret"), "access_key_secret"), Json.text(value.get("security_token"), "security_token"));
            Instant expires = Instant.parse(Json.text(value.get("expiration"), "expiration"));
            if (!expires.isAfter(Instant.now())) throw new IllegalArgumentException("Controller returned expired STS");
            synchronized (this) { cache.put(purpose, new Cached(credential, expires)); }
            return credential;
        }).doFinally(signal -> { synchronized (this) { pending.remove(purpose); } }).cache();
        pending.put(purpose, request);
        return request;
    }
    public Mono<String> workloadAccessToken() {
        return Mono.defer(() -> {
            synchronized (this) {
                if (wat == null) wat = post("/api/v1/workload/token", null)
                    .map(value -> Json.text(value.get("workloadAccessToken"), "workloadAccessToken"))
                    .doOnNext(value -> { synchronized (this) { watValue = value; } })
                    .doOnError(e -> { synchronized (this) { wat = null; } }).cache();
                return wat;
            }
        });
    }
    public synchronized void invalidateWorkloadAccessToken(String rejectedToken) {
        if (java.util.Objects.equals(watValue, rejectedToken)) { wat = null; watValue = null; }
    }
    private Mono<Map<String, Object>> post(String path, String purpose) {
        return request(path, purpose == null ? Map.of() : Map.of("purpose", purpose));
    }
    public Mono<Map<String, Object>> request(String path, Map<String, String> query) {
        String parameters = query.entrySet().stream().map(e -> ControlPlane.encode(e.getKey()) + "=" + ControlPlane.encode(e.getValue()))
            .collect(java.util.stream.Collectors.joining("&"));
        URI url = URI.create(endpoint + path + (parameters.isEmpty() ? "" : "?" + parameters));
        return Mono.defer(tokens::get).flatMap(token -> send(url, token).onErrorResume(AgentCoreException.class,
            error -> Integer.valueOf(401).equals(error.status())
                ? tokens.refresh(token).flatMap(fresh -> fresh.equals(token) ? Mono.error(error) : send(url, fresh)) : Mono.error(error)));
    }
    private Mono<Map<String, Object>> send(URI url, String token) {
        return http.json("Controller credentials", "POST", url,
            () -> Mono.just(Map.of("Authorization", "Bearer " + token)), null, Duration.ofSeconds(30))
            .retryWhen(reactor.util.retry.Retry.backoff(2, Duration.ofMillis(250))
                .filter(error -> EndpointFallback.connectionFailure(error) || error instanceof AgentCoreException api
                    && api.status() != null && java.util.Set.of(500, 502, 503, 504).contains(api.status()))
                .doBeforeRetry(signal -> org.slf4j.LoggerFactory.getLogger(ControllerCredentials.class)
                    .warn("agentcore.credentials.retry endpoint={}{} attempt={}", url.getAuthority(), url.getPath(), signal.totalRetries() + 2))
                .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }
}
