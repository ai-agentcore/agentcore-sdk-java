package io.agentcore.tool;

import io.agentcore.Json;
import java.util.Map;
import java.util.function.Function;
import java.util.function.BiFunction;
import reactor.core.publisher.Mono;

public record Tool(String name, String description, Map<String, Object> parameters,
                   BiFunction<Map<String, Object>, Map<String, Object>, Mono<Object>> invoke) {
    public Tool { Json.text(name, "tool name"); parameters = Map.copyOf(parameters); }
    public Tool(String name, String description, Map<String, Object> parameters,
                Function<Map<String, Object>, Mono<Object>> invoke) {
        this(name, description, parameters, (arguments, context) -> invoke.apply(arguments));
    }
    public Mono<Object> call(Map<String, Object> arguments) { return call(arguments, Map.of()); }
    /** Application-provided execution metadata, separate from model-visible arguments. */
    public Mono<Object> call(Map<String, Object> arguments, Map<String, Object> context) {
        return Mono.defer(() -> invoke.apply(arguments, context));
    }
}
