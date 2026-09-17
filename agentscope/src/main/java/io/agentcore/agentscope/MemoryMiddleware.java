package io.agentcore.agentscope;

import io.agentcore.memory.MemoryContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Invocation-local recall and opt-in write-back, shared by call() and streamEvents(). */
public final class MemoryMiddleware implements MiddlewareBase {
    private final Object recallKey = new Object();
    private final MemoryContext memory;
    private final boolean writeBack;
    public MemoryMiddleware(MemoryContext memory) { this(memory, false); }
    public MemoryMiddleware(MemoryContext memory, boolean writeBack) {
        this.memory = memory; this.writeBack = writeBack;
        if (writeBack) memory.requireWriteScope();
    }
    @Override public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            var users = input.msgs().stream().filter(msg -> msg.getRole() == MsgRole.USER).toList();
            if (users.isEmpty()) return next.apply(input);
            String query = users.get(users.size() - 1).getTextContent();
            var result = new java.util.concurrent.atomic.AtomicReference<io.agentscope.core.message.Msg>();
            return memory.recallForTurn(query).flatMapMany(recalled -> next.apply(input).doOnNext(event -> {
                if (event instanceof AgentResultEvent completed) result.set(completed.getResult());
            }).concatWith(Mono.defer(() -> {
                var answer = result.get();
                return writeBack && answer != null && answer.getGenerateReason() == io.agentscope.core.message.GenerateReason.MODEL_STOP
                    ? memory.recordTurn(query, answer.getTextContent()) : Mono.<Void>empty();
            }).then(Mono.empty()))
                .contextWrite(context -> context.put(recallKey, recalled)));
        });
    }
    @Override public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        return Mono.deferContextual(context -> {
            String recalled = context.getOrDefault(recallKey, "");
            return Mono.just(recalled.isEmpty() ? currentPrompt : currentPrompt + "\n" + recalled);
        });
    }
}
