package io.agentcore;

import com.sun.net.httpserver.HttpServer;
import io.agentcore.auth.AccessKeyCredential;
import io.agentcore.memory.MemoryStore;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class MemoryTaskTest {
    private AgentCore core(HttpServer server) {
        return AgentCore.builder().workspaceId("ws-test").regionId("cn-hangzhou")
            .accessKeyCredential(new AccessKeyCredential("test-ak", "test-sk"))
            .controlPlaneEndpoint(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).build();
    }

    @Test void textAndMessageWritesDefaultToSubmissionButSupportExplicitSync() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MemoryStore.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        var bodies = new CopyOnWriteArrayList<Map<String, Object>>();
        var actions = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            actions.add(exchange.getRequestHeaders().getFirst("x-acs-action"));
            String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var body = Json.read(URLDecoder.decode(raw.substring(5), StandardCharsets.UTF_8)); bodies.add(body);
            var data = Boolean.TRUE.equals(body.get("sync")) ? Map.of("memories", List.of(Map.of("memoryId", "m"))) : Map.of("taskId", "task-1");
            byte[] response = Json.write(Map.of("success", true, "data", data)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        }); server.start();
        try (var core = core(server)) {
            var store = core.memory("store"); var messages = List.of(Map.of("role", "user", "content", "fact"));
            assertEquals("task-1", Json.object(store.addMemories("fact", null).block(Duration.ofSeconds(5)).get("data")).get("taskId"));
            assertEquals("task-1", Json.object(store.addMessages(messages, null).block(Duration.ofSeconds(5)).get("data")).get("taskId"));
            assertEquals("task-1", Json.object(store.addMemories("fact", null, null, false).block(Duration.ofSeconds(5)).get("data")).get("taskId"));
            assertEquals(List.of(Map.of("memoryId", "m")), Json.object(store.addMemories("fact", null, null, true).block(Duration.ofSeconds(5)).get("data")).get("memories"));
            assertEquals(List.of(Map.of("memoryId", "m")), Json.object(store.addMessages(messages, null, null, true).block(Duration.ofSeconds(5)).get("data")).get("memories"));
            assertEquals(List.of(false, false, false, true, true), bodies.stream().map(body -> body.get("sync")).toList());
            assertEquals(java.util.Collections.nCopies(5, "AddMemories"), actions);
            var submitted = appender.list.stream().map(event -> event.getFormattedMessage())
                .filter(message -> message.contains("agentcore.memory.request.submitted")).toList();
            assertEquals(3, submitted.size());
            assertTrue(submitted.stream().allMatch(message -> message.contains("task_id=task-1")));
            assertTrue(submitted.stream().noneMatch(message -> message.contains("fact")));
        } finally { server.stop(0); logger.detachAppender(appender); appender.stop(); }
    }

    @ParameterizedTest @ValueSource(strings = {"PENDING", "RUNNING", "SUCCEEDED", "FAILED", "UNKNOWN"})
    void taskQueryPreservesTheServiceResult(String status) throws Exception {
        var requests = new CopyOnWriteArrayList<String>();
        var data = new java.util.LinkedHashMap<String, Object>(Map.of("taskId", "task/1", "status", status));
        if (status.equals("SUCCEEDED")) data.put("memories", List.of(Map.of("memoryId", "m")));
        if (status.equals("FAILED") || status.equals("UNKNOWN")) data.put("error", Map.of("code", "Failed", "message", "Extraction failed"));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
            requests.add(exchange.getRequestHeaders().getFirst("x-acs-action"));
            requests.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = Json.write(Map.of("success", true, "data", data)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        }); server.start();
        try (var core = core(server)) {
            assertEquals(data, core.memory("store").getMemoryTask("task/1").block(Duration.ofSeconds(5)).get("data"));
            assertEquals(3, requests.size());
            assertEquals("GET /workspaces/ws-test/memorystores/store/memory-tasks/task%2F1", requests.get(0));
            assertEquals("GetMemoryTask", requests.get(1)); assertFalse(requests.get(2).isBlank());
        } finally { server.stop(0); }
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "{\"data\":{}}", "{\"data\":{\"taskId\":\"\"}}"})
    void missingSubmissionTaskIdIsUnknownAndNotRetried(String payload) throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            var body = new java.util.LinkedHashMap<String, Object>(Json.read(payload));
            body.put("success", true); body.put("requestId", "req-add");
            byte[] response = Json.write(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        }); server.start();
        try (var core = core(server)) {
            var error = assertThrows(MemoryStore.AddMemoriesOutcomeUnknownException.class,
                () -> core.memory("store").addMemories("fact", null).block(Duration.ofSeconds(5)));
            assertEquals("req-add", error.requestId()); assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }
}
