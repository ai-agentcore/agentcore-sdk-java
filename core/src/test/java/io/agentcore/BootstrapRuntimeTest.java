package io.agentcore;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentcore.runtime.BootstrapRuntime;
import io.agentcore.runtime.BootstrapToken;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;

class BootstrapRuntimeTest {
    @TempDir Path directory;
    HttpServer server;
    String root;
    AtomicInteger tokens = new AtomicInteger();
    AtomicInteger downloads = new AtomicInteger();
    AtomicInteger rejected = new AtomicInteger();
    List<String> jwtRequests = new CopyOnWriteArrayList<>();
    List<String> saHeaders = new CopyOnWriteArrayList<>();
    List<String> queries = new CopyOnWriteArrayList<>();
    volatile boolean shortJwt;
    volatile int tokenStatus;
    volatile boolean rejectFirstSa;
    private static final String YAML = """
        apiVersion: agentteams.io/v1alpha1
        kind: AgentConfig
        metadata: {runtimeName: runtime, workspaceId: ws-bootstrap, regionId: cn-hangzhou}
        spec:
          model: {gatewayUrl: 'http://private.invalid:19011/model-connection'}
          mcp: {gatewayUrl: 'http://private.invalid:19011/mcp-servers'}
          credentials: {header: [{key: Authorization, value: 'Bearer test-consumer'}]}
        """;
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        root = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/api/v1/edge/token", e -> {
            jwtRequests.add((String) Json.read(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).get("jwtToken"));
            int n = tokens.incrementAndGet();
            if (tokenStatus != 0) { e.sendResponseHeaders(tokenStatus, -1); e.close(); return; }
            reply(e, Map.of("token", "sa-" + n, "jwtToken", "jwt-" + n, "expiresAt", Instant.now().plusSeconds(3600).toString(),
                "jwtExpiresAt", Instant.now().plusSeconds(shortJwt && n == 1 ? 301 : 3600).toString()));
        });
        server.createContext("/api/v1/credentials/sts", e -> {
            String sa = e.getRequestHeaders().getFirst("Authorization"); saHeaders.add(sa);
            String query = e.getRequestURI().getQuery(); queries.add(query);
            if (rejectFirstSa && sa.equals("Bearer sa-1")) {
                rejected.incrementAndGet(); e.sendResponseHeaders(401, -1); e.close(); return;
            }
            var result = new java.util.LinkedHashMap<String, Object>();
            result.putAll(Map.of("access_key_id", "test-ak", "access_key_secret", "test-sk", "security_token", "test-sts",
                "expiration", Instant.now().plusSeconds(3600).toString()));
            if (query.contains("target=control")) result.putAll(Map.of("oss_endpoint", root, "oss_bucket", "test-bucket", "agent_config_path", "config/agent.yaml"));
            reply(e, result);
        });
        server.createContext("/api/v1/workload/token", e -> reply(e, Map.of("workloadAccessToken", "test-wat")));
        server.createContext("/test-bucket/config/agent.yaml", e -> {
            downloads.incrementAndGet();
            try {
                var mac = javax.crypto.Mac.getInstance("HmacSHA1");
                mac.init(new javax.crypto.spec.SecretKeySpec("test-sk".getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
                String canonical = "GET\n\n\n" + e.getRequestHeaders().getFirst("Date")
                    + "\nx-oss-security-token:test-sts\n/test-bucket/config/agent.yaml";
                assertEquals("OSS test-ak:" + Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))),
                    e.getRequestHeaders().getFirst("Authorization"));
                assertEquals("test-sts", e.getRequestHeaders().getFirst("x-oss-security-token"));
            } catch (java.security.GeneralSecurityException failure) { throw new java.io.IOException(failure); }
            byte[] bytes = YAML.getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); e.close();
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    String token() {
        return Base64.getEncoder().encodeToString(Json.write(Map.of("product", "agentcore", "jwtToken", "jwt-initial",
            "controllerUrl", root, "modelGatewayUrl", "https://public.example.com", "matrixUrl", root, "futureField", true))
            .getBytes(StandardCharsets.UTF_8));
    }
    @Test void bootstrapWinsOverLocalConfigAndLoadsYamlOnlyInMemory() throws Exception {
        Path path = directory.resolve("agent.yaml"); Files.writeString(path, "invalid-local-config");
        try (var core = AgentCore.builder().configPath(path).bootstrapToken(token()).build()) {
            assertEquals("ws-bootstrap", core.controlPlane().block(Duration.ofSeconds(5)).workspaceId());
            var config = core.bootstrapRuntime().config().block(Duration.ofSeconds(5));
            assertEquals("https://public.example.com/model-connection", config.modelGateway().toString());
            assertEquals("https://public.example.com/mcp-servers", config.mcpGateway().toString());
            assertEquals("Bearer test-consumer", config.headers().get("authorization"));
            assertEquals("test-ak", core.bootstrapRuntime().controller().get("highcode_sdk").block(Duration.ofSeconds(5)).accessKeyId());
            assertEquals("test-wat", core.bootstrapRuntime().controller().workloadAccessToken().block(Duration.ofSeconds(5)));
            assertTrue(queries.stream().anyMatch(q -> q.contains("purpose=oss") && q.contains("target=control")));
            assertTrue(queries.contains("purpose=highcode_sdk"));
            assertEquals(1, downloads.get()); assertEquals(1, tokens.get());
            assertEquals("invalid-local-config", Files.readString(path));
            assertFalse(config.toString().contains("test-consumer"));
        }
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }
    @Test void concurrentExchangeAndIdleJwtRenewalUseNewJwtAndStopOnClose() throws Exception {
        shortJwt = true;
        try (var runtime = new BootstrapRuntime(BootstrapToken.parse(token()), new HttpTransport())) {
            var values = Flux.range(0, 12).flatMap(i -> runtime.get()).collectList().block(Duration.ofSeconds(5));
            assertEquals(12, values.size()); assertEquals(1, tokens.get());
            long limit = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!"sa-2".equals(runtime.get().block(Duration.ofSeconds(2))) && System.nanoTime() < limit) Thread.sleep(20);
            assertEquals(2, tokens.get()); assertEquals(List.of("jwt-initial", "jwt-1"), jwtRequests);
            assertEquals("sa-2", runtime.get().block(Duration.ofSeconds(2)));
            assertFalse(runtime.toString().contains("jwt-"));
            runtime.close(); assertThrows(IllegalStateException.class, () -> runtime.get().block());
        }
    }
    @Test void rejectedSaExchangesWithCurrentJwtAndRetriesControllerOnce() {
        rejectFirstSa = true;
        try (var runtime = new BootstrapRuntime(BootstrapToken.parse(token()), new HttpTransport())) {
            // Keep the initial result callback open until the rejected SA has been refreshed.
            runtime.get().doOnNext(token -> assertEquals("test-ak",
                runtime.controller().get("highcode_sdk").block(Duration.ofSeconds(5)).accessKeyId()))
                .block(Duration.ofSeconds(10));
            assertEquals(1, rejected.get()); assertEquals(2, tokens.get());
            assertEquals(List.of("Bearer sa-1", "Bearer sa-2"), saHeaders);
        }
    }
    @Test void immediateConcurrentRefreshSharesOneNewExchange() {
        try (var runtime = new BootstrapRuntime(BootstrapToken.parse(token()), new HttpTransport())) {
            var values = runtime.get().flatMapMany(token -> Flux.range(0, 12)
                .flatMap(i -> runtime.refresh(token))).collectList().block(Duration.ofSeconds(5));
            assertEquals(java.util.Collections.nCopies(12, "sa-2"), values);
            assertEquals(2, tokens.get());
            assertEquals(List.of("jwt-initial", "jwt-1"), jwtRequests);
        }
    }
    @Test void failedExchangeCanBeRetriedFromErrorCallback() {
        tokenStatus = 401;
        try (var runtime = new BootstrapRuntime(BootstrapToken.parse(token()), new HttpTransport())) {
            String value = runtime.get().onErrorResume(AgentCoreException.class, error -> {
                assertEquals(401, error.status());
                tokenStatus = 0;
                return runtime.get();
            }).block(Duration.ofSeconds(5));
            assertEquals("sa-2", value);
            assertEquals(2, tokens.get());
            assertEquals(List.of("jwt-initial", "jwt-initial"), jwtRequests);
        }
    }
    @Test void invalidTokenDoesNotFallBackToLocalConfigAndAuthIsNotRetried() {
        assertThrows(IllegalArgumentException.class, () -> AgentCore.builder().bootstrapToken("invalid").build());
        tokenStatus = 401;
        try (var runtime = new BootstrapRuntime(BootstrapToken.parse(token()), new HttpTransport())) {
            assertEquals(401, assertThrows(AgentCoreException.class, () -> runtime.get().block(Duration.ofSeconds(5))).status());
            assertEquals(1, tokens.get());
        }
    }
    @Test void transientExchangeRecoversOnNextInvocation() {
        tokenStatus = 503;
        try (var runtime = new BootstrapRuntime(BootstrapToken.parse(token()), new HttpTransport())) {
            var config = runtime.config().onErrorResume(AgentCoreException.class, error -> {
                assertEquals(503, error.status());
                assertEquals(3, tokens.get()); tokenStatus = 0;
                // Retry before the first error callback returns, not after an arbitrary delay.
                return runtime.config();
            }).block(Duration.ofSeconds(5));
            assertEquals("ws-bootstrap", config.workspaceId());
            assertEquals(4, tokens.get()); assertEquals(1, downloads.get());
        }
    }
    private static void reply(HttpExchange e, Map<String, Object> body) throws java.io.IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", "application/json"); e.sendResponseHeaders(200, bytes.length);
        e.getResponseBody().write(bytes); e.close();
    }
}
