package io.github.aililuola.mathproofmesh.persistence;

import java.time.Instant;
import java.util.Objects;

public record RunLease(
    String runId,
    String ownerId,
    long fencingToken,
    Instant expiresAt,
    Instant heartbeatAt
) {
  public RunLease {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(ownerId, "ownerId");
    Objects.requireNonNull(expiresAt, "expiresAt");
    Objects.requireNonNull(heartbeatAt, "heartbeatAt");
  }
}
