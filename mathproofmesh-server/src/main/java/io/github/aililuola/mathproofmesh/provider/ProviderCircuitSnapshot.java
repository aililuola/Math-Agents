package io.github.aililuola.mathproofmesh.provider;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ProviderCircuitSnapshot(
    String providerScope,
    List<Failure> failures,
    Instant openUntil,
    long version) {

  public ProviderCircuitSnapshot {
    providerScope = Objects.requireNonNull(providerScope, "providerScope");
    failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
  }

  public record Failure(Instant occurredAt, String agentId, String category) {
    public Failure {
      Objects.requireNonNull(occurredAt, "occurredAt");
      Objects.requireNonNull(agentId, "agentId");
      Objects.requireNonNull(category, "category");
    }
  }
}
