package io.agentcore;

import com.sun.net.httpserver.HttpServer;
import io.agentcore.auth.ControllerCredentials;
import io.agentcore.memory.MemoryStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class CredentialLifecycleTest {
    @TempDir Path directory;
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {401, 503})
    void failedStsCanBeRetriedFromErrorCallback(int initialStatus) throws Exception {
        var status = new AtomicInteger(initialStatus); var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/credentials/sts", exchange -> {
            calls.incrementAndGet();
            if (status.get() != 0) {
                exchange.sendResponseHeaders(status.get(), -1); exchange.close(); return;
            }
            byte[] body = Json.write(Map.of("access_key_id", "test-ak", "access_key_secret", "test-sk",
                "security_token", "test-sts", "expiration", Instant.now().plusSeconds(3600).toString()))
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try {
            var credentials = new ControllerCredentials("http://127.0.0.1:" + server.getAddress().getPort(),
                () -> Mono.just("sa-fixture"), new HttpTransport());
            var result = credentials.get("highcode_sdk").onErrorResume(AgentCoreException.class, error -> {
                assertEquals(initialStatus, error.status());
                assertEquals(initialStatus == 503 ? 3 : 1, calls.get());
                status.set(0);
                return Flux.range(0, 10).flatMap(i -> credentials.get("highcode_sdk"))
                    .collectList().map(values -> { assertEquals(10, values.size()); return values.get(0); });
            }).block(Duration.ofSeconds(5));
            assertEquals("test-ak", result.accessKeyId());
            assertEquals(initialStatus == 503 ? 4 : 2, calls.get(), "Recovery callers must share one fresh request");
            assertSame(result, credentials.get("highcode_sdk").block(Duration.ofSeconds(1)));
        } finally { server.stop(0); }
    }
    @Test void stsSingleFlightAndWatRefreshDoNotRetainAnOldSaToken() throws Exception {
        Path token = directory.resolve("token"); Files.writeString(token, "sa-one");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var stsCalls = new AtomicInteger(); var watCalls = new AtomicInteger(); var saHeaders = new CopyOnWriteArrayList<String>();
        server.createContext("/api/v1", exchange -> {
            saHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            Map<String, Object> body;
            if (exchange.getRequestURI().getPath().endsWith("/sts")) {
                stsCalls.incrementAndGet();
                assertTrue(exchange.getRequestURI().getQuery().startsWith("purpose="));
                body = Map.of("access_key_id", "test-ak", "access_key_secret", "test-sk", "security_token", "test-sts", "expiration", Instant.now().plusSeconds(3600).toString());
            } else body = Map.of("workloadAccessToken", "wat-" + watCalls.incrementAndGet());
            byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        try {
            var credentials = new ControllerCredentials("http://127.0.0.1:" + server.getAddress().getPort(), token, new HttpTransport());
            assertEquals(10, Flux.range(0, 10).flatMap(i -> credentials.get("highcode_sdk")).collectList().block(Duration.ofSeconds(5)).size());
            assertEquals(1, stsCalls.get());
            assertEquals("wat-1", credentials.workloadAccessToken().block(Duration.ofSeconds(5)));
            Files.writeString(token, "sa-two"); credentials.invalidateWorkloadAccessToken("wat-1");
            assertEquals("wat-2", credentials.workloadAccessToken().block(Duration.ofSeconds(5)));
            credentials.invalidateWorkloadAccessToken("wat-1");
            assertEquals("wat-2", credentials.workloadAccessToken().block(Duration.ofSeconds(5)));
            assertEquals(2, watCalls.get()); assertEquals(List.of("Bearer sa-one", "Bearer sa-one", "Bearer sa-two"), saHeaders);
        } finally { server.stop(0); }
    }
    @Test void localMemoryConfigurationErrorIsNotAnUnknownWrite() {
        var failure = new IllegalArgumentException("missing workspace");
        var memory = new MemoryStore("store", () -> Mono.error(failure));
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> memory.addMemories("text", null).block()));
    }
    @Test void controllerTransientFailureRetriesButAuthenticationFailureDoesNot() throws Exception {
        Path token = directory.resolve("token"); Files.writeString(token, "sa-fixture");
        var calls = new AtomicInteger(); var rejected = new java.util.concurrent.atomic.AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/workload/token", exchange -> {
            int call = calls.incrementAndGet();
            if (rejected.get() || call < 3) {
                exchange.sendResponseHeaders(rejected.get() ? 401 : 503, -1); exchange.close(); return;
            }
            byte[] bytes = "{\"workloadAccessToken\":\"wat-fixture\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        try {
            var credentials = new ControllerCredentials("http://127.0.0.1:" + server.getAddress().getPort(), token, new HttpTransport());
            assertEquals("wat-fixture", credentials.workloadAccessToken().block(Duration.ofSeconds(5)));
            assertEquals(3, calls.get());
            credentials.invalidateWorkloadAccessToken("wat-fixture"); rejected.set(true);
            assertEquals(401, assertThrows(AgentCoreException.class, () -> credentials.workloadAccessToken().block(Duration.ofSeconds(5))).status());
            assertEquals(4, calls.get());
            rejected.set(false);
            assertEquals("wat-fixture", credentials.workloadAccessToken().block(Duration.ofSeconds(5)));
        } finally { server.stop(0); }
    }
}
