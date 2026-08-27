package io.github.aililuola.mathproofmesh.runstate;

import java.time.Instant;
import java.util.Objects;

public record RunExecutionAttemptRecord(
    String attemptId,
    String runId,
    int ordinal,
    RunExecutionAttemptStatus status,
    String failureCode,
    Instant createdAt,
    Instant updatedAt,
    long version) {
  public RunExecutionAttemptRecord {
    attemptId = RunStateHashes.required(attemptId, "attemptId");
    runId = RunStateHashes.required(runId, "runId");
    status = Objects.requireNonNull(status, "status");
    failureCode = RunStateHashes.optional(failureCode);
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (ordinal < 0 || version < 0L || updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("invalid execution attempt counters or timestamps");
    }
  }
}
