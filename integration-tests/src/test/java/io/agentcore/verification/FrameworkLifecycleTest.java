package io.agentcore.verification;

import com.sun.net.httpserver.HttpServer;
import io.agentcore.AgentCore;
import io.agentcore.Json;
import io.agentcore.event.AgentEvent;
import io.agentcore.model.ModelClient;
import io.agentcore.springai.AgentCoreSpringAI;
import io.agentcore.springai.SpringAIEvents;
import io.agentcore.langchain4j.AgentCoreLangChain4j;
import io.agentcore.langchain4j.LangChain4jEvents;
import io.agentcore.agentscope.AgentCoreAgentScope;
import io.agentcore.agentscope.AgentScopeEvents;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class FrameworkLifecycleTest {
    interface Assistant { dev.langchain4j.service.TokenStream chat(String input); }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void langchainThinkingHandlePropagatesCancellationWithoutEmittingText(boolean cancelBeforeThinking) throws Exception {
        var ready = new CountDownLatch(1);
        var response = new java.util.concurrent.atomic.AtomicReference<dev.langchain4j.model.chat.response.StreamingChatResponseHandler>();
        var cancelled = new AtomicBoolean();
        var handle = new dev.langchain4j.model.chat.response.StreamingHandle() {
            @Override public void cancel() { cancelled.set(true); }
            @Override public boolean isCancelled() { return cancelled.get(); }
        };
        var model = new dev.langchain4j.model.chat.StreamingChatModel() {
            @Override public void doChat(dev.langchain4j.model.chat.request.ChatRequest request,
                    dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler) {
                response.set(handler); ready.countDown();
            }
        };
        var assistant = dev.langchain4j.service.AiServices.builder(Assistant.class).streamingChatModel(model).build();
        var events = new java.util.concurrent.CopyOnWriteArrayList<AgentEvent>();
        var subscription = LangChain4jEvents.from(() -> assistant.chat("hello")).subscribe(events::add);
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            if (cancelBeforeThinking) subscription.dispose();
            response.get().onPartialThinking(new dev.langchain4j.model.chat.response.PartialThinking("thinking"),
                new dev.langchain4j.model.chat.response.PartialThinkingContext(handle));
            if (!cancelBeforeThinking) subscription.dispose();
            assertTrue(cancelled.get()); assertTrue(events.isEmpty());
        } finally { subscription.dispose(); }
    }
    @org.junit.jupiter.api.Test
    void langchainCancellationBeforeToolOnlyResponseReachesNativeHandle() throws Exception {
        var received = new CountDownLatch(1); var release = new CountDownLatch(1); var executed = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            boolean first = calls.incrementAndGet() == 1;
            try {
                if (first) { received.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Response not released"); }
                String body = first ? "data: " + Json.write(Map.of("id", "fixture", "object", "chat.completion.chunk", "created", 1, "model", "custom",
                    "choices", List.of(Map.of("index", 0, "delta", Map.of("role", "assistant", "tool_calls", List.of(Map.of(
                        "index", 0, "id", "call_one", "type", "function", "function", Map.of("name", "echo", "arguments", "{}")))))))) + "\n\n"
                    : new String(chunk("Finished", null), StandardCharsets.UTF_8);
                body += new String(chunk("", first ? "tool_calls" : "stop"), StandardCharsets.UTF_8) + "data: [DONE]\n\n";
                var bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        }); server.start();
        reactor.core.Disposable subscription = null;
        try {
            var model = dev.langchain4j.model.openai.OpenAiStreamingChatModel.builder().modelName("custom")
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1").apiKey("fixture").build();
            var tool = new io.agentcore.tool.Tool("echo", "test", Map.of("type", "object", "properties", Map.of()), args -> {
                executed.countDown(); return Mono.just("tool output");
            });
            var assistant = dev.langchain4j.service.AiServices.builder(Assistant.class).streamingChatModel(model)
                .tools(AgentCoreLangChain4j.tools(List.of(tool))).build();
            subscription = LangChain4jEvents.from(() -> assistant.chat("Use echo")).subscribe();
            assertTrue(received.await(5, TimeUnit.SECONDS));
            subscription.dispose(); release.countDown();
            assertFalse(executed.await(1, TimeUnit.SECONDS), "Native cancellation must prevent this pending tool-only turn");
            assertEquals(1, calls.get(), "No follow-up model call after cancellation");
        } finally { if (subscription != null) subscription.dispose(); release.countDown(); server.stop(0); }
    }
    @ParameterizedTest @ValueSource(strings = {"spring", "langchain4j", "agentscope"})
    void parallelExecutionsKeepBoundariesAndCancellationClosesStream(String framework) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = java.util.concurrent.Executors.newCachedThreadPool(); server.setExecutor(executor);
        var hold = new AtomicBoolean(); var error = new AtomicBoolean(); var disconnected = new CountDownLatch(1);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (error.get()) {
                byte[] failure = "{\"error\":{\"message\":\"fixture error\",\"type\":\"invalid_request_error\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, failure.length); exchange.getResponseBody().write(failure); exchange.close(); return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, 0);
            try {
                exchange.getResponseBody().write(chunk("hello", null)); exchange.getResponseBody().flush();
                if (hold.get()) for (int i = 0; i < 200; i++) {
                    Thread.sleep(25); exchange.getResponseBody().write(chunk("x".repeat(2048), null)); exchange.getResponseBody().flush();
                }
                exchange.getResponseBody().write(chunk("", "stop")); exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            } catch (java.io.IOException closed) { disconnected.countDown(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        }); server.start();
        try (var core = AgentCore.auto()) {
            var model = core.directModel("custom", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                ModelClient.Protocol.OPENAI, () -> Mono.just(Map.of("Authorization", "Bearer fixture")));
            Function<String, Flux<AgentEvent>> invoke = invoker(framework, model);
            var turns = Flux.range(0, 4).flatMap(i -> invoke.apply("hello-" + i).collectList(), 4)
                .collectList().block(Duration.ofSeconds(15));
            assertEquals(4, turns.size());
            var ids = new java.util.HashSet<Object>();
            for (var turn : turns) {
                var starts = turn.stream().filter(e -> e.type() == AgentEvent.Type.TEXT_START).toList();
                assertEquals(1, starts.size()); assertTrue(ids.add(starts.get(0).data().get("messageId")));
                assertEquals(1, turn.stream().filter(e -> e.type() == AgentEvent.Type.TEXT_END).count());
            }
            hold.set(true);
            assertNotNull(invoke.apply("cancel").filter(e -> e.type() == AgentEvent.Type.TEXT_DELTA).next().block(Duration.ofSeconds(10)));
            assertTrue(disconnected.await(6, TimeUnit.SECONDS), framework + " must close the upstream HTTP stream after cancellation");
            hold.set(false); error.set(true);
            assertThrows(RuntimeException.class, () -> invoke.apply("failure").collectList().block(Duration.ofSeconds(10)));
        } finally { server.stop(0); executor.shutdownNow(); }
    }
    private static Function<String, Flux<AgentEvent>> invoker(String framework, ModelClient source) {
        if (framework.equals("spring")) {
            var model = AgentCoreSpringAI.model(source);
            return input -> SpringAIEvents.stream(model, new org.springframework.ai.chat.prompt.Prompt(input));
        }
        if (framework.equals("langchain4j")) {
            var assistant = dev.langchain4j.service.AiServices.builder(Assistant.class)
                .streamingChatModel(AgentCoreLangChain4j.streamingModel(source)).build();
            return input -> LangChain4jEvents.from(() -> assistant.chat(input));
        }
        var model = AgentCoreAgentScope.model(source);
        return input -> Flux.using(() -> io.agentscope.core.ReActAgent.builder().name("fixture").model(model).maxIters(2).build(),
            agent -> AgentScopeEvents.from(agent.streamEvents(input)), io.agentscope.core.ReActAgent::close);
    }
    private static byte[] chunk(String text, String finish) {
        var choice = new java.util.LinkedHashMap<String, Object>(); choice.put("index", 0); choice.put("delta", Map.of("role", "assistant", "content", text));
        if (finish != null) choice.put("finish_reason", finish);
        return ("data: " + Json.write(Map.of("id", "fixture", "object", "chat.completion.chunk", "created", 1, "model", "custom", "choices", List.of(choice))) + "\n\n").getBytes(StandardCharsets.UTF_8);
    }
}
