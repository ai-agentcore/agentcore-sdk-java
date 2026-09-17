package io.agentcore.server;

import io.agentcore.Json;
import io.agentcore.event.AgentEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

public final class Protocols {
    private Protocols() {}
    public static Flux<String> agui(AgentRequest request, Flux<AgentEvent> execution) {
        return Flux.concat(Flux.just(sse(Map.of("type", "RUN_STARTED", "threadId", request.sessionId(), "runId", request.runId()))),
            execution.map(event -> {
                var data = new LinkedHashMap<String, Object>(event.data());
                data.put("type", switch (event.type()) {
                    case TEXT_START -> "TEXT_MESSAGE_START";
                    case TEXT_DELTA -> "TEXT_MESSAGE_CONTENT";
                    case TEXT_END -> "TEXT_MESSAGE_END";
                    case TOOL_START -> "TOOL_CALL_START";
                    case TOOL_ARGS -> "TOOL_CALL_ARGS";
                    case TOOL_END -> "TOOL_CALL_END";
                    case TOOL_RESULT -> "TOOL_CALL_RESULT";
                });
                if (event.type() == AgentEvent.Type.TEXT_START) data.put("role", "assistant");
                if (event.type() == AgentEvent.Type.TOOL_RESULT) data.put("role", "tool");
                return sse(data);
            }), Flux.just(sse(Map.of("type", "RUN_FINISHED", "threadId", request.sessionId(), "runId", request.runId()))))
            .onErrorResume(error -> Flux.just(sse(Map.of("type", "RUN_ERROR", "code", "INTERNAL_ERROR", "message", "Agent execution failed"))));
    }
    /** Chat Completions cannot encode intermediate tool results or multiple assistant message boundaries. */
    public static Flux<String> openai(AgentRequest request, Flux<AgentEvent> execution) {
        return Flux.defer(() -> {
            Map<String, Integer> tools = new LinkedHashMap<>();
            var chunks = execution.<String>handle((event, sink) -> {
                Map<String, Object> delta;
                switch (event.type()) {
                    case TEXT_DELTA -> delta = Map.of("content", event.data().get("delta"));
                    case TOOL_START -> {
                        String id = (String) event.data().get("toolCallId"); tools.put(id, tools.size());
                        delta = Map.of("tool_calls", List.of(Map.of("index", tools.get(id), "id", id, "type", "function",
                            "function", Map.of("name", event.data().get("toolCallName"), "arguments", ""))));
                    }
                    case TOOL_ARGS -> delta = Map.of("tool_calls", List.of(Map.of("index", tools.get((String) event.data().get("toolCallId")),
                        "function", Map.of("arguments", event.data().get("delta")))));
                    default -> { return; }
                }
                sink.next(chunk(request, delta, null));
            });
            return Flux.concat(Flux.just(chunk(request, Map.of("role", "assistant"), null)), chunks,
                Flux.defer(() -> Flux.just(chunk(request, Map.of(), tools.isEmpty() ? "stop" : "tool_calls"), "data: [DONE]\n\n")))
                .onErrorResume(error -> Flux.just(sse(Map.of("error", Map.of("message", "Agent execution failed", "type", "server_error"))), "data: [DONE]\n\n"));
        });
    }
    public static Map<String, Object> completion(AgentRequest request, List<AgentEvent> events) {
        var content = new StringBuilder(); var calls = new LinkedHashMap<String, Map<String, Object>>();
        for (var event : events) switch (event.type()) {
            case TEXT_DELTA -> content.append(event.data().get("delta"));
            case TOOL_START -> calls.put((String) event.data().get("toolCallId"), new LinkedHashMap<>(Map.of(
                "name", event.data().get("toolCallName"), "arguments", "")));
            case TOOL_ARGS -> {
                var call = calls.get((String) event.data().get("toolCallId")); call.put("arguments", call.get("arguments") + (String) event.data().get("delta"));
            }
            default -> { }
        }
        var message = new LinkedHashMap<String, Object>(); message.put("role", "assistant"); message.put("content", content.toString());
        if (!calls.isEmpty()) message.put("tool_calls", calls.entrySet().stream().map(e -> Map.of("id", e.getKey(), "type", "function", "function", e.getValue())).toList());
        return Map.of("id", request.runId(), "object", "chat.completion", "created", System.currentTimeMillis() / 1000,
            "model", request.body().getOrDefault("model", "agent"), "choices", List.of(Map.of("index", 0, "message", message,
                "finish_reason", calls.isEmpty() ? "stop" : "tool_calls")));
    }
    public static Flux<String> heartbeat(Flux<String> source, Duration idle) {
        return Flux.create(sink -> {
            var resources = Disposables.composite(); sink.onDispose(resources);
            var scheduler = Schedulers.parallel(); var unit = java.util.concurrent.TimeUnit.NANOSECONDS;
            AtomicLong last = new AtomicLong(scheduler.now(unit));
            resources.add(scheduler.schedulePeriodically(() -> {
                if (scheduler.now(unit) - last.get() >= idle.toNanos()) { sink.next(": ping\n\n"); last.set(scheduler.now(unit)); }
            }, idle.toMillis(), idle.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS));
            resources.add(source.subscribe(value -> { last.set(scheduler.now(unit)); sink.next(value); }, sink::error, sink::complete));
        });
    }
    private static String chunk(AgentRequest request, Map<String, Object> delta, String finish) {
        var choice = new LinkedHashMap<String, Object>(); choice.put("index", 0); choice.put("delta", delta); choice.put("finish_reason", finish);
        return sse(Map.of("id", request.runId(), "object", "chat.completion.chunk", "created", System.currentTimeMillis() / 1000,
            "model", request.body().getOrDefault("model", "agent"), "choices", List.of(choice)));
    }
    private static String sse(Object value) { return "data: " + Json.write(value) + "\n\n"; }
}
