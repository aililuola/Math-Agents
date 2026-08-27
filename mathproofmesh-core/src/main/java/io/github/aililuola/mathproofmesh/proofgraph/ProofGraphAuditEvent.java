package io.github.aililuola.mathproofmesh.proofgraph;

import java.util.Map;

public record ProofGraphAuditEvent(
    long sequence,
    String eventType,
    String subjectId,
    long version,
    Map<String, String> details) {

  public ProofGraphAuditEvent {
    if (sequence < 1 || version < 0) {
      throw new IllegalArgumentException("proof graph audit sequence and version are invalid");
    }
    if (eventType == null || eventType.isBlank()) {
      throw new IllegalArgumentException("eventType is required");
    }
    if (subjectId == null || subjectId.isBlank()) {
      throw new IllegalArgumentException("subjectId is required");
    }
    details = details == null ? Map.of() : Map.copyOf(details);
  }
}
