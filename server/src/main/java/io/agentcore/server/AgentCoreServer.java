package io.agentcore.server;

import io.agentcore.Json;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public final class AgentCoreServer implements AutoCloseable {
    private final InvokeHandler handler;
    private final List<ProtocolHandler> protocols;
    private final java.util.function.BooleanSupplier readiness;
    private DisposableServer server;
    public AgentCoreServer(InvokeHandler handler) { this(handler, null, () -> true); }
    /** Null protocols select the built-ins; an explicit list replaces them. */
    public AgentCoreServer(InvokeHandler handler, List<ProtocolHandler> protocols, java.util.function.BooleanSupplier readiness) {
        this.handler = handler; this.protocols = protocols == null ? null : List.copyOf(protocols); this.readiness = readiness;
    }
    public synchronized AgentCoreServer start(int port) {
        if (server != null) throw new IllegalStateException("Server is already running");
        server = HttpServer.create().host("0.0.0.0").port(port).route(routes -> {
            routes.get("/healthz", (request, response) -> response.sendString(Mono.just("ok")))
                .get("/readyz", (request, response) -> {
                    boolean ready;
                    try { ready = readiness.getAsBoolean(); }
                    catch (Exception error) {
                        LoggerFactory.getLogger(AgentCoreServer.class).warn("agentcore.server.readiness.failed", error); ready = false;
                    }
                    return response.status(ready ? 200 : 503).header("Content-Type", "application/json")
                        .sendString(Mono.just(Json.write(Map.of("ready", ready))));
                });
            var selected = protocols == null ? defaultProtocols() : protocols;
            for (var protocol : selected) protocol.register(routes, this::invoke);
        }).bindNow();
        return this;
    }
    public int port() { return server.port(); }
    public void await() { server.onDispose().block(); }
    public static List<ProtocolHandler> defaultProtocols() {
        return List.of((routes, agent) -> routes.post("/agui", (request, response) -> serve("agui", request, response, agent)),
            (routes, agent) -> routes.post("/openai/v1/chat/completions", (request, response) -> serve("openai", request, response, agent)));
    }
    private Flux<io.agentcore.event.AgentEvent> invoke(AgentRequest input) {
        return Flux.defer(() -> handler.invoke(input)).doOnError(error ->
            LoggerFactory.getLogger(AgentCoreServer.class).error("agentcore.server.invoke.failed request_id={} run_id={}", input.requestId(), input.runId(), error));
    }
    private static Mono<Void> serve(String protocol, HttpServerRequest request, HttpServerResponse response, InvokeHandler handler) {
        return request.receive().aggregate().asString().flatMap(text -> {
            AgentRequest input;
            try {
                var body = Json.read(text); var headers = new LinkedHashMap<String, String>();
                request.requestHeaders().forEach(e -> headers.put(e.getKey().toLowerCase(java.util.Locale.ROOT), e.getValue()));
                String session = protocol.equals("agui") ? Json.text(body.get("threadId"), "threadId")
                    : String.valueOf(body.getOrDefault("threadId", UUID.randomUUID().toString()));
                String run = protocol.equals("agui") ? Json.text(body.get("runId"), "runId")
                    : String.valueOf(body.getOrDefault("runId", UUID.randomUUID().toString()));
                input = new AgentRequest(protocol, headers.getOrDefault("x-request-id", UUID.randomUUID().toString()), session,
                    run,
                    ((List<?>) body.getOrDefault("messages", List.of())).stream().map(Json::object).toList(), headers, body);
            } catch (IllegalArgumentException | ClassCastException error) {
                return response.status(400).sendString(Mono.just("Invalid request body")).then();
            }
            var execution = handler.invoke(input);
            if (protocol.equals("openai") && !Boolean.TRUE.equals(input.body().get("stream")))
                return execution.collectList().flatMap(events -> response.header("Content-Type", "application/json")
                    .sendString(Mono.just(Json.write(Protocols.completion(input, events)))).then())
                    .onErrorResume(error -> response.status(500).sendString(Mono.just("Agent execution failed")).then());
            var events = protocol.equals("agui") ? Protocols.agui(input, execution) : Protocols.openai(input, execution);
            return response.header("Content-Type", "text/event-stream").header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no").sendString(Protocols.heartbeat(events, Duration.ofSeconds(15))).then();
        });
    }
    @Override public synchronized void close() { if (server != null) { server.disposeNow(); server = null; } }
}
