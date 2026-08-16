package io.github.aililuola.mathproofmesh.computation;

import java.util.Objects;

public record ProducedComputation(
    HandlerEvidence evidence, String producerId, String producerVersion) {
  public ProducedComputation {
    evidence = Objects.requireNonNull(evidence, "evidence");
    producerId = required(producerId, "producerId");
    producerVersion = required(producerVersion, "producerVersion");
  }

  private static String required(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }
}
