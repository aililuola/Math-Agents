package io.github.aililuola.mathproofmesh.provider;

import java.math.BigDecimal;
import java.util.Map;

public record AgentRuntimeMetric(
    String agentId,
    long calls,
    long inputTokens,
    long outputTokens,
    BigDecimal costUsd,
    double latencyMs,
    double trust,
    long failures,
    long failedAttempts,
    Map<String, Long> failureCategories,
    int activeCalls,
    boolean lastExecutionWasVirtual) {

  public AgentRuntimeMetric {
    failureCategories = Map.copyOf(failureCategories);
  }
}
