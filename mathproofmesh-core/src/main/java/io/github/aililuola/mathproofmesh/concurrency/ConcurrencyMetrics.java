package io.github.aililuola.mathproofmesh.concurrency;

import java.util.Map;

public record ConcurrencyMetrics(
    double meanConcurrencyWholeRun,
    double meanConcurrencyProviderActiveWindow,
    double meanConcurrencyWhileReadyWorkExists,
    double researchSlotUtilizationWhileReady,
    double totalSlotUtilizationWhileReady,
    double singleActiveFractionWhileReady,
    double zeroActiveFractionWhileReady,
    int maxActiveProviderCalls,
    Map<String, Long> perAgentBusyNanos,
    Map<String, Long> perAgentLeaseCount,
    long queueWaitNanos,
    long barrierWaitNanos,
    long idleSlotsWhileReadyNanos) {
  public ConcurrencyMetrics {
    perAgentBusyNanos = perAgentBusyNanos == null ? Map.of() : Map.copyOf(perAgentBusyNanos);
    perAgentLeaseCount = perAgentLeaseCount == null ? Map.of() : Map.copyOf(perAgentLeaseCount);
  }
}
