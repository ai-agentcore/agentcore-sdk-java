package io.agentcore.memory;

import reactor.core.publisher.Mono;

/** Turn-level memory operations consumed by framework adapters. */
public interface FrameworkMemory {
    Mono<String> recallForTurn(String input);
    Mono<Void> recordTurn(String input, String answer);
    void requireWriteScope();
}
