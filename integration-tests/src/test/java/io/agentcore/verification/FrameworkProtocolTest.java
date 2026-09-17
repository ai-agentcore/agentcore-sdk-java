package io.agentcore.verification;

import com.sun.net.httpserver.HttpServer;
import io.agentcore.AgentCore;
import io.agentcore.Json;
import io.agentcore.model.ModelClient;
import io.agentcore.tool.Tool;
import io.agentcore.event.AgentEvent;
import io.agentcore.springai.AgentCoreSpringAI;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class FrameworkProtocolTest {
    HttpServer server; AgentCore core; ModelClient source;
    AtomicInteger calls = new AtomicInteger(); AtomicInteger executed = new AtomicInteger();
    List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
    List<List<String>> authentication = new CopyOnWriteArrayList<>();
    java.util.concurrent.atomic.AtomicBoolean failModel = new java.util.concurrent.atomic.AtomicBoolean();
    Tool tool;
    List<Map<String, Object>> memoryRequests = new CopyOnWriteArrayList<>();
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authentication.add(exchange.getRequestHeaders().getOrDefault("Authorization", List.of()));
            var request = Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); requests.add(request);
            if (failModel.get()) {
                byte[] bytes = "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"fixture failure\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); return;
            }
            boolean first = calls.getAndIncrement() == 0 && request.containsKey("tools");
            var message = first ? Map.of("role", "assistant", "content", "I will check.", "tool_calls", List.of(Map.of("id", "call_one", "type", "function", "function", Map.of("name", "echo", "arguments", "{}"))))
                : Map.of("role", "assistant", "content", "Final answer.");
            boolean streaming = Boolean.TRUE.equals(request.get("stream"));
            String body;
            if (streaming) {
                Map<String, Object> delta = new java.util.LinkedHashMap<>(message);
                if (first) delta.put("tool_calls", List.of(Map.of("index", 0, "id", "call_one", "type", "function", "function", Map.of("name", "echo", "arguments", "{}"))));
                body = "data: " + Json.write(Map.of("id", "reply-" + calls.get(), "object", "chat.completion.chunk", "created", 1, "model", "custom",
                    "choices", List.of(Map.of("index", 0, "delta", delta)))) + "\n\n";
                body += "data: " + Json.write(Map.of("id", "reply-" + calls.get(), "object", "chat.completion.chunk", "created", 1, "model", "custom",
                    "choices", List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", first ? "tool_calls" : "stop")))) + "\n\ndata: [DONE]\n\n";
            } else body = Json.write(Map.of("id", "reply-" + calls.get(), "object", "chat.completion", "created", 1, "model", "custom", "choices",
                List.of(Map.of("index", 0, "message", message, "finish_reason", first ? "tool_calls" : "stop"))));
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/workspaces", exchange -> {
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            memoryRequests.add(Json.read(java.net.URLDecoder.decode(form.substring(5), StandardCharsets.UTF_8)));
            String response = exchange.getRequestURI().getPath().endsWith("/search")
                ? "{\"success\":true,\"data\":{\"memories\":[{\"memory\":{\"content\":{\"text\":\"The user prefers green.\"}}}]}}"
                : "{\"success\":true,\"data\":{\"memories\":[{\"memoryId\":\"memory-1\"}]}}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start(); core = AgentCore.auto();
        source = core.directModel("custom", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"), ModelClient.Protocol.OPENAI,
            () -> Mono.just(Map.of("Authorization", "Bearer test-consumer")));
        tool = new Tool("echo", "Return a test result", Map.of("type", "object", "properties", Map.of()), args -> {
            executed.incrementAndGet(); return Mono.just("tool output");
        });
    }
    @AfterEach void close() { core.close(); server.stop(0); }
    @Test void agentscopeOpenaiDoesNotInventAnOutputLimit() {
        var msg = io.agentscope.core.message.Msg.builder().name("user").role(io.agentscope.core.message.MsgRole.USER)
            .content(io.agentscope.core.message.TextBlock.builder().text("hello").build()).build();
        var model = AgentCoreAgentScope.model(source);
        model.stream(List.of(msg), List.of(), null).collectList().block(Duration.ofSeconds(10));
        assertFalse(requests.get(0).containsKey("max_tokens"));
        model.stream(List.of(msg), List.of(), io.agentscope.core.model.GenerateOptions.builder().maxTokens(256).build())
            .collectList().block(Duration.ofSeconds(10));
        assertEquals(256, requests.get(1).get("max_tokens"));
        var configured = new ModelClient(new ModelClient.Settings("custom", source.settings().baseUrl(), ModelClient.Protocol.OPENAI, 1024, false),
            source::headers, new io.agentcore.HttpTransport());
        var configuredModel = AgentCoreAgentScope.model(configured);
        configuredModel.stream(List.of(msg), List.of(), null).collectList().block(Duration.ofSeconds(10));
        assertEquals(1024, requests.get(2).get("max_tokens"));
        configuredModel.stream(List.of(msg), List.of(), io.agentscope.core.model.GenerateOptions.builder().maxTokens(128).build())
            .collectList().block(Duration.ofSeconds(10));
        assertEquals(128, requests.get(3).get("max_tokens"));
    }
    @Test void springNativeModelSendsHeadersAndExecutesAdaptedTool() {
        var model = AgentCoreSpringAI.model(source);
        var options = ((org.springframework.ai.openai.OpenAiChatOptions) model.getOptions()).mutate().toolCallbacks(AgentCoreSpringAI.tool(tool)).build();
        var prompt = new org.springframework.ai.chat.prompt.Prompt("Use echo", options);
        var response = model.call(prompt);
        assertEquals("call_one", response.getResult().getOutput().getToolCalls().get(0).id());
        var manager = org.springframework.ai.model.tool.ToolCallingManager.builder().build();
        var result = manager.executeToolCalls(prompt, response);
        var answer = model.call(new org.springframework.ai.chat.prompt.Prompt(result.conversationHistory(), options));
        assertEquals("Final answer.", answer.getResult().getOutput().getText());
        assertEquals(1, executed.get()); assertAuth();
    }
    @Test void springStreamingAgentLoopPreservesToolsAndMessageBoundaries() {
        var model = AgentCoreSpringAI.model(source);
        var options = ((org.springframework.ai.openai.OpenAiChatOptions) model.getOptions()).mutate().toolCallbacks(AgentCoreSpringAI.tool(tool)).build();
        var events = io.agentcore.springai.SpringAIEvents.stream(model, new org.springframework.ai.chat.prompt.Prompt("Use echo", options))
            .collectList().block(Duration.ofSeconds(20));
        assertTurn(events); assertEquals(1, executed.get()); assertAuth();
    }
    interface Assistant { dev.langchain4j.service.TokenStream chat(String message); }
    @Test void springApplicationModelUsesOwnCredentialsWithAgentCoreToolsAndEvents() {
        var model = org.springframework.ai.openai.OpenAiChatModel.builder()
            .options(org.springframework.ai.openai.OpenAiChatOptions.builder().model("custom")
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                .apiKey("application-key").maxRetries(0).build()).build();
        var options = org.springframework.ai.model.tool.ToolCallingChatOptions.builder()
            .toolCallbacks(AgentCoreSpringAI.tool(tool)).build();
        var events = io.agentcore.springai.SpringAIEvents.stream(model,
            new org.springframework.ai.chat.prompt.Prompt("Use echo", options)).collectList().block(Duration.ofSeconds(20));
        assertTurn(events); assertApplicationAuth();
    }
    @Test void langchainApplicationModelUsesOwnCredentialsWithAgentCoreToolsAndEvents() {
        var model = dev.langchain4j.model.openai.OpenAiStreamingChatModel.builder().modelName("custom")
            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1").apiKey("application-key").build();
        var assistant = dev.langchain4j.service.AiServices.builder(Assistant.class).streamingChatModel(model)
            .tools(AgentCoreLangChain4j.tools(List.of(tool))).build();
        assertTurn(LangChain4jEvents.from(() -> assistant.chat("Use echo")).collectList().block(Duration.ofSeconds(20)));
        assertApplicationAuth();
    }
    @Test void agentscopeApplicationModelUsesOwnCredentialsWithAgentCoreToolsAndEvents() {
        var model = io.agentscope.extensions.model.openai.OpenAIChatModel.builder().modelName("custom")
            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1").endpointPath("/chat/completions")
            .apiKey("application-key").build();
        var toolkit = new io.agentscope.core.tool.Toolkit(); toolkit.registerAgentTool(AgentCoreAgentScope.tool(tool));
        try (var agent = io.agentscope.core.ReActAgent.builder().name("test").model(model).toolkit(toolkit).maxIters(3).build()) {
            assertTurn(AgentScopeEvents.from(agent.streamEvents("Use echo")).collectList().block(Duration.ofSeconds(20)));
        }
        assertApplicationAuth();
    }
    private void assertApplicationAuth() {
        assertEquals(1, executed.get()); assertFalse(authentication.isEmpty());
        for (var values : authentication) assertEquals(List.of("Bearer application-key"), values);
    }
    @Test void langchain4jAgentLoopPreservesToolsAndMessageBoundaries() {
        var assistant = dev.langchain4j.service.AiServices.builder(Assistant.class)
            .streamingChatModel(AgentCoreLangChain4j.streamingModel(source)).tools(AgentCoreLangChain4j.tools(List.of(tool))).build();
        var events = LangChain4jEvents.from(() -> assistant.chat("Use echo")).collectList().block(Duration.ofSeconds(20));
        assertTurn(events); assertEquals(1, executed.get()); assertAuth();
    }
    @Test void agentscopeAgentLoopPreservesToolsAndMessageBoundaries() {
        var toolkit = new io.agentscope.core.tool.Toolkit(); toolkit.registerAgentTool(AgentCoreAgentScope.tool(tool));
        try (var agent = io.agentscope.core.ReActAgent.builder().name("test").model(AgentCoreAgentScope.model(source)).toolkit(toolkit).maxIters(3).build()) {
            var events = AgentScopeEvents.from(agent.streamEvents("Use echo")).collectList().block(Duration.ofSeconds(20));
            assertTurn(events); assertEquals(1, executed.get()); assertAuth();
        }
    }
    @Test void memoryRecallThroughAllFrameworksAndExplicitCurrentTurnWriteBack() {
        var cp = new io.agentcore.controlplane.ControlPlane("workspace", "region", source.settings().baseUrl().resolve("/"),
            purpose -> Mono.just(new io.agentcore.auth.AccessKeyCredential("test-ak", "test-sk")));
        var memory = new io.agentcore.memory.MemoryContext(new io.agentcore.memory.MemoryStore("store", () -> Mono.just(cp)),
            new io.agentcore.memory.MemoryScope("user-one", "agent-one", "session-one"), 3);
        var spring = org.springframework.ai.chat.client.ChatClient.builder(AgentCoreSpringAI.model(source))
            .defaultAdvisors(new io.agentcore.springai.MemoryAdvisor(memory)).build();
        assertEquals("Final answer.", spring.prompt().user("Which color?").call().content());
        var langchain = dev.langchain4j.service.AiServices.builder(Assistant.class).streamingChatModel(AgentCoreLangChain4j.streamingModel(source))
            .contentRetriever(new io.agentcore.langchain4j.MemoryRetriever(memory)).build();
        assertNotNull(LangChain4jEvents.from(() -> langchain.chat("Which color?")).collectList().block(Duration.ofSeconds(10)));
        try (var agent = io.agentscope.core.ReActAgent.builder().name("memory-agent").model(AgentCoreAgentScope.model(source))
                .middleware(new io.agentcore.agentscope.MemoryMiddleware(memory)).maxIters(2).build()) {
            assertNotNull(agent.streamEvents("Which color?").collectList().block(Duration.ofSeconds(10)));
        }
        assertEquals(3, memoryRequests.size());
        for (var request : requests) assertTrue(Json.write(request.get("messages")).contains("The user prefers green."));
        for (var request : memoryRequests) assertEquals("user-one", Json.object(request.get("scope")).get("userId"));
        memory.writeBack("Which color?", "Green.").block(Duration.ofSeconds(10));
        assertEquals(4, memoryRequests.size());
        assertEquals(List.of(Map.of("role", "user", "content", "Which color?"), Map.of("role", "assistant", "content", "Green.")), memoryRequests.get(3).get("messages"));
    }
    private void assertAuth() { assertFalse(authentication.isEmpty()); for (var values : authentication) assertEquals(List.of("Bearer test-consumer"), values); }
    private io.agentcore.memory.MemoryContext splitMemory() {
        var cp = new io.agentcore.controlplane.ControlPlane("workspace", "region", source.settings().baseUrl().resolve("/"),
            purpose -> Mono.just(new io.agentcore.auth.AccessKeyCredential("test-ak", "test-sk")));
        return new io.agentcore.memory.MemoryContext(new io.agentcore.memory.MemoryStore("store", () -> Mono.just(cp)),
            new io.agentcore.memory.MemoryScope("user-one", "agent-one", null),
            new io.agentcore.memory.MemoryScope("user-one", "agent-one", "session-one"), 3);
    }
    @Test void springAutomaticWriteBackWrapsWholeToolLoop() {
        var client = org.springframework.ai.chat.client.ChatClient.builder(AgentCoreSpringAI.model(source))
            .defaultAdvisors(new io.agentcore.springai.MemoryAdvisor(splitMemory(), true),
                org.springframework.ai.chat.client.advisor.ToolCallingAdvisor.builder().build()).build();
        client.prompt().user("Use echo").toolCallbacks(AgentCoreSpringAI.tool(tool)).stream().chatClientResponse()
            .collectList().block(Duration.ofSeconds(10));
        assertEquals(1, executed.get()); assertMemoryTurn("Use echo");
    }
    @Test void langchainAutomaticWriteBackKeepsFinalAnswerOnly() {
        var memory = new io.agentcore.langchain4j.MemoryAdapter(splitMemory(), true);
        var assistant = dev.langchain4j.service.AiServices.builder(Assistant.class)
            .streamingChatModel(AgentCoreLangChain4j.streamingModel(source)).tools(AgentCoreLangChain4j.tools(List.of(tool)))
            .contentRetriever(memory.retriever()).build();
        assertTurn(memory.stream("Use echo", () -> assistant.chat("Use echo")).collectList().block(Duration.ofSeconds(10)));
        assertMemoryTurn("Use echo");
    }
    @Test void agentscopeMiddlewareWritesOnceAfterFinalAnswer() {
        var toolkit = new io.agentscope.core.tool.Toolkit(); toolkit.registerAgentTool(AgentCoreAgentScope.tool(tool));
        try (var agent = io.agentscope.core.ReActAgent.builder().name("memory-agent").model(AgentCoreAgentScope.model(source))
                .toolkit(toolkit).middleware(new io.agentcore.agentscope.MemoryMiddleware(splitMemory(), true)).maxIters(3).build()) {
            assertTurn(AgentScopeEvents.from(agent.streamEvents("Use echo")).collectList().block(Duration.ofSeconds(10)));
        }
        assertMemoryTurn("Use echo");
        assertTrue(Json.write(requests.get(0)).contains("The user prefers green."));
    }
    private void assertMemoryTurn(String input) {
        assertEquals(2, memoryRequests.size(), () -> "Expected one recall and one write: " + memoryRequests);
        assertFalse(Json.object(memoryRequests.get(0).get("scope")).containsKey("sessionId"));
        assertEquals("session-one", Json.object(memoryRequests.get(1).get("scope")).get("sessionId"));
        assertEquals(List.of(Map.of("role", "user", "content", input), Map.of("role", "assistant", "content", "Final answer.")),
            memoryRequests.get(1).get("messages"));
    }
    interface SyncAssistant { dev.langchain4j.service.Result<String> chat(String input); }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"spring", "langchain4j", "agentscope"})
    void automaticWriteBackAlsoWorksForNonStreamingCalls(String framework) {
        var memory = splitMemory();
        if (framework.equals("spring")) {
            var client = org.springframework.ai.chat.client.ChatClient.builder(AgentCoreSpringAI.model(source))
                .defaultAdvisors(new io.agentcore.springai.MemoryAdvisor(memory, true)).build();
            assertEquals("Final answer.", client.prompt().messages(new org.springframework.ai.chat.messages.UserMessage("old question"),
                new org.springframework.ai.chat.messages.AssistantMessage("old answer")).user("current question").call().content());
        } else if (framework.equals("langchain4j")) {
            var adapter = new io.agentcore.langchain4j.MemoryAdapter(memory, true);
            var assistant = dev.langchain4j.service.AiServices.builder(SyncAssistant.class)
                .chatModel(AgentCoreLangChain4j.model(source)).contentRetriever(adapter.retriever()).build();
            assertEquals("Final answer.", adapter.call("current question", () -> assistant.chat("current question"))
                .block(Duration.ofSeconds(10)).content());
        } else {
            try (var agent = io.agentscope.core.ReActAgent.builder().name("memory-agent").model(AgentCoreAgentScope.model(source))
                    .middleware(new io.agentcore.agentscope.MemoryMiddleware(memory, true)).build()) {
                assertEquals("Final answer.", agent.call("current question").block(Duration.ofSeconds(10)).getTextContent());
            }
        }
        assertMemoryTurn("current question");
    }
    @Test void readOnlyMemoryCannotEnableWriteBack() {
        var readOnly = new io.agentcore.memory.MemoryContext(splitMemory().store(), splitMemory().readScope(), null, 3);
        assertThrows(IllegalArgumentException.class, () -> new io.agentcore.springai.MemoryAdvisor(readOnly, true));
        assertThrows(IllegalArgumentException.class, () -> new io.agentcore.langchain4j.MemoryAdapter(readOnly, true));
        assertThrows(IllegalArgumentException.class, () -> new io.agentcore.agentscope.MemoryMiddleware(readOnly, true));
        assertTrue(memoryRequests.isEmpty());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"spring", "langchain4j", "agentscope"})
    void automaticMemoryDoesNotWriteAfterFailureOrCancellation(String framework) {
        failModel.set(true);
        assertThrows(RuntimeException.class, () -> memoryInvocation(framework, splitMemory(), "failed").collectList().block(Duration.ofSeconds(10)));
        assertEquals(1, memoryRequests.size()); assertTrue(memoryRequests.get(0).containsKey("query"));
        failModel.set(false); memoryRequests.clear();
        assertNotNull(memoryInvocation(framework, splitMemory(), "cancelled").next().block(Duration.ofSeconds(10)));
        assertEquals(1, memoryRequests.size()); assertTrue(memoryRequests.get(0).containsKey("query"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"spring", "langchain4j", "agentscope"})
    void concurrentMemoryTurnsDoNotMixUserScopes(String framework) {
        var store = splitMemory().store();
        reactor.core.publisher.Flux.range(0, 3).flatMap(i -> {
            var context = new io.agentcore.memory.MemoryContext(store, new io.agentcore.memory.MemoryScope("user-" + i, "agent", null),
                new io.agentcore.memory.MemoryScope("user-" + i, "agent", "session-" + i), 3);
            return memoryInvocation(framework, context, "input-" + i).collectList();
        }, 3).collectList().block(Duration.ofSeconds(15));
        assertEquals(6, memoryRequests.size());
        var writes = memoryRequests.stream().filter(request -> request.containsKey("messages")).toList();
        assertEquals(3, writes.size());
        for (var write : writes) {
            String user = (String) Json.object(write.get("scope")).get("userId");
            String index = user.substring("user-".length());
            assertEquals("session-" + index, Json.object(write.get("scope")).get("sessionId"));
            assertEquals("input-" + index, Json.object(((List<?>) write.get("messages")).get(0)).get("content"));
        }
    }
    private reactor.core.publisher.Flux<?> memoryInvocation(String framework, io.agentcore.memory.MemoryContext memory, String input) {
        if (framework.equals("spring")) return org.springframework.ai.chat.client.ChatClient.builder(AgentCoreSpringAI.model(source))
            .defaultAdvisors(new io.agentcore.springai.MemoryAdvisor(memory, true)).build().prompt().user(input).stream().chatClientResponse();
        if (framework.equals("langchain4j")) {
            var adapter = new io.agentcore.langchain4j.MemoryAdapter(memory, true);
            var assistant = dev.langchain4j.service.AiServices.builder(Assistant.class)
                .streamingChatModel(AgentCoreLangChain4j.streamingModel(source)).contentRetriever(adapter.retriever()).build();
            return adapter.stream(input, () -> assistant.chat(input));
        }
        return reactor.core.publisher.Flux.using(() -> io.agentscope.core.ReActAgent.builder().name("memory-agent")
                .model(AgentCoreAgentScope.model(source)).middleware(new io.agentcore.agentscope.MemoryMiddleware(memory, true)).build(),
            agent -> agent.streamEvents(input), io.agentscope.core.ReActAgent::close);
    }
    private void assertTurn(List<AgentEvent> events) {
        assertNotNull(events);
        var types = events.stream().map(AgentEvent::type).toList();
        assertTrue(types.contains(AgentEvent.Type.TOOL_START), () -> "Missing tool call: " + events);
        assertTrue(types.contains(AgentEvent.Type.TOOL_RESULT), () -> "Missing tool result: " + events);
        assertTrue(types.lastIndexOf(AgentEvent.Type.TEXT_DELTA) > types.indexOf(AgentEvent.Type.TOOL_RESULT), () -> "Missing final answer: " + events);
        assertTrue(events.stream().filter(e -> e.type() == AgentEvent.Type.TEXT_START).map(e -> e.data().get("messageId")).distinct().count() >= 2, () -> "Reused message IDs: " + events);
        for (var event : events) if (event.type() == AgentEvent.Type.TOOL_START || event.type() == AgentEvent.Type.TOOL_RESULT)
            assertEquals("call_one", event.data().get("toolCallId"));
    }
}
