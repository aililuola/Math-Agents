package io.github.aililuola.mathproofmesh.proofgraph;

import java.util.Map;

public record ObligationCanonicalizationAuditEvent(
    long sequence,
    String code,
    String subjectId,
    Map<String, String> details) {

  public ObligationCanonicalizationAuditEvent {
    if (sequence <= 0) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    code = require(code, "code");
    subjectId = require(subjectId, "subjectId");
    details = details == null ? Map.of() : Map.copyOf(details);
  }

  private static String require(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }
}
