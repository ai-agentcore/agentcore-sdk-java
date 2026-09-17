package io.agentcore.model;

import io.agentcore.HttpTransport;
import io.agentcore.Json;
import io.agentcore.controlplane.ControlPlane;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Native protocol payloads are preserved; no synthetic stream or model capability fallback. */
public final class ModelClient {
    public enum Protocol { OPENAI, ANTHROPIC }
    public record Descriptor(String connectionId, String connectionName, String protocol, String providerType,
                             String modelId, String modelName, Integer contextSize, Integer maxTokens,
                             Map<String, Boolean> capabilities) {
        public Descriptor { capabilities = Map.copyOf(capabilities); }
    }
    public record Settings(String model, URI baseUrl, Protocol protocol, Integer maxTokens, boolean managed) {}
    private final Settings settings;
    private final Supplier<Mono<Map<String, String>>> headers;
    private final HttpTransport http;
    private final Descriptor descriptor;
    private final Duration timeout = Duration.ofMinutes(10);

    public ModelClient(Settings settings, Supplier<Mono<Map<String, String>>> headers, HttpTransport http) {
        this(settings, headers, http, null);
    }
    public ModelClient(Settings settings, Supplier<Mono<Map<String, String>>> headers, HttpTransport http, Descriptor descriptor) {
        this.settings = settings; this.headers = headers; this.http = http; this.descriptor = descriptor;
    }
    public Settings settings() { return settings; }
    /** Platform resource metadata; direct clients have no platform descriptor. */
    public Descriptor descriptor() { return descriptor; }
    public Mono<Map<String, String>> headers() { return Mono.defer(headers).map(io.agentcore.Headers::merge); }
    public Mono<Map<String, Object>> completion(List<Map<String, Object>> messages) { return completion(messages, Map.of()); }
    public Mono<Map<String, Object>> completion(List<Map<String, Object>> messages, Map<String, Object> parameters) {
        return Mono.defer(() -> http.json("model.completion", "POST", url(chatPath()), this::protocolHeaders,
            body(messages, parameters, false), timeout));
    }
    public Flux<Map<String, Object>> stream(List<Map<String, Object>> messages, Map<String, Object> parameters) {
        return Flux.defer(() -> http.sse("model.stream", url(chatPath()), this::protocolHeaders,
            body(messages, parameters, true), timeout));
    }
    public Flux<Map<String, Object>> stream(List<Map<String, Object>> messages) { return stream(messages, Map.of()); }
    public Mono<Map<String, Object>> embedding(Object input, Map<String, Object> parameters) {
        return Mono.defer(() -> {
            if (settings.managed || settings.protocol != Protocol.OPENAI)
                return Mono.error(new IllegalArgumentException("Embedding requires a direct OpenAI-compatible embedding model"));
            var body = parameters(parameters, List.of("model", "input")); body.put("model", settings.model); body.put("input", input);
            return http.json("model.embedding", "POST", url("/embeddings"), this::protocolHeaders, body, timeout);
        });
    }
    public Mono<Map<String, Object>> responses(Object input, Map<String, Object> parameters) {
        return Mono.defer(() -> http.json("model.responses", "POST", url("/responses"), this::protocolHeaders,
            inputBody(input, parameters, false), timeout));
    }
    public Flux<Map<String, Object>> responsesStream(Object input, Map<String, Object> parameters) {
        return Flux.defer(() -> http.sse("model.responses", url("/responses"), this::protocolHeaders,
            inputBody(input, parameters, true), timeout));
    }
    private Map<String, Object> inputBody(Object input, Map<String, Object> parameters, boolean stream) {
        if (settings.protocol != Protocol.OPENAI) throw new IllegalArgumentException("Responses API requires OpenAI protocol");
        var body = parameters(parameters, List.of("model", "input", "stream"));
        body.put("model", settings.model); body.put("input", input); body.put("stream", stream);
        return body;
    }
    private Map<String, Object> body(List<Map<String, Object>> messages, Map<String, Object> parameters, boolean stream) {
        var body = parameters(parameters, List.of("model", "messages", "stream"));
        body.put("model", settings.model); body.put("stream", stream);
        if (settings.protocol == Protocol.OPENAI) body.put("messages", messages);
        else {
            var conversation = new ArrayList<Map<String, Object>>();
            var system = new ArrayList<String>();
            for (var message : messages) {
                if ("system".equals(message.get("role"))) system.add(Json.text(message.get("content"), "system message"));
                else conversation.add(message);
            }
            if (!system.isEmpty()) {
                if (body.containsKey("system")) throw new IllegalArgumentException("system specified twice");
                body.put("system", String.join("\n\n", system));
            }
            body.put("messages", conversation);
            if (!body.containsKey("max_tokens") && settings.maxTokens != null) body.put("max_tokens", settings.maxTokens);
            if (!body.containsKey("max_tokens")) throw new IllegalArgumentException("Anthropic requires max_tokens");
        }
        return body;
    }
    private static LinkedHashMap<String, Object> parameters(Map<String, Object> input, List<String> reserved) {
        for (String key : reserved) if (input.containsKey(key)) throw new IllegalArgumentException("Reserved model parameter: " + key);
        return new LinkedHashMap<>(input);
    }
    private Mono<Map<String, String>> protocolHeaders() {
        return headers().map(values -> {
            var result = new LinkedHashMap<>(values);
            if (settings.protocol == Protocol.ANTHROPIC) result.putIfAbsent("anthropic-version", "2023-06-01");
            return result;
        });
    }
    private String chatPath() { return settings.protocol == Protocol.OPENAI ? "/chat/completions" : "/v1/messages"; }
    private URI url(String path) { return URI.create(settings.baseUrl.toString().replaceAll("/+$", "") + path); }
    public static URI managedUrl(URI gateway, String connectionId, Protocol protocol) {
        String base = gateway.toString().replaceAll("/+$", "").replaceAll("/v1$", "").replaceAll("/model-connection$", "");
        return URI.create(base + "/model-connection/" + ControlPlane.encode(connectionId) + (protocol == Protocol.OPENAI ? "/v1" : ""));
    }
    @Override public String toString() { return "ModelClient(model=" + settings.model + ", protocol=" + settings.protocol + ")"; }
}
