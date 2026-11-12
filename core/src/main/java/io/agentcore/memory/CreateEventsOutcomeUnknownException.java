package io.agentcore.memory;

import io.agentcore.AgentCoreException;

/** A dispatched write could have been accepted. Reconcile before deciding whether to resubmit. */
public final class CreateEventsOutcomeUnknownException extends AgentCoreException {
  public CreateEventsOutcomeUnknownException(AgentCoreException cause) {
    super(
        "CreateEvents",
        cause.status(),
        cause.requestId(),
        cause.serviceCode(),
        cause.serviceMessage(),
        cause);
  }

  public boolean outcomeUnknown() {
    return true;
  }
}
