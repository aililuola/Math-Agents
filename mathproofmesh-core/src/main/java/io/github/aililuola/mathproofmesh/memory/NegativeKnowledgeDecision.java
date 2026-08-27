package io.github.aililuola.mathproofmesh.memory;

import java.util.List;

public record NegativeKnowledgeDecision(
    NegativeKnowledgeDecisionCode code,
    NegativeMatchStrength matchStrength,
    NegativeKnowledgeSurface surface,
    NegativeCandidateIntent intent,
    List<String> matchedNegativeIds,
    String detail) {

  public NegativeKnowledgeDecision {
    code = java.util.Objects.requireNonNull(code, "code");
    matchStrength = java.util.Objects.requireNonNull(matchStrength, "matchStrength");
    surface = java.util.Objects.requireNonNull(surface, "surface");
    intent = java.util.Objects.requireNonNull(intent, "intent");
    matchedNegativeIds = matchedNegativeIds == null ? List.of() : List.copyOf(matchedNegativeIds);
    detail = detail == null ? "" : detail.trim();
  }

  public boolean allowed() {
    return code == NegativeKnowledgeDecisionCode.ALLOW
        || code == NegativeKnowledgeDecisionCode.ALLOW_FALSIFICATION_ONLY;
  }
}
