package io.agentcore.verification;

import io.agentcore.Json;
import io.agentcore.event.AgentEvent;
import io.agentcore.tool.Tool;
import io.agentcore.langchain4j.*;
import java.util.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;

class MultiRoundEventsTest {
    interface Assistant { dev.langchain4j.service.TokenStream chat(String input); }
    @ParameterizedTest @ValueSource(strings = {"langchain4j", "spring", "agentscope"})
    void toolOnlyRoundOwnsANewMessageAndSameRoundToolsShareParent(String framework) throws Exception {
        var fixture = new ToolContextTest(); fixture.setup();
        var round = new AtomicInteger();
        fixture.server.removeContext("/v1/chat/completions");
        fixture.server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes(); int n = round.incrementAndGet();
            var delta = new LinkedHashMap<String, Object>(); delta.put("role", "assistant");
            if (n == 1) delta.put("content", "Checking.");
            if (n <= 2) delta.put("tool_calls", n == 1 ? List.of(call(0, "call-1"), call(1, "call-2")) : List.of(call(0, "call-3")));
            else delta.put("content", "Done.");
            byte[] bytes = (chunk(n, delta, null) + chunk(n, Map.of(), n <= 2 ? "tool_calls" : "stop") + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        try {
            var tool = new Tool("echo", "Echo", Map.of("type", "object", "properties", Map.of()), args -> Mono.just("Result"));
            List<AgentEvent> events;
            if (framework.equals("langchain4j")) {
                var agent = dev.langchain4j.service.AiServices.builder(Assistant.class).streamingChatModel(AgentCoreLangChain4j.streamingModel(fixture.model))
                    .tools(AgentCoreLangChain4j.tools(List.of(tool))).build();
                events = LangChain4jEvents.from(() -> agent.chat("Check twice")).collectList().block(Duration.ofSeconds(15));
            } else if (framework.equals("spring")) {
                var model = io.agentcore.springai.AgentCoreSpringAI.model(fixture.model);
                var options = org.springframework.ai.model.tool.ToolCallingChatOptions.builder().toolCallbacks(io.agentcore.springai.AgentCoreSpringAI.tool(tool)).build();
                events = io.agentcore.springai.SpringAIEvents.stream(model, new org.springframework.ai.chat.prompt.Prompt("Check twice", options))
                    .collectList().block(Duration.ofSeconds(15));
            } else {
                var toolkit = new io.agentscope.core.tool.Toolkit(); toolkit.registerAgentTool(io.agentcore.agentscope.AgentCoreAgentScope.tool(tool));
                try (var agent = io.agentscope.core.ReActAgent.builder().name("probe").model(io.agentcore.agentscope.AgentCoreAgentScope.model(fixture.model)).toolkit(toolkit).maxIters(4).build()) {
                    events = io.agentcore.agentscope.AgentScopeEvents.from(agent.streamEvents("Check twice")).collectList().block(Duration.ofSeconds(15));
                }
            }
            var starts = events.stream().filter(e -> e.type() == AgentEvent.Type.TOOL_START).collect(java.util.stream.Collectors.toMap(e -> e.data().get("toolCallId"), e -> e));
            assertEquals(Set.of("call-1", "call-2", "call-3"), starts.keySet());
            assertEquals(starts.get("call-1").data().get("parentMessageId"), starts.get("call-2").data().get("parentMessageId"));
            assertNotEquals(starts.get("call-1").data().get("parentMessageId"), starts.get("call-3").data().get("parentMessageId"));
            for (var id : starts.keySet()) {
                var result = events.stream().filter(e -> e.type() == AgentEvent.Type.TOOL_RESULT && id.equals(e.data().get("toolCallId"))).findFirst().orElseThrow();
                assertTrue(events.indexOf(starts.get(id)) < events.indexOf(result));
                Object parent = starts.get(id).data().get("parentMessageId"); assertNotNull(parent);
                // AgentScope's tool-only reply has its own reply ID, without synthetic empty text events.
                if (framework.equals("agentscope") && id.equals("call-3")) continue;
                var begin = events.stream().filter(e -> e.type() == AgentEvent.Type.TEXT_START && parent.equals(e.data().get("messageId"))).findFirst().orElseThrow();
                var end = events.stream().filter(e -> e.type() == AgentEvent.Type.TEXT_END && parent.equals(e.data().get("messageId"))).findFirst().orElseThrow();
                assertTrue(events.indexOf(begin) < events.indexOf(end)); assertTrue(events.indexOf(end) < events.indexOf(starts.get(id)));
            }
            var textStarts = events.stream().filter(e -> e.type() == AgentEvent.Type.TEXT_START).toList();
            assertEquals(framework.equals("agentscope") ? 2 : 3, textStarts.size());
            assertEquals(textStarts.size(), events.stream().filter(e -> e.type() == AgentEvent.Type.TEXT_END).count());
            Object finalId = textStarts.get(textStarts.size() - 1).data().get("messageId");
            for (var start : starts.values()) assertNotEquals(finalId, start.data().get("parentMessageId"));
            var request = new io.agentcore.server.AgentRequest("agui", "r", "s", "run", List.of(), Map.of(), Map.of());
            var agui = io.agentcore.server.Protocols.agui(request, Flux.fromIterable(events)).collectList().block();
            assertEquals(3, agui.stream().filter(e -> e.contains("TOOL_CALL_RESULT")).count());
            assertTrue(agui.get(agui.size() - 1).contains("RUN_FINISHED"));
            var openai = String.join("", io.agentcore.server.Protocols.openai(request, Flux.fromIterable(events)).collectList().block());
            for (var id : starts.keySet()) assertTrue(openai.contains((String) id));
            assertFalse(openai.contains("TOOL_CALL_RESULT")); assertTrue(openai.endsWith("data: [DONE]\n\n"));
        } finally { fixture.close(); }
    }
    private static Map<String, Object> call(int index, String id) {
        return Map.of("index", index, "id", id, "type", "function", "function", Map.of("name", "echo", "arguments", "{}"));
    }
    private static String chunk(int n, Map<String, Object> delta, String finish) {
        var choice = new LinkedHashMap<String, Object>(); choice.put("index", 0); choice.put("delta", delta);
        if (finish != null) choice.put("finish_reason", finish);
        return "data: " + Json.write(Map.of("id", "reply-" + n, "object", "chat.completion.chunk", "created", 1, "model", "test", "choices", List.of(choice))) + "\n\n";
    }
}
