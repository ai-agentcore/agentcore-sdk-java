package io.agentcore.server;

import io.agentcore.event.AgentEvent;
import reactor.core.publisher.Flux;

/** The application's Agent entry point, independent of the incoming HTTP protocol. */
@FunctionalInterface
public interface InvokeHandler { Flux<AgentEvent> invoke(AgentRequest request); }
