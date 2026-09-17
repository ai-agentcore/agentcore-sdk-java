package io.agentcore.verification;

import com.sun.net.httpserver.HttpServer;
import io.agentcore.AgentCore;
import io.agentcore.Json;
import io.agentcore.model.ModelClient;
import io.agentcore.springai.AgentCoreSpringAI;
import io.agentcore.langchain4j.AgentCoreLangChain4j;
import io.agentcore.agentscope.AgentCoreAgentScope;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class AnthropicProtocolTest {
    @Test void allFrameworksUseMessagesRouteAndOnlyActualAuthentication() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var paths = new CopyOnWriteArrayList<String>();
        var headers = new CopyOnWriteArrayList<Map<String, List<String>>>();
        var bodies = new CopyOnWriteArrayList<Map<String, Object>>();
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath()); headers.add(Map.copyOf(exchange.getRequestHeaders()));
            var request = Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            bodies.add(request);
            String message = "{\"id\":\"msg1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"custom\",\"content\":[{\"type\":\"text\",\"text\":\"Answer\"}],\"stop_reason\":\"end_turn\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
            String body = message;
            if (Boolean.TRUE.equals(request.get("stream"))) {
                body = event("message_start", Map.of("message", Json.read(message.replace("[{\"type\":\"text\",\"text\":\"Answer\"}]", "[]"))))
                    + event("content_block_start", Map.of("index", 0, "content_block", Map.of("type", "text", "text", "")))
                    + event("content_block_delta", Map.of("index", 0, "delta", Map.of("type", "text_delta", "text", "Answer")))
                    + event("content_block_stop", Map.of("index", 0))
                    + event("message_delta", Map.of("delta", Map.of("stop_reason", "end_turn"), "usage", Map.of("output_tokens", 1)))
                    + event("message_stop", Map.of());
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", Boolean.TRUE.equals(request.get("stream")) ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        try (var core = AgentCore.auto()) {
            var source = core.directModel("custom", URI.create("http://127.0.0.1:" + server.getAddress().getPort()), ModelClient.Protocol.ANTHROPIC,
                () -> Mono.just(Map.of("Authorization", "Bearer consumer")));
            var spring = AgentCoreSpringAI.model(source);
            assertEquals("Answer", spring.call(new org.springframework.ai.chat.prompt.Prompt("hello", spring.getOptions())).getResult().getOutput().getText());
            assertEquals("Answer", AgentCoreLangChain4j.model(source).chat("hello"));
            var msg = io.agentscope.core.message.Msg.builder().name("user").role(io.agentscope.core.message.MsgRole.USER)
                .content(io.agentscope.core.message.TextBlock.builder().text("hello").build()).build();
            assertFalse(AgentCoreAgentScope.model(source).stream(List.of(msg), List.of(), null).collectList().block(Duration.ofSeconds(10)).isEmpty());
            assertEquals(List.of("/v1/messages", "/v1/messages", "/v1/messages"), paths);
            assertEquals(4096, bodies.get(2).get("max_tokens"));
            for (var requestHeaders : headers) {
                assertEquals(List.of("Bearer consumer"), requestHeaders.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("authorization")).findFirst().orElseThrow().getValue());
                assertFalse(requestHeaders.keySet().stream().anyMatch(name -> name.equalsIgnoreCase("x-api-key")), "Placeholder API key leaked to wire");
            }
        } finally { server.stop(0); }
    }
    private static String event(String type, Map<String, Object> data) {
        var body = new java.util.LinkedHashMap<String, Object>(data); body.put("type", type);
        return "event: " + type + "\ndata: " + Json.write(body) + "\n\n";
    }
}
