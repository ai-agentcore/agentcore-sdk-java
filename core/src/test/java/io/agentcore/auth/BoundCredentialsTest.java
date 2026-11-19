package io.agentcore.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentcore.AgentCoreException;
import io.agentcore.HttpTransport;
import io.agentcore.Json;
import io.agentcore.controlplane.ControlPlane;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoundCredentialsTest {
    @ParameterizedTest
    @CsvSource({
        "InvalidParameter.WorkloadAccessToken, true, 2",
        "InvalidParameter.WorkloadAccessToken, false, 2",
        "WORKLOAD_ACCESS_TOKEN_EXPIRED, true, 2",
        "WORKLOAD_ACCESS_TOKEN_INVALID, true, 2",
        "Forbidden, false, 1",
    })
    void retriesOnlyExplicitWatRejections(String code, boolean retrySucceeds, int expectedCalls) throws IOException {
        var watCalls = new AtomicInteger();
        var seen = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/workspaces/ws-1/credentials", request ->
            reply(request, 200, Map.of("items", List.of(Map.of("name", "my-key", "credentialType", "apiKey")))));
        server.createContext("/api/v1/credentials/sts", request ->
            reply(request, 200, Map.of("access_key_id", "test-ak", "access_key_secret", "test-sk",
                "security_token", "test-sts", "expiration", Instant.now().plusSeconds(3600).toString())));
        server.createContext("/api/v1/workload/token", request ->
            reply(request, 200, Map.of("workloadAccessToken", "wat-" + watCalls.incrementAndGet())));
        server.createContext("/", request -> {
            var body = new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var token = Arrays.stream(body.split("&")).filter(field -> field.startsWith("WorkloadAccessToken="))
                .map(field -> URLDecoder.decode(field.substring("WorkloadAccessToken=".length()), StandardCharsets.UTF_8))
                .findFirst().orElse("");
            seen.add(token);
            if (retrySucceeds && seen.size() == 2) {
                reply(request, 200, Map.of("APIKey", "fixture-key"));
            } else {
                reply(request, code.equals("Forbidden") ? 403 : 400,
                    Map.of("Code", code, "Message", "request denied", "RequestId", "identity-" + seen.size()));
            }
        });
        server.start();
        try {
            var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var controller = new ControllerCredentials(endpoint.toString(), () -> Mono.just("test-sa"), new HttpTransport());
            var controlPlane = new ControlPlane("ws-1", "cn-hangzhou", endpoint, controller);
            var credentials = new BoundCredentials(() -> Mono.just(controlPlane), () -> Mono.just(controller), endpoint);
            if (retrySucceeds) {
                assertEquals("fixture-key", credentials.get("my-key").block(Duration.ofSeconds(5)).value());
            } else {
                var failure = assertThrows(AgentCoreException.class,
                    () -> credentials.get("my-key").block(Duration.ofSeconds(5)));
                assertEquals(code, failure.serviceCode());
                assertEquals("identity-" + expectedCalls, failure.requestId());
                if (expectedCalls == 2) assertNull(failure.getCause());
            }
            assertEquals(expectedCalls == 2 ? List.of("wat-1", "wat-2") : List.of("wat-1"), seen);
            assertEquals(expectedCalls, watCalls.get());
        } finally {
            server.stop(0);
        }
    }

    private static void reply(HttpExchange request, int status, Map<String, Object> payload) throws IOException {
        byte[] body = Json.write(payload).getBytes(StandardCharsets.UTF_8);
        request.getResponseHeaders().set("Content-Type", "application/json");
        request.sendResponseHeaders(status, body.length);
        try (var output = request.getResponseBody()) {
            output.write(body);
        }
    }
}
