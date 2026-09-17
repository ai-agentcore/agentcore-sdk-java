package io.agentcore.runtime;

import io.agentcore.AgentCoreException;
import io.agentcore.HttpTransport;
import io.agentcore.Json;
import io.agentcore.auth.AgentSATokenSource;
import io.agentcore.auth.ControllerCredentials;
import io.agentcore.controlplane.EndpointFallback;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.retry.Retry;

/** Bootstrap JWT -> renewable SA -> Controller STS/WAT and in-memory OSS configuration. */
public final class BootstrapRuntime implements AgentSATokenSource, AutoCloseable {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(BootstrapRuntime.class);
    private final BootstrapToken token;
    private final HttpTransport http;
    private final ControllerCredentials controller;
    private final ControlConfigLoader loader;
    private final Mono<AgentConfig> config;
    private final Sinks.One<Void> stopped = Sinks.one();
    private final java.util.concurrent.ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "agentcore-token-refresh"); thread.setDaemon(true); return thread;
    });
    private String jwt;
    private String sa;
    private Instant saExpiration = Instant.EPOCH;
    private Instant jwtExpiration = Instant.EPOCH;
    private Mono<String> pending;
    private ScheduledFuture<?> refreshTask;
    private boolean closed;

    public BootstrapRuntime(BootstrapToken token, HttpTransport http) {
        this.token = token; this.http = http; this.jwt = token.jwt();
        this.controller = new ControllerCredentials(token.controller().toString(), this, http);
        this.loader = new ControlConfigLoader(controller, http);
        this.config = Mono.defer(loader::load).map(value -> new AgentConfig(value.runtimeName(), value.workspaceId(), value.regionId(),
            override(value.modelGateway()), override(value.mcpGateway()), value.headers())).takeUntilOther(stopped.asMono())
            .cacheInvalidateIf(value -> false);
    }
    public ControllerCredentials controller() { return controller; }
    public Mono<AgentConfig> config() { return Mono.defer(() -> { checkOpen(); return config; }); }
    public URI matrixUrl() { return token.matrix(); }
    public Mono<String> teamsConfig() { return Mono.defer(() -> { checkOpen(); return loader.loadTeams(); }).takeUntilOther(stopped.asMono()); }
    public Mono<String> matrixToken() {
        return controller.request("/api/v1/credentials/matrix-token", Map.of()).map(value -> Json.text(value.get("access_token"), "access_token"));
    }
    @Override public Mono<String> get() {
        return Mono.defer(() -> {
            synchronized (this) {
                checkOpen();
                return sa != null && saExpiration.isAfter(Instant.now().plusSeconds(60)) ? Mono.just(sa) : exchange();
            }
        });
    }
    @Override public Mono<String> refresh(String rejectedToken) {
        return Mono.defer(() -> {
            synchronized (this) { checkOpen(); return sa != null && !sa.equals(rejectedToken) ? Mono.just(sa) : exchange(); }
        });
    }
    private synchronized Mono<String> exchange() {
        checkOpen();
        if (pending != null) return pending;
        URI url = token.controller().resolve("/api/v1/edge/token");
        String currentJwt = jwt;
        pending = http.json("bootstrap.exchange", "POST", url, () -> Mono.just(Map.of()), Map.of("jwtToken", currentJwt), Duration.ofSeconds(10))
            .retryWhen(Retry.backoff(2, Duration.ofMillis(100)).filter(BootstrapRuntime::transientFailure)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
            .map(data -> {
                String nextSa = Json.text(data.get("token"), "token");
                String nextJwt = Json.text(data.get("jwtToken"), "jwtToken");
                Instant saExpires = Instant.parse(Json.text(data.get("expiresAt"), "expiresAt"));
                Instant jwtExpires = Instant.parse(Json.text(data.get("jwtExpiresAt"), "jwtExpiresAt"));
                if (!saExpires.isAfter(Instant.now()) || !jwtExpires.isAfter(Instant.now()))
                    throw new IllegalArgumentException("Controller returned expired bootstrap credentials");
                synchronized (this) {
                    checkOpen(); sa = nextSa; jwt = nextJwt; saExpiration = saExpires; jwtExpiration = jwtExpires;
                    schedule(Math.max(1000, Duration.between(Instant.now(), jwtExpiration.minusSeconds(300)).toMillis()));
                }
                LOG.info("agentcore.bootstrap.exchange.succeeded saExpiration={} jwtExpiration={}", saExpires, jwtExpires);
                return nextSa;
            }).doOnError(error -> LOG.warn("agentcore.bootstrap.exchange.failed error_type={}", error.getClass().getSimpleName()))
            // Release the flight before subscribers can request a refresh or retry.
            .takeUntilOther(stopped.asMono()).doOnTerminate(() -> { synchronized (this) { pending = null; } }).cache();
        return pending;
    }
    private synchronized void schedule(long delayMillis) {
        if (closed) return;
        if (refreshTask != null) refreshTask.cancel(false);
        refreshTask = scheduler.schedule(() -> Mono.defer(this::exchange).subscribe(ignored -> {}, error -> {
            synchronized (this) {
                long remaining = Duration.between(Instant.now(), jwtExpiration).toMillis();
                if (!closed && transientFailure(error) && remaining > 1000) schedule(Math.min(30_000, remaining / 2));
            }
        }), delayMillis, TimeUnit.MILLISECONDS);
    }
    private URI override(URI mounted) {
        String path = mounted.getRawPath();
        return path.isEmpty() || path.equals("/") ? token.gateway()
            : URI.create(token.gateway().getScheme() + "://" + token.gateway().getRawAuthority() + path);
    }
    private static boolean transientFailure(Throwable error) {
        return EndpointFallback.connectionFailure(error) || error instanceof AgentCoreException api && api.status() != null
            && java.util.Set.of(500, 502, 503, 504).contains(api.status());
    }
    private synchronized void checkOpen() { if (closed) throw new IllegalStateException("Bootstrap runtime is closed"); }
    @Override public synchronized void close() {
        closed = true; stopped.tryEmitEmpty(); scheduler.shutdownNow(); sa = null; jwt = null; loader.close();
    }
    @Override public String toString() { return "BootstrapRuntime(<redacted>)"; }
}
