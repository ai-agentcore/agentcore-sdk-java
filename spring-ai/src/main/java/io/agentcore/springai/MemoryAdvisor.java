package io.agentcore.springai;

import io.agentcore.memory.MemoryContext;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.ArrayList;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Encloses the tool loop; each subscription retains only its own current turn. */
public final class MemoryAdvisor implements CallAdvisor, StreamAdvisor {
    private final MemoryContext memory;
    private final boolean writeBack;
    public MemoryAdvisor(MemoryContext memory) { this(memory, false); }
    public MemoryAdvisor(MemoryContext memory, boolean writeBack) {
        this.memory = memory; this.writeBack = writeBack;
        if (writeBack) memory.requireWriteScope();
    }
    @Override public String getName() { return "AgentCoreMemory"; }
    @Override public int getOrder() { return org.springframework.ai.chat.client.advisor.api.Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER; }
    @Override public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        var result = chain.nextCall(enrich(request).block());
        record(request, result).block();
        return result;
    }
    @Override public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            var result = new java.util.concurrent.atomic.AtomicReference<ChatClientResponse>();
            return new org.springframework.ai.chat.client.ChatClientMessageAggregator()
                .aggregateChatClientResponse(enrich(request).flatMapMany(chain::nextStream), result::set)
                .concatWith(Mono.defer(() -> record(request, result.get())).then(Mono.empty()));
        });
    }
    private Mono<Void> record(ChatClientRequest request, ChatClientResponse response) {
        if (!writeBack || response == null || response.chatResponse() == null || response.chatResponse().getResult() == null) return Mono.empty();
        var result = response.chatResponse().getResult();
        String reason = result.getMetadata().getFinishReason();
        if (result.getOutput().hasToolCalls() || (reason != null && !reason.isEmpty()
                && !java.util.Set.of("stop", "end_turn", "STOP").contains(reason))) return Mono.empty();
        return memory.recordTurn(request.prompt().getUserMessage().getText(), result.getOutput().getText());
    }
    private Mono<ChatClientRequest> enrich(ChatClientRequest request) {
        return memory.recallForTurn(request.prompt().getUserMessage().getText()).map(text -> {
            if (text.isEmpty()) return request;
            var messages = new ArrayList<Message>(request.prompt().getInstructions()); messages.add(0, new SystemMessage(text));
            return request.mutate().prompt(new Prompt(messages, request.prompt().getOptions())).build();
        });
    }
}
