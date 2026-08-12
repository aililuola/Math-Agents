package io.github.aililuola.mathproofmesh.verification;

import java.util.List;

public record EscalationPlan(
    double riskScore,
    List<ValidationLevel> levels,
    List<String> diagnostics,
    boolean blocksFactPromotion) {

  public EscalationPlan {
    if (!Double.isFinite(riskScore) || riskScore < 0.0 || riskScore > 1.0) {
      throw new IllegalArgumentException("risk score must be between zero and one");
    }
    levels = levels == null ? List.of() : List.copyOf(levels);
    diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    if (levels.size() != new java.util.LinkedHashSet<>(levels).size()) {
      throw new IllegalArgumentException("validation levels must be unique");
    }
  }
}
