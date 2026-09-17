package io.agentcore;

import com.sun.net.httpserver.HttpServer;
import io.agentcore.mcp.MCPClient;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class McpLifecycleTest {
    @Test void metadataTimeoutDoesNotCloseSessionOrFailConcurrentTool() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = java.util.concurrent.Executors.newCachedThreadPool(); server.setExecutor(executor);
        var initialized = new AtomicInteger(); var lists = new AtomicInteger(); var calls = new AtomicInteger();
        var toolReceived = new CountDownLatch(1); var release = new CountDownLatch(1);
        server.createContext("/mcp", exchange -> {
            try {
                if (!exchange.getRequestMethod().equals("POST")) { exchange.sendResponseHeaders(405, -1); return; }
                var request = Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (request.get("id") == null) { exchange.sendResponseHeaders(202, -1); return; }
                String method = (String) request.get("method");
                if (method.equals("initialize")) initialized.incrementAndGet();
                if (method.equals("tools/call")) {
                    calls.incrementAndGet(); toolReceived.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Tool was not released");
                }
                if (method.equals("tools/list") && lists.incrementAndGet() == 1)
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("List was not released");
                Object result = method.equals("tools/call")
                    ? Map.of("content", List.of(Map.of("type", "text", "text", "completed"))) : StdioMcpFixture.result(request);
                byte[] body = Json.write(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result)).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        }); server.start();
        // Shorten only the test deadline without exposing a production injection API.
        var constructor = MCPClient.class.getDeclaredConstructor(java.util.function.Supplier.class, Duration.class, Duration.class);
        constructor.setAccessible(true);
        java.util.function.Supplier<Mono<io.modelcontextprotocol.spec.McpClientTransport>> transport = () -> Mono.just(
            io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + server.getAddress().getPort()).endpoint("/mcp").build());
        try (var mcp = constructor.newInstance(transport, Duration.ofMillis(250), Duration.ofSeconds(10))) {
            var pending = mcp.callTool("slow", Map.of()).toFuture();
            try {
                assertTrue(toolReceived.await(5, TimeUnit.SECONDS));
                var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> mcp.listTools().toFuture().get(5, TimeUnit.SECONDS));
                assertInstanceOf(java.util.concurrent.TimeoutException.class, failure.getCause());
                assertFalse(pending.isDone(), "Only the timed-out request may fail");
            } finally { release.countDown(); }
            assertNotNull(pending.get(5, TimeUnit.SECONDS));
            assertNotNull(mcp.listTools().block(Duration.ofSeconds(5)));
            assertEquals(1, initialized.get(), "The healthy Session must remain reusable");
            assertEquals(1, calls.get(), "The running tool must not be replayed");
        } finally { release.countDown(); server.stop(0); executor.shutdownNow(); }
    }
    @Test void closeUnblocksPendingConnectionAcquisition() throws Exception {
        var cancelled = new CountDownLatch(1);
        var mcp = new MCPClient(URI.create("http://localhost:1/mcp"), MCPClient.Transport.STREAMABLE_HTTP,
            () -> Mono.<Map<String, String>>never().doOnCancel(cancelled::countDown));
        var pending = mcp.listTools().toFuture();
        mcp.closeAsync().block(Duration.ofSeconds(1));
        assertTrue(cancelled.await(1, TimeUnit.SECONDS));
        assertThrows(java.util.concurrent.ExecutionException.class, () -> pending.get(1, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> mcp.listTools().block());
    }
    @Test void cancellingLastWaiterCancelsUnfinishedConnection() throws Exception {
        var cancelled = new CountDownLatch(1);
        try (var mcp = new MCPClient(URI.create("http://localhost:1/mcp"), MCPClient.Transport.STREAMABLE_HTTP,
                () -> Mono.<Map<String, String>>never().doOnCancel(cancelled::countDown))) {
            var subscription = mcp.listTools().subscribe();
            subscription.dispose();
            assertTrue(cancelled.await(1, TimeUnit.SECONDS), "Connection acquisition must not outlive every caller");
        }
    }
    @Test void concurrentCallsShareSessionAndFailureReconnectsWithoutReplayingTool() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = java.util.concurrent.Executors.newCachedThreadPool(); server.setExecutor(executor);
        var initialized = new AtomicInteger(); var calls = new AtomicInteger(); var fail = new AtomicBoolean();
        var token = new AtomicReference<>("first"); var sessions = new java.util.concurrent.CopyOnWriteArrayList<String>();
        server.createContext("/mcp", exchange -> {
            if (!exchange.getRequestMethod().equals("POST")) { exchange.sendResponseHeaders(405, -1); exchange.close(); return; }
            var request = Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (request.get("id") == null) { exchange.sendResponseHeaders(202, -1); exchange.close(); return; }
            String method = (String) request.get("method");
            if (method.equals("initialize")) { initialized.incrementAndGet(); sessions.add(exchange.getRequestHeaders().getFirst("X-Test")); }
            if (method.equals("tools/call")) {
                calls.incrementAndGet();
                if (fail.getAndSet(false)) { exchange.sendResponseHeaders(500, -1); exchange.close(); return; }
            }
            Object result = method.equals("tools/call") ? Map.of("content", List.of(Map.of("type", "text", "text",
                Json.object(Json.object(request.get("params")).get("arguments")).get("value"))), "isError", false) : StdioMcpFixture.result(request);
            byte[] body = Json.write(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try (var mcp = new MCPClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"),
                MCPClient.Transport.STREAMABLE_HTTP, () -> Mono.just(Map.of("X-Test", token.get())))) {
            var values = Flux.range(0, 8).flatMap(i -> mcp.callTool("echo", Map.of("value", "user-" + i))
                .map(result -> Json.write(result.content())), 8).collectList().block(Duration.ofSeconds(10));
            for (int i = 0; i < 8; i++) { String expected = "user-" + i; assertEquals(1, values.stream().filter(value -> value.contains(expected)).count()); }
            assertEquals(1, initialized.get());
            fail.set(true);
            assertThrows(RuntimeException.class, () -> mcp.callTool("echo", Map.of("value", "failed")).block(Duration.ofSeconds(5)));
            assertEquals(9, calls.get(), "Failed tools must not automatically execute again");
            token.set("second");
            assertNotNull(mcp.callTool("echo", Map.of("value", "new")).block(Duration.ofSeconds(5)));
            assertEquals(List.of("first", "second"), sessions);
        } finally { server.stop(0); executor.shutdownNow(); }
    }
}
