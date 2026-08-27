package io.github.aililuola.mathproofmesh.concurrency;

import java.util.Objects;

public record ConcurrencyTelemetryEvent(
    long sequence,
    long monotonicNanos,
    ConcurrencyEventType type,
    String epochId,
    String workItemId,
    String agentId,
    int readyWorkCount) {
  public ConcurrencyTelemetryEvent {
    if (sequence < 1L || monotonicNanos < 0L || readyWorkCount < 0) {
      throw new IllegalArgumentException("telemetry counters must be valid");
    }
    type = Objects.requireNonNull(type, "type");
    epochId = optional(epochId);
    workItemId = optional(workItemId);
    agentId = optional(agentId);
  }

  private static String optional(String value) {
    return value == null ? "" : value.strip();
  }
}
