package io.github.aililuola.mathproofmesh.persistence;

import java.time.Instant;
import java.util.Objects;

public record OutboxRecord(
    String eventId,
    String runId,
    String aggregateType,
    String aggregateId,
    long aggregateVersion,
    String eventType,
    String payload,
    Instant availableAt,
    String claimedBy,
    Instant claimedAt,
    Instant publishedAt,
    int attempts
) {
  public OutboxRecord {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(aggregateType, "aggregateType");
    Objects.requireNonNull(aggregateId, "aggregateId");
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(availableAt, "availableAt");
  }
}
