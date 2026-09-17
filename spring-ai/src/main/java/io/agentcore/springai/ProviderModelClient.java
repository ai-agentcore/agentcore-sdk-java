package io.agentcore.springai;

import io.agentcore.event.AgentEvent;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Provider configuration, authentication and capability support remain with Spring AI. */
public final class ProviderModelClient {
    private final ChatModel model;
    private final EmbeddingModel embedding;
    public ProviderModelClient(ChatModel model) { this(model, null); }
    public ProviderModelClient(ChatModel model, EmbeddingModel embedding) {
        this.model = java.util.Objects.requireNonNull(model); this.embedding = embedding;
    }
    public ChatModel nativeModel() { return model; }
    public Mono<ChatResponse> completion(Prompt prompt) {
        return Mono.fromCallable(() -> model.call(prompt)).subscribeOn(Schedulers.boundedElastic());
    }
    public Flux<ChatResponse> stream(Prompt prompt) { return Flux.defer(() -> model.stream(prompt)); }
    public Flux<AgentEvent> events(Prompt prompt) { return SpringAIEvents.stream(model, prompt); }
    public Mono<EmbeddingResponse> embedding(EmbeddingRequest request) {
        if (embedding == null) return Mono.error(new IllegalStateException("Configure a Spring AI EmbeddingModel"));
        return Mono.fromCallable(() -> embedding.call(request)).subscribeOn(Schedulers.boundedElastic());
    }
}
