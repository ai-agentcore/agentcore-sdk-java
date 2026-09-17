package io.agentcore;

import com.sun.net.httpserver.HttpServer;
import io.agentcore.auth.AccessKeyCredential;
import io.agentcore.memory.MemoryContext;
import io.agentcore.memory.MemoryScope;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.LinkedHashMap;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class MemoryLoggingTest {
    @ParameterizedTest @CsvSource({"200,false", "200,true", "403,false", "403,true", "503,false", "503,true"})
    void requestAndBestEffortLogsKeepDiagnosticsWithoutPayload(int status, boolean headerOnly) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var payload = new LinkedHashMap<String, Object>(status == 200
                ? Map.of("success", false, "httpStatusCode", 403, "code", "MemoryDenied", "requestId", "req-memory",
                    "message", "Memory access denied; token=PRIVATE_TOKEN")
                : Map.of("Code", "MemoryDenied", "RequestId", "req-memory", "Message", "Memory access denied; token=PRIVATE_TOKEN"));
            if (headerOnly) { payload.remove("requestId"); payload.remove("RequestId"); }
            byte[] bytes = Json.write(payload).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("X-ACS-REQUEST-ID", "req-memory");
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        var logger = (Logger) LoggerFactory.getLogger("io.agentcore");
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try (var core = AgentCore.builder().workspaceId("ws-test").regionId("cn-hangzhou")
                .accessKeyCredential(new AccessKeyCredential("test-ak", "test-sk"))
                .controlPlaneEndpoint(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).build()) {
            var store = core.memory("test-store"); var scope = new MemoryScope("user", "agent", "session");
            var error = assertThrows(AgentCoreException.class, () -> store.searchMemories("PRIVATE_QUERY", scope, 5).block(Duration.ofSeconds(5)));
            // Tea drops response headers when throwing on HTTP errors; do not invent an ID.
            String expectedId = headerOnly && status != 200 ? null : "req-memory";
            assertEquals(expectedId, error.requestId()); assertEquals(status == 503 ? 503 : 403, error.status());
            assertTrue(error.getMessage().contains("Memory access denied"));
            var context = new MemoryContext(store, scope, 5);
            assertEquals("", context.recallForTurn("PRIVATE_QUERY").block(Duration.ofSeconds(5)));
            context.recordTurn("PRIVATE_QUERY", "PRIVATE_ANSWER").block(Duration.ofSeconds(5));
            for (String marker : new String[]{"agentcore.memory.request.failed", "agentcore.memory.adapter.failed"}) {
                var logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).filter(s -> s.contains(marker)).toList();
                assertFalse(logs.isEmpty());
                for (String log : logs) {
                    assertTrue(log.contains("request_id=" + expectedId), log); assertTrue(log.contains("MemoryDenied"), log);
                    assertTrue(log.contains("test-store"), log); assertTrue(log.contains(status == 503 ? "503" : "403"), log);
                    assertTrue(log.contains("Memory access denied"), log);
                }
            }
            String all = error.getMessage() + appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            for (String secret : new String[]{"PRIVATE_TOKEN", "PRIVATE_QUERY", "PRIVATE_ANSWER", "test-sk"}) assertFalse(all.contains(secret));
        } finally { logger.detachAppender(appender); appender.stop(); server.stop(0); }
    }

    @Test void unknownWritePreservesDiagnostics() {
        var cause = new AgentCoreException("AddMemories", 503, "req-add", "Unavailable", "Store unavailable; token=PRIVATE", null);
        var error = new io.agentcore.memory.MemoryStore.AddMemoriesOutcomeUnknownException(cause);
        assertEquals("req-add", error.requestId()); assertEquals(503, error.status());
        assertEquals("Unavailable", error.serviceCode()); assertEquals(cause.serviceMessage(), error.serviceMessage());
        assertTrue(error.getMessage().contains("outcome is unknown")); assertTrue(error.getMessage().contains("Store unavailable"));
        assertFalse(error.getMessage().contains("PRIVATE"));
    }

    @Test void serviceMessageIsBoundedAndDoesNotEchoPayloadFields() {
        for (String suffix : new String[]{"Authorization: Bearer PRIVATE", "api_key=PRIVATE", "securityToken=PRIVATE",
                "Incorrect API key provided: sk-PRIVATE.", "Incorrect API KEY PROVIDED : 'sk-PRIVATE'.",
                "Invalid API Key: sk-PRIVATE", "Invalid api-key=sk-PRIVATE", "Invalid api_key: sk-PRIVATE",
                "Bearer PRIVATE", "Basic PRIVATE", "query: PRIVATE", "{\"messages\":[{\"content\":\"PRIVATE\"}]}",
                "LTAIPRIVATE", "eyJPRIVATE.claims.signature", "https://example.com?token=PRIVATE"}) {
            var error = new AgentCoreException("SearchMemories", 403, "req", "Denied", "Access denied; " + suffix, null);
            assertTrue(error.serviceMessage().startsWith("Access denied; "));
            assertFalse(error.getMessage().contains("PRIVATE"));
        }
        var error = new AgentCoreException("SearchMemories", 403, "req", "Denied", "Error\r\n" + "x".repeat(600), null);
        assertEquals(512, error.serviceMessage().length()); assertFalse(error.serviceMessage().contains("\n"));
        assertEquals("API key is missing", new AgentCoreException("model.completion", 401, "req", "Denied",
            "API key is missing", null).serviceMessage());
    }
}
