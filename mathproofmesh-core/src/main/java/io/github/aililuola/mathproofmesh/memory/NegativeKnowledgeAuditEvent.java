package io.github.aililuola.mathproofmesh.memory;

public record NegativeKnowledgeAuditEvent(
    long sequence,
    int round,
    NegativeKnowledgeSurface surface,
    NegativeCandidateIntent intent,
    NegativeKnowledgeDecisionCode decisionCode,
    NegativeMatchStrength matchStrength,
    String candidateSemanticKey,
    String matchedNegativeId,
    String detail) {

  public NegativeKnowledgeAuditEvent {
    if (sequence < 1 || round < 0) {
      throw new IllegalArgumentException("negative knowledge audit sequence and round are invalid");
    }
    surface = java.util.Objects.requireNonNull(surface, "surface");
    intent = java.util.Objects.requireNonNull(intent, "intent");
    decisionCode = java.util.Objects.requireNonNull(decisionCode, "decisionCode");
    matchStrength = java.util.Objects.requireNonNull(matchStrength, "matchStrength");
    candidateSemanticKey = require(candidateSemanticKey, "candidateSemanticKey");
    matchedNegativeId = matchedNegativeId == null ? "" : matchedNegativeId.trim();
    detail = detail == null ? "" : detail.trim();
  }

  private static String require(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value.trim();
  }
}
