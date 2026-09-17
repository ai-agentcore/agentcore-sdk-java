package io.agentcore.auth;

import reactor.core.publisher.Mono;

@FunctionalInterface
public interface AgentSATokenSource {
    Mono<String> get();
    default Mono<String> refresh(String rejectedToken) { return get(); }
}
