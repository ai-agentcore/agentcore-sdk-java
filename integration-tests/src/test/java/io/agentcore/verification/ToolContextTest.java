package io.agentcore.verification;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import io.agentcore.AgentCore;
import io.agentcore.Json;
import io.agentcore.agentscope.AgentCoreAgentScope;
import io.agentcore.collaboration.CollaborationContext;
import io.agentcore.langchain4j.AgentCoreLangChain4j;
import io.agentcore.langchain4j.LangChain4jEvents;
import io.agentcore.model.ModelClient;
import io.agentcore.springai.AgentCoreSpringAI;
import io.agentcore.tool.Tool;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import static org.junit.jupiter.api.Assertions.*;

/** Real framework tool loops against a local OpenAI HTTP/SSE fixture, not a cloud model. */
class ToolContextTest {
    interface SyncAssistant { String chat(@dev.langchain4j.service.UserMessage String message, InvocationParameters parameters); }
    interface StreamingAssistant { TokenStream chat(@dev.langchain4j.service.UserMessage String message, InvocationParameters parameters); }
    HttpServer server;
    AgentCore core;
    ModelClient model;
    Tool tool;
    Map<String, String> seen = new ConcurrentHashMap<>();
    List<String> modelRequests = new CopyOnWriteArrayList<>();

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            modelRequests.add(raw);
            var request = Json.read(raw);
            var messages = (List<?>) request.get("messages");
            String input = ""; boolean result = false;
            for (var item : messages) {
                var message = Json.object(item);
                if ("user".equals(message.get("role"))) { input = String.valueOf(message.get("content")); result = false; }
                if ("tool".equals(message.get("role"))) result = true;
            }
            var definition = Json.object(Json.object(((List<?>) request.get("tools")).get(0)).get("function"));
            String name = (String) definition.get("name");
            String arguments = name.equals("probe") ? Json.write(Map.of("request_id", input)) : "{}";
            var function = Map.of("name", name, "arguments", arguments);
            boolean stream = Boolean.TRUE.equals(request.get("stream"));
            Map<String, Object> message = result ? Map.of("role", "assistant", "content", "Done.")
                : Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", "call-probe", "type", "function", "function", function)));
            String body;
            if (stream) {
                var delta = result ? Map.of("role", "assistant", "content", "Done.")
                    : Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0, "id", "call-probe", "type", "function", "function", function)));
                body = "data: " + Json.write(Map.of("id", "reply", "object", "chat.completion.chunk", "created", 1, "model", "test",
                    "choices", List.of(Map.of("index", 0, "delta", delta)))) + "\n\n";
                body += "data: " + Json.write(Map.of("id", "reply", "object", "chat.completion.chunk", "created", 1, "model", "test",
                    "choices", List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", result ? "stop" : "tool_calls")))) + "\n\ndata: [DONE]\n\n";
            } else body = Json.write(Map.of("id", "reply", "object", "chat.completion", "created", 1, "model", "test",
                "choices", List.of(Map.of("index", 0, "message", message, "finish_reason", result ? "stop" : "tool_calls"))));
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start(); core = AgentCore.auto();
        model = core.directModel("test", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
            ModelClient.Protocol.OPENAI, () -> Mono.just(Map.of()));
        tool = new Tool("probe", "Probe request context", Map.of("type", "object", "properties",
            Map.of("request_id", Map.of("type", "string")), "required", List.of("request_id")), (args, metadata) ->
            Mono.delay(Duration.ofMillis(40)).map(ignored -> {
                var context = (CollaborationContext) metadata.get(CollaborationContext.KEY);
                seen.put((String) args.get("request_id"), context == null ? "absent" : context.eventId());
                return (Object) "Tool done.";
            }));
    }
    @AfterEach void close() { core.close(); server.stop(0); }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void springContinuesAfterExpectedCollaborationFailure(boolean streaming) throws Exception {
        var teams = new io.agentcore.collaboration.TeamsProvider(() -> Mono.just("""
            apiVersion: agentteams.io/v1alpha1
            kind: TeamsConfig
            metadata: {runtimeName: test}
            spec:
              self: {name: test, runtimeName: test}
              teams: []
            """));
        try (var collaboration = new io.agentcore.collaboration.Collaboration(teams,
                new io.agentcore.collaboration.TaskServiceClient(() -> Mono.error(new AssertionError("No Task Service call expected"))))) {
            var query = collaboration.tools().block().stream().filter(t -> t.name().equals("agentteams_list_tasks")).findFirst().orElseThrow();
            var client = org.springframework.ai.chat.client.ChatClient.builder(AgentCoreSpringAI.model(model))
                .defaultTools(AgentCoreSpringAI.tool(query)).build();
            var request = client.prompt().user("What tasks are assigned to me?");
            String answer = streaming ? request.stream().content().reduce("", String::concat).block(Duration.ofSeconds(10)) : request.call().content();
            assertEquals("Done.", answer); assertEquals(2, modelRequests.size());
            var messages = (List<?>) Json.read(modelRequests.get(1)).get("messages");
            var result = messages.stream().map(Json::object).filter(m -> "tool".equals(m.get("role"))).findFirst().orElseThrow();
            var failure = Json.read((String) result.get("content"));
            assertEquals(false, failure.get("ok")); assertEquals("COLLABORATION_TEAM_UNAVAILABLE", failure.get("code"));
            assertEquals(false, failure.get("retryable"));
        }
    }

    @ParameterizedTest
    @CsvSource({"spring,false", "spring,true", "langchain4j,false", "langchain4j,true", "agentscope,false", "agentscope,true"})
    void sharedToolsReceivePerInvocationContextInNativeLoops(String framework, boolean streaming) {
        var spring = org.springframework.ai.chat.client.ChatClient.builder(AgentCoreSpringAI.model(model))
            .defaultTools(AgentCoreSpringAI.tool(tool)).build();
        var executors = AgentCoreLangChain4j.tools(List.of(tool));
        var sync = AiServices.builder(SyncAssistant.class).chatModel(AgentCoreLangChain4j.model(model)).tools(executors).build();
        var stream = AiServices.builder(StreamingAssistant.class).streamingChatModel(AgentCoreLangChain4j.streamingModel(model)).tools(executors).build();
        var toolkit = new io.agentscope.core.tool.Toolkit(); toolkit.registerAgentTool(AgentCoreAgentScope.tool(tool));
        try (var agent = io.agentscope.core.ReActAgent.builder().name("test").model(AgentCoreAgentScope.model(model)).toolkit(toolkit).maxIters(3).build()) {
            java.util.function.BiFunction<String, Map<String, Object>, Mono<?>> invoke = (input, metadata) -> {
                if (framework.equals("spring")) {
                    var request = spring.prompt().user(input).toolContext(metadata);
                    return streaming ? request.stream().content().collectList() : Mono.fromCallable(() -> request.call().content());
                }
                if (framework.equals("langchain4j")) {
                    var parameters = InvocationParameters.from(metadata);
                    return streaming ? LangChain4jEvents.from(() -> stream.chat(input, parameters)).collectList()
                        : Mono.fromCallable(() -> sync.chat(input, parameters));
                }
                var context = io.agentscope.core.agent.RuntimeContext.builder().sessionId(input).putAll(metadata).build();
                return streaming ? agent.streamEvents(input, context).collectList() : agent.call(input, context);
            };
            Flux.just("one", "two").flatMap(id -> invoke.apply(id, Map.of(CollaborationContext.KEY,
                new CollaborationContext("session-" + id, "team", "room-" + id, "private-event-" + id, "task")))
                .subscribeOn(Schedulers.boundedElastic()), 2).collectList().block(Duration.ofSeconds(20));
            invoke.apply("three", Map.of()).subscribeOn(Schedulers.boundedElastic()).block(Duration.ofSeconds(20));
            assertEquals(Map.of("one", "private-event-one", "two", "private-event-two", "three", "absent"), seen);
            assertTrue(modelRequests.stream().noneMatch(body -> body.contains("private-event-") || body.contains(CollaborationContext.KEY)));
        }
    }
}
