package io.agentcore.auth;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.agentcore.HttpTransport;
import io.agentcore.Json;
import io.agentcore.AgentCoreException;
import io.agentcore.controlplane.ControlPlane;
import io.agentcore.controlplane.EndpointFallback;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** STS and WAT have separate lifetimes. The projected SA file is read for each exchange. */
public final class ControllerCredentials implements CredentialProvider {
    private static final String WORKLOAD_TOKEN_PATH = "/api/v1/workload/token";
    private static final long WAT_REFRESH_BEFORE_SECONDS = 30;
    private static final ObjectReader WAT_CLAIMS = new ObjectMapper().reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final String endpoint;
    private final AgentSATokenSource tokens;
    private final HttpTransport http;
    private final Map<String, Cached> cache = new HashMap<>();
    private final Map<String, Mono<AccessKeyCredential>> pending = new HashMap<>();
    private CachedWat wat;
    private Mono<String> watPending;
    private record Cached(AccessKeyCredential credential, Instant expiration) {}
    private record CachedWat(String token, long expiresAt, long refreshAt) {}
    private static final class WorkloadTokenTransportFailure extends RuntimeException {
        WorkloadTokenTransportFailure(IOException cause) { super(cause); }
    }

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
        }).doOnTerminate(() -> { synchronized (this) { pending.remove(purpose); } }).cache();
        pending.put(purpose, request);
        return request;
    }
    public Mono<String> workloadAccessToken() {
        return Mono.defer(this::resolveWorkloadAccessToken);
    }
    private synchronized Mono<String> resolveWorkloadAccessToken() {
        if (wat != null && (wat.expiresAt == 0 || wat.refreshAt > Instant.now().getEpochSecond())) {
            return Mono.just(wat.token);
        }
        if (watPending == null) {
            watPending = request(WORKLOAD_TOKEN_PATH, Map.of(), true)
                .map(value -> Json.text(value.get("workloadAccessToken"), "workloadAccessToken"))
                .doOnNext(token -> {
                    long expiresAt = workloadTokenExpiry(token);
                    synchronized (this) {
                        wat = new CachedWat(token, expiresAt, expiresAt - WAT_REFRESH_BEFORE_SECONDS);
                    }
                })
                .onErrorResume(error -> error instanceof WorkloadTokenTransportFailure || isRetryableStatus(error),
                    this::reuseValidWorkloadToken)
                .doOnTerminate(() -> { synchronized (this) { watPending = null; } })
                .cache();
        }
        return watPending;
    }
    private synchronized Mono<String> reuseValidWorkloadToken(Throwable error) {
        if (wat == null || wat.expiresAt <= Instant.now().getEpochSecond()) {
            return Mono.error(error instanceof WorkloadTokenTransportFailure ? error.getCause() : error);
        }
        wat = new CachedWat(wat.token, wat.expiresAt, wat.expiresAt);
        return Mono.just(wat.token);
    }
    public synchronized void invalidateWorkloadAccessToken(String rejectedToken) {
        if (wat != null && wat.token.equals(rejectedToken)) wat = null;
    }
    private static long workloadTokenExpiry(String token) {
        // The unverified claim is a cache hint only. Zero represents a missing expiry.
        String[] parts = token.split("\\.", 4);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) return 0;
        try {
            JsonNode claims = WAT_CLAIMS.readTree(Base64.getUrlDecoder().decode(parts[1]));
            JsonNode exp = claims.path("exp");
            return exp.isIntegralNumber() && exp.canConvertToLong() ? Math.max(0, exp.longValue()) : 0;
        } catch (IllegalArgumentException | IOException e) {
            return 0;
        }
    }
    private Mono<Map<String, Object>> post(String path, String purpose) {
        return request(path, purpose == null ? Map.of() : Map.of("purpose", purpose));
    }
    public Mono<Map<String, Object>> request(String path, Map<String, String> query) {
        return request(path, query, false);
    }
    private Mono<Map<String, Object>> request(String path, Map<String, String> query, boolean workloadToken) {
        String parameters = query.entrySet().stream().map(e -> ControlPlane.encode(e.getKey()) + "=" + ControlPlane.encode(e.getValue()))
            .collect(java.util.stream.Collectors.joining("&"));
        URI url = URI.create(endpoint + path + (parameters.isEmpty() ? "" : "?" + parameters));
        return Mono.defer(tokens::get).flatMap(token -> send(url, token, workloadToken).onErrorResume(AgentCoreException.class,
            error -> Integer.valueOf(401).equals(error.status())
                ? tokens.refresh(token).flatMap(fresh -> fresh.equals(token)
                    ? Mono.error(error) : send(url, fresh, workloadToken)) : Mono.error(error)));
    }
    private Mono<Map<String, Object>> send(URI url, String token, boolean workloadToken) {
        var response = http.json("Controller credentials", "POST", url,
            () -> Mono.just(Map.of("Authorization", "Bearer " + token)), null, Duration.ofSeconds(30))
            .retryWhen(reactor.util.retry.Retry.backoff(2, Duration.ofMillis(250))
                .filter(error -> EndpointFallback.connectionFailure(error) || isRetryableStatus(error))
                .doBeforeRetry(signal -> org.slf4j.LoggerFactory.getLogger(ControllerCredentials.class)
                    .warn("agentcore.credentials.retry endpoint={}{} attempt={}", url.getAuthority(), url.getPath(), signal.totalRetries() + 2))
                .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
        // Distinguish HTTP I/O from errors reading the Agent SA token file.
        return workloadToken ? response.onErrorMap(IOException.class, WorkloadTokenTransportFailure::new) : response;
    }
    private static boolean isRetryableStatus(Throwable error) {
        return error instanceof AgentCoreException api
            && api.status() != null && Set.of(500, 502, 503, 504).contains(api.status());
    }
}
