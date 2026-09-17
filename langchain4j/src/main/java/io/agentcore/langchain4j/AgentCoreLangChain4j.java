package io.agentcore.langchain4j;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.service.tool.ToolExecutor;
import io.agentcore.Headers;
import io.agentcore.Json;
import io.agentcore.model.ModelClient;
import io.agentcore.tool.Tool;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AgentCoreLangChain4j {
    private AgentCoreLangChain4j() {}
    public static ChatModel model(ModelClient source) {
        var settings = source.settings();
        if (settings.protocol() == ModelClient.Protocol.OPENAI) return OpenAiChatModel.builder().modelName(settings.model())
            .baseUrl(settings.baseUrl().toString()).apiKey("agentcore").httpClientBuilder(new ModelHttpClientBuilder(source)).timeout(Duration.ofMinutes(10)).maxRetries(0).build();
        return AnthropicChatModel.builder().modelName(settings.model()).baseUrl(settings.baseUrl().toString().replaceAll("/+$", "") + "/v1").apiKey("agentcore")
            .httpClientBuilder(new ModelHttpClientBuilder(source)).maxTokens(settings.maxTokens() == null ? 4096 : settings.maxTokens())
            .timeout(Duration.ofMinutes(10)).maxRetries(0).build();
    }
    public static StreamingChatModel streamingModel(ModelClient source) {
        var settings = source.settings();
        if (settings.protocol() == ModelClient.Protocol.OPENAI) return OpenAiStreamingChatModel.builder().modelName(settings.model())
            .baseUrl(settings.baseUrl().toString()).apiKey("agentcore").httpClientBuilder(new ModelHttpClientBuilder(source)).timeout(Duration.ofMinutes(10)).build();
        return AnthropicStreamingChatModel.builder().modelName(settings.model()).baseUrl(settings.baseUrl().toString().replaceAll("/+$", "") + "/v1").apiKey("agentcore")
            .httpClientBuilder(new ModelHttpClientBuilder(source)).maxTokens(settings.maxTokens() == null ? 4096 : settings.maxTokens()).timeout(Duration.ofMinutes(10)).build();
    }
    public static Map<ToolSpecification, ToolExecutor> tools(List<Tool> sources) {
        var result = new LinkedHashMap<ToolSpecification, ToolExecutor>();
        for (var tool : sources) {
            var input = tool.parameters(); var schema = JsonObjectSchema.builder();
            if (input.containsKey("definitions")) input = normalizeDefinitions(input);
            Json.object(input.getOrDefault("properties", Map.of())).forEach((name, property) -> schema.addProperty(name, JsonRawSchema.from(Json.write(property))));
            if (input.get("required") instanceof List<?> required) schema.required(required.stream().map(String.class::cast).toList());
            if (input.get("additionalProperties") instanceof Boolean additional) schema.additionalProperties(additional);
            var definitions = new LinkedHashMap<String, JsonSchemaElement>();
            Json.object(input.getOrDefault("$defs", Map.of())).forEach((name, definition) ->
                definitions.put(name, JsonRawSchema.from(Json.write(definition))));
            schema.definitions(definitions);
            result.put(ToolSpecification.builder().name(tool.name()).description(tool.description()).parameters(schema.build()).build(),
                new ToolExecutor() {
                    @Override public String execute(dev.langchain4j.agent.tool.ToolExecutionRequest request, Object memoryId) {
                        Object value = tool.call(Json.read(request.arguments())).block();
                        return value instanceof String text ? text : Json.write(value);
                    }
                    @Override public dev.langchain4j.service.tool.ToolExecutionResult executeWithContext(
                            dev.langchain4j.agent.tool.ToolExecutionRequest request,
                            dev.langchain4j.invocation.InvocationContext context) {
                        var parameters = context.invocationParameters();
                        Object value = tool.call(Json.read(request.arguments()), parameters == null ? Map.of() : parameters.asMap()).block();
                        return dev.langchain4j.service.tool.ToolExecutionResult.builder().result(value)
                            .resultText(value instanceof String text ? text : Json.write(value)).build();
                    }
                });
        }
        return Map.copyOf(result);
    }

    /** LangChain4j serializes root definitions as $defs; retain references without expanding them. */
    private static Map<String, Object> normalizeDefinitions(Map<String, Object> input) {
        var result = new LinkedHashMap<>(input);
        var definitions = new LinkedHashMap<>(Json.object(input.getOrDefault("$defs", Map.of())));
        Json.object(result.remove("definitions")).forEach((name, definition) -> {
            if (definitions.containsKey(name) && !definitions.get(name).equals(definition))
                throw new IllegalArgumentException("Conflicting tool schema definitions: " + name);
            definitions.put(name, definition);
        });
        result.put("$defs", definitions);
        return rewriteDefinitionReferences(result);
    }

    private static Map<String, Object> rewriteDefinitionReferences(Map<String, Object> schema) {
        var result = new LinkedHashMap<>(schema);
        if (schema.get("$ref") instanceof String ref && ref.startsWith("#/definitions/"))
            result.put("$ref", "#/$defs/" + ref.substring("#/definitions/".length()));
        // Traverse schema positions only: defaults, enum values and examples are application data.
        for (String key : List.of("properties", "patternProperties", "$defs", "definitions", "dependentSchemas"))
            if (schema.get(key) instanceof Map<?, ?> values) {
                var converted = new LinkedHashMap<String, Object>();
                Json.object(values).forEach((name, value) -> converted.put(name,
                    value instanceof Map<?, ?> ? rewriteDefinitionReferences(Json.object(value)) : value));
                result.put(key, converted);
            }
        for (String key : List.of("items", "additionalProperties", "contains", "not", "if", "then", "else",
                "propertyNames", "unevaluatedProperties", "unevaluatedItems", "allOf", "anyOf", "oneOf", "prefixItems")) {
            Object value = schema.get(key);
            if (value instanceof Map<?, ?>) result.put(key, rewriteDefinitionReferences(Json.object(value)));
            else if (value instanceof List<?> list) result.put(key, list.stream().map(item ->
                item instanceof Map<?, ?> ? rewriteDefinitionReferences(Json.object(item)) : item).toList());
        }
        return result;
    }
}
