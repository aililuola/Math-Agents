package io.github.aililuola.mathproofmesh.provider;

import java.time.Duration;
import java.util.List;

@SuppressWarnings("serial")
public final class ProviderCircuitOpenError extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String providerScope;
  private final List<String> agentIds;
  private final Duration retryAfter;

  public ProviderCircuitOpenError(
      String providerScope,
      List<String> agentIds,
      Duration retryAfter,
      Throwable cause) {
    super(
        "provider circuit open for "
            + providerScope
            + "; distinct agents="
            + agentIds,
        cause);
    this.providerScope = java.util.Objects.requireNonNull(providerScope, "providerScope");
    this.agentIds = List.copyOf(agentIds);
    this.retryAfter = java.util.Objects.requireNonNull(retryAfter, "retryAfter");
  }

  public String providerScope() {
    return providerScope;
  }

  public List<String> agentIds() {
    return agentIds;
  }

  public Duration retryAfter() {
    return retryAfter;
  }
}
