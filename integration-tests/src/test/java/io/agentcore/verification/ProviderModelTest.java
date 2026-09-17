package io.agentcore.verification;

import com.sun.net.httpserver.HttpServer;
import io.agentcore.Json;
import io.agentcore.springai.AgentCoreSpringAI;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingRequest;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProviderModelTest {
    @Test void springAiOllamaNativeProtocolChatStreamingEmbeddingAndErrors() throws Exception {
        var requests = new CopyOnWriteArrayList<Map<String, Object>>();
        var failure = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", e -> {
            var body = Json.read(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); requests.add(body);
            if (failure.get()) { e.sendResponseHeaders(400, -1); e.close(); return; }
            boolean stream = Boolean.TRUE.equals(body.get("stream"));
            String first = Json.write(Map.of("model", "fixture", "created_at", "2026-01-01T00:00:00Z",
                "message", Map.of("role", "assistant", "content", "Hello"), "done", false));
            String last = Json.write(Map.of("model", "fixture", "created_at", "2026-01-01T00:00:00Z",
                "message", Map.of("role", "assistant", "content", stream ? " world" : "Hello world"),
                "done", true, "done_reason", "stop", "prompt_eval_count", 3, "eval_count", 2));
            byte[] bytes = (stream ? first + "\n" + last + "\n" : last).getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type", stream ? "application/x-ndjson" : "application/json");
            e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); e.close();
        });
        server.createContext("/api/embed", e -> {
            requests.add(Json.read(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] bytes = "{\"model\":\"fixture-embedding\",\"embeddings\":[[0.1,0.2]],\"prompt_eval_count\":2}".getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type", "application/json");
            e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); e.close();
        }); server.start();
        try {
            var api = OllamaApi.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
            var model = OllamaChatModel.builder().ollamaApi(api).options(OllamaChatOptions.builder().model("fixture").build()).build();
            var embedding = OllamaEmbeddingModel.builder().ollamaApi(api).options(OllamaEmbeddingOptions.builder().model("fixture-embedding").build()).build();
            var client = AgentCoreSpringAI.directModel(model, embedding);
            var result = client.completion(new Prompt("hello")).block(Duration.ofSeconds(10));
            assertEquals("Hello world", result.getResult().getOutput().getText());
            assertEquals(2, result.getMetadata().getUsage().getCompletionTokens());
            assertEquals("Hello world", client.stream(new Prompt("hello")).map(r -> r.getResult().getOutput().getText())
                .collectList().map(parts -> String.join("", parts)).block(Duration.ofSeconds(10)));
            assertArrayEquals(new float[]{0.1f, 0.2f}, client.embedding(new EmbeddingRequest(List.of("hello"), null))
                .block(Duration.ofSeconds(10)).getResult().getOutput());
            assertSame(model, client.nativeModel()); assertEquals(3, requests.size());
            assertEquals("fixture", requests.get(0).get("model"));
            assertEquals("fixture-embedding", requests.get(2).get("model"));
            failure.set(true);
            assertThrows(RuntimeException.class, () -> client.completion(new Prompt("fail")).block(Duration.ofSeconds(10)));
            assertThrows(IllegalStateException.class, () -> AgentCoreSpringAI.directModel(model)
                .embedding(new EmbeddingRequest(List.of("no embedding model"), null)).block());
        } finally { server.stop(0); }
    }
}
