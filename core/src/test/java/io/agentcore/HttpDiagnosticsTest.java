package io.agentcore;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class HttpDiagnosticsTest {
    @ParameterizedTest @CsvSource({"false,code,false", "true,code,false", "false,type,false", "true,type,false",
        "false,code,true", "true,code,true", "false,type,true", "true,type,true"})
    void jsonAndStreamErrorsKeepNestedDiagnostics(boolean stream, String codeField, boolean authError) throws Exception {
        int status = authError ? 401 : 429;
        String code = authError ? "invalid_api_key" : "QuotaExceeded";
        String message = authError ? "Incorrect API key provided: sk-PRIVATE." : "Quota exhausted; token=PRIVATE";
        String description = authError ? "Incorrect" : "Quota exhausted";
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var payload = new java.util.LinkedHashMap<String, Object>();
            payload.put("error", Map.of(codeField, code, "message", message));
            if (codeField.equals("code")) payload.put("requestId", "req-model");
            else exchange.getResponseHeaders().set("request-id", "req-model");
            byte[] bytes = Json.write(payload).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(HttpTransport.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            var http = new HttpTransport(); var url = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var error = assertThrows(AgentCoreException.class, () -> {
                if (stream) http.sse("model.stream", url, () -> Mono.just(Map.of()), Map.of(), Duration.ofSeconds(3)).blockLast();
                else http.json("model.completion", "POST", url, () -> Mono.just(Map.of()), Map.of(), Duration.ofSeconds(3)).block();
            });
            assertEquals("req-model", error.requestId()); assertEquals(code, error.serviceCode());
            assertEquals(status, error.status()); assertTrue(error.getMessage().contains(description));
            assertFalse(error.getMessage().contains("PRIVATE"));
            String logs = appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList().toString();
            for (String field : new String[]{"req-model", code, description, String.valueOf(status)}) assertTrue(logs.contains(field));
            assertFalse(logs.contains("PRIVATE"));
        } finally { logger.detachAppender(appender); appender.stop(); server.stop(0); }
    }
}
