package io.agentcore.langchain4j;

import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.TokenStream;
import io.agentcore.event.AgentEvent;
import io.agentcore.memory.MemoryContext;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Registers recall with AiServices; wraps the whole invocation, not each model/tool iteration. */
public final class MemoryAdapter {
    private final MemoryContext memory;
    private final boolean writeBack;
    public MemoryAdapter(MemoryContext memory) { this(memory, false); }
    public MemoryAdapter(MemoryContext memory, boolean writeBack) {
        this.memory = memory; this.writeBack = writeBack;
        if (writeBack) memory.requireWriteScope();
    }
    public MemoryRetriever retriever() { return new MemoryRetriever(memory); }
    public Mono<Result<String>> call(String input, Supplier<Result<String>> invocation) {
        return Mono.fromSupplier(invocation).subscribeOn(Schedulers.boundedElastic())
            .flatMap(result -> record(input, result.content(), result.finishReason()).thenReturn(result));
    }
    public Flux<AgentEvent> stream(String input, Supplier<TokenStream> invocation) {
        return LangChain4jEvents.from(invocation, result -> result.aiMessage().hasToolExecutionRequests() ? Mono.empty()
            : record(input, result.aiMessage().text(), result.finishReason()));
    }
    private Mono<Void> record(String input, String answer, FinishReason reason) {
        return writeBack && reason == FinishReason.STOP ? memory.recordTurn(input, answer) : Mono.empty();
    }
}
