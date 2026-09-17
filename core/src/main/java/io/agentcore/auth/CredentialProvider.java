package io.agentcore.auth;

import reactor.core.publisher.Mono;

@FunctionalInterface
public interface CredentialProvider {
    Mono<AccessKeyCredential> get(String purpose);
}
