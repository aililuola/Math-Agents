package io.github.aililuola.mathproofmesh.persistence;

import java.time.Instant;
import java.util.Objects;

public record RunRecord(
    String runId,
    String problemHash,
    String status,
    String currentStage,
    String configPayload,
    long fencingToken,
    long version,
    Instant createdAt,
    Instant updatedAt
) {
  public RunRecord {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(problemHash, "problemHash");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(currentStage, "currentStage");
    Objects.requireNonNull(configPayload, "configPayload");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }
}
