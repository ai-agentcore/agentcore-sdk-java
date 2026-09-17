package io.agentcore.verification;

import io.agentcore.Json;
import io.agentcore.event.AgentEvent;
import io.agentcore.server.AgentCoreServer;
import io.agentcore.server.AgentRequest;
import io.agentcore.server.Protocols;
import io.agentcore.springai.SpringAIEvents;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import static org.junit.jupiter.api.Assertions.*;

class ServerProtocolTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"/agui", "/openai/v1/chat/completions"})
    void sessionHeaderRemainsOrdinaryMetadata(String path) throws Exception {
        var received = new java.util.concurrent.CopyOnWriteArrayList<AgentRequest>();
        try (var server = new AgentCoreServer(input -> { received.add(input); return Flux.fromIterable(turn()); }).start(0)) {
            var client = HttpClient.newHttpClient();
            for (String header : List.of("routing-one", "routing-two", "")) {
                var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("threadId", "conversation-one", "runId", "run-one", "messages", List.of()))));
                if (!header.isEmpty()) builder.header("X-AgentCore-Session-ID", header);
                var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                var input = received.get(received.size() - 1);
                assertEquals("conversation-one", input.sessionId());
                assertEquals(header.isEmpty() ? null : header, input.headers().get("x-agentcore-session-id"));
                if (path.equals("/agui")) {
                    var events = decode(response.body());
                    assertEquals("conversation-one", events.get(0).get("threadId"));
                    assertEquals("conversation-one", events.get(events.size() - 1).get("threadId"));
                }
            }
            var headerOnly = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .header("X-AgentCore-Session-ID", "routing-only")
                .POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[]}")).build();
            if (path.equals("/agui")) {
                assertEquals(400, client.send(headerOnly, HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(3, received.size());
                return;
            }
            assertEquals(200, client.send(headerOnly, HttpResponse.BodyHandlers.ofString()).statusCode());
            var input = received.get(received.size() - 1);
            assertNotEquals("routing-only", input.sessionId());
            assertDoesNotThrow(() -> java.util.UUID.fromString(input.sessionId()));
            assertEquals("routing-only", input.headers().get("x-agentcore-session-id"));
        }
    }

    @Test void aguiRejectsMissingNullOrNonStringIdsBeforeInvokingAgent() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var server = new AgentCoreServer(input -> { calls.incrementAndGet(); return Flux.fromIterable(turn()); }).start(0)) {
            var client = HttpClient.newHttpClient();
            for (String field : List.of("threadId", "runId")) {
                var body = new java.util.LinkedHashMap<String, Object>(Map.of("threadId", "thread-one", "runId", "run-one", "messages", List.of()));
                body.remove(field);
                assertEquals(400, request(client, server.port(), "/agui", body).statusCode());
                for (Object value : java.util.Arrays.asList(null, 123, List.of("id"), "", " ")) {
                    body.put(field, value);
                    assertEquals(400, request(client, server.port(), "/agui", body).statusCode());
                }
            }
            assertEquals(0, calls.get());
            assertEquals(200, request(client, server.port(), "/agui", Map.of("threadId", "thread-one", "runId", "run-one", "messages", List.of())).statusCode());
            assertEquals(200, request(client, server.port(), "/openai/v1/chat/completions", Map.of("messages", List.of())).statusCode());
            assertEquals(200, request(client, server.port(), "/openai/v1/chat/completions", Map.of("messages", List.of(), "stream", true)).statusCode());
            assertEquals(3, calls.get());
        }
    }

    @Test void customProtocolsAndReadinessAreIndependentOfLiveness() throws Exception {
        var ready = new java.util.concurrent.atomic.AtomicBoolean();
        var selected = new java.util.ArrayList<>(AgentCoreServer.defaultProtocols());
        selected.add((routes, invoke) -> routes.get("/custom", (request, response) -> invoke.invoke(
            new AgentRequest("custom", "request", "session", "run", List.of(), Map.of(), Map.of()))
            .collectList().flatMap(events -> response.sendString(Mono.just("events=" + events.size())).then())));
        try (var server = new AgentCoreServer(request -> Flux.fromIterable(turn()), selected, ready::get).start(0)) {
            var client = HttpClient.newHttpClient();
            for (String path : List.of("/healthz", "/custom"))
                assertEquals(200, get(client, server.port(), path).statusCode());
            assertEquals("events=10", get(client, server.port(), "/custom").body());
            assertEquals(503, get(client, server.port(), "/readyz").statusCode());
            ready.set(true); assertEquals(200, get(client, server.port(), "/readyz").statusCode());
            assertEquals(200, request(client, server.port(), "/agui", Map.of("threadId", "s", "runId", "r")).statusCode());
        }
        try (var server = new AgentCoreServer(request -> Flux.empty(), List.of(), () -> { throw new IllegalStateException("not initialized"); }).start(0)) {
            var client = HttpClient.newHttpClient();
            assertEquals(503, get(client, server.port(), "/readyz").statusCode());
            assertEquals(404, request(client, server.port(), "/agui", Map.of()).statusCode());
        }
    }
    private static HttpResponse<String> get(HttpClient client, int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    static List<AgentEvent> turn() { return List.of(AgentEvent.textStart("before"), AgentEvent.text("before", "Checking."), AgentEvent.textEnd("before"),
        AgentEvent.toolStart("before", "call_1", "echo"), AgentEvent.toolArgs("call_1", "{}"), AgentEvent.toolEnd("call_1"),
        AgentEvent.toolResult("call_1", "tool output"), AgentEvent.textStart("after"), AgentEvent.text("after", "Finished."), AgentEvent.textEnd("after")); }
    @Test void aguiAndOpenaiActualHttpOutput() throws Exception {
        try (var server = new AgentCoreServer(request -> Flux.fromIterable(turn())).start(0)) {
            HttpClient client = HttpClient.newHttpClient();
            var agui = request(client, server.port(), "/agui", Map.of("threadId", "s1", "runId", "r1", "messages", List.of()));
            assertEquals(200, agui.statusCode());
            var events = decode(agui.body());
            assertEquals("RUN_STARTED", events.get(0).get("type"));
            assertEquals("RUN_FINISHED", events.get(events.size() - 1).get("type"));
            assertEquals(2, events.stream().filter(e -> e.get("type").equals("TEXT_MESSAGE_START")).count());
            assertEquals("call_1", events.stream().filter(e -> e.get("type").equals("TOOL_CALL_RESULT")).findFirst().orElseThrow().get("toolCallId"));
            var openai = request(client, server.port(), "/openai/v1/chat/completions", Map.of("model", "agent", "stream", true, "messages", List.of()));
            assertTrue(openai.body().endsWith("data: [DONE]\n\n"));
            assertTrue(openai.body().contains("tool_calls")); assertFalse(openai.body().contains("tool output"));
            var complete = request(client, server.port(), "/openai/v1/chat/completions", Map.of("model", "agent", "messages", List.of()));
            assertEquals("chat.completion", Json.read(complete.body()).get("object"));
        }
    }
    @Test void springCompletedMessagesPreserveToolResultsAndBoundaries() {
        var assistant = org.springframework.ai.chat.messages.AssistantMessage.builder().content("Checking.").toolCalls(List.of(
            new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("call_1", "function", "echo", "{}"))).build();
        var result = org.springframework.ai.chat.messages.ToolResponseMessage.builder().responses(List.of(
            new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("call_1", "echo", "tool output"))).build();
        var events = SpringAIEvents.from(Flux.just(assistant, result, new org.springframework.ai.chat.messages.AssistantMessage("Finished."))).collectList().block();
        assertEquals(turn().stream().map(AgentEvent::type).toList(), events.stream().map(AgentEvent::type).toList());
    }
    @Test void nullableBodyFieldsReachHandlerAndSnapshotRemainsReadOnly() throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("model", "agent"); body.put("messages", List.of()); body.put("temperature", null);
        body.put("threadId", "thread-one"); body.put("runId", "run-one");
        var snapshot = new AgentRequest("openai", "req", "s", "r", List.of(), Map.of(), body);
        body.put("model", "changed");
        assertEquals("agent", snapshot.body().get("model"));
        assertTrue(snapshot.body().containsKey("temperature")); assertNull(snapshot.body().get("temperature"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.body().put("new", "value"));
        var received = new java.util.concurrent.CopyOnWriteArrayList<AgentRequest>();
        try (var server = new AgentCoreServer(input -> { received.add(input); return Flux.fromIterable(turn()); }).start(0)) {
            var client = HttpClient.newHttpClient();
            for (String path : List.of("/agui", "/openai/v1/chat/completions")) {
                assertEquals(200, request(client, server.port(), path, body).statusCode());
            }
            body.put("stream", true);
            var stream = request(client, server.port(), "/openai/v1/chat/completions", body);
            assertEquals(200, stream.statusCode()); assertTrue(stream.body().contains("data: [DONE]"));
            assertEquals(3, received.size());
            for (var input : received) {
                assertTrue(input.body().containsKey("temperature")); assertNull(input.body().get("temperature"));
            }
        }
    }
    @Test void idleHeartbeatDoesNotRestartExecutionOrDelayCompletion() {
        var subscriptions = new java.util.concurrent.atomic.AtomicInteger();
        StepVerifier.withVirtualTime(() -> Protocols.heartbeat(Flux.defer(() -> { subscriptions.incrementAndGet(); return Flux.just("event").delaySubscription(Duration.ofSeconds(31)); }), Duration.ofSeconds(15)))
            .thenAwait(Duration.ofSeconds(15)).expectNext(": ping\n\n").thenAwait(Duration.ofSeconds(15)).expectNext(": ping\n\n")
            .thenAwait(Duration.ofSeconds(1)).expectNext("event").expectComplete().verify(Duration.ofSeconds(5));
        assertEquals(1, subscriptions.get());
    }
    @Test void streamErrorDoesNotEmitSuccess() {
        var request = new AgentRequest("agui", "req", "s", "r", List.of(), Map.of(), Map.of());
        var events = Protocols.agui(request, Flux.error(new IllegalStateException("not public"))).collectList().block();
        assertTrue(events.get(1).contains("RUN_ERROR")); assertFalse(String.join("", events).contains("not public"));
        assertFalse(String.join("", events).contains("RUN_FINISHED"));
    }
    private static HttpResponse<String> request(HttpClient client, int port, String path, Map<String, Object> body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static List<Map<String, Object>> decode(String sse) {
        return sse.lines().filter(s -> s.startsWith("data:") && !s.contains("[DONE]")).map(s -> Json.read(s.substring(5).trim())).toList();
    }
}
