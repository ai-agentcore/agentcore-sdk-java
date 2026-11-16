package example;

import io.agentcore.AgentCore;
import io.agentcore.memory.ContextEvent;
import io.agentcore.memory.ContextMemoryModels.CallOptions;
import io.agentcore.memory.ContextMemoryModels.ContextMemorySearchResult;
import io.agentcore.memory.ContextMemoryModels.CreateEventsResult;
import io.agentcore.memory.ContextMemoryModels.EventPage;
import io.agentcore.memory.ContextMemoryModels.ListEventsOptions;
import io.agentcore.memory.ContextMemoryModels.MemoryType;
import io.agentcore.memory.ContextMemoryModels.SearchOptions;
import io.agentcore.memory.MemoryScope;
import java.util.List;
import reactor.core.publisher.Mono;

/** The caller supplies trusted scope and owns persistence, subscription and reconciliation. */
public final class ContextMemoryExample {
  private ContextMemoryExample() {}

  public static Mono<ContextMemorySearchResult> recall(
      AgentCore sdk, String store, MemoryScope scope, String query, List<MemoryType> types) {
    return sdk.contextMemory(store)
        .searchMemories(
            query,
            new SearchOptions(scope, types, null, null, null, null, null),
            CallOptions.defaults());
  }

  public static Mono<CreateEventsResult> capture(
      AgentCore sdk, String store, MemoryScope scope, List<ContextEvent> persistedEvents) {
    return sdk.contextMemory(store).createEvents(scope, persistedEvents, CallOptions.defaults());
  }

  public static Mono<EventPage> reconcilePage(
      AgentCore sdk, String store, MemoryScope scope, String nextToken) {
    return sdk.contextMemory(store)
        .listEvents(new ListEventsOptions(scope, nextToken, 100), CallOptions.defaults());
  }
}
