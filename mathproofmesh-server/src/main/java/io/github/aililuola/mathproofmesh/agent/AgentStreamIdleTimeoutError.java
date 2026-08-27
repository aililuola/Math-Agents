package io.github.aililuola.mathproofmesh.agent;

import io.github.aililuola.mathproofmesh.contract.UsageRecord;
import java.util.Map;

public final class AgentStreamIdleTimeoutError extends AgentProgressError {
  private static final long serialVersionUID = 1L;

  public AgentStreamIdleTimeoutError(
      String message, UsageRecord usage, Map<String, ?> progress) {
    super(message, usage, progress, null);
  }
}
