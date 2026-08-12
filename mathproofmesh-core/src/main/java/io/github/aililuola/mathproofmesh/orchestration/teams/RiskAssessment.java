package io.github.aililuola.mathproofmesh.orchestration.teams;

import java.util.List;

/** Risk signals that decide whether a route needs skeptic or tool review. */
public record RiskAssessment(
    double score,
    List<String> reasons,
    boolean needsSkeptic,
    boolean needsTool,
    boolean enteringGlobalFactGate) {
  public RiskAssessment {
    if (!Double.isFinite(score) || score < 0.0d || score > 1.0d) {
      throw new IllegalArgumentException("score must be in [0,1]");
    }
    reasons = reasons == null ? List.of() : List.copyOf(reasons);
  }

  @Override
  public List<String> reasons() {
    return List.copyOf(reasons);
  }
}
