package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.ComputationDecisionPlan;

/** Authoritative problem/goal/Claim binding supplied by a production caller. */
public record ComputationExecutionContext(
    String problemHash,
    String rootGoalHash,
    String routeId,
    String claimId,
    String claimSemanticHash,
    String obligationId,
    String canonicalTargetId,
    int round,
    ComputationDecisionPlan decisionPlan) {
  public ComputationExecutionContext {
    problemHash = normalize(problemHash);
    rootGoalHash = normalize(rootGoalHash);
    routeId = required(routeId, "routeId");
    claimId = normalize(claimId);
    claimSemanticHash = normalize(claimSemanticHash);
    obligationId = normalize(obligationId);
    canonicalTargetId = normalize(canonicalTargetId);
    if (round < 0) {
      throw new IllegalArgumentException("round must be nonnegative");
    }
    if (!problemHash.isEmpty() && rootGoalHash.isEmpty()) {
      throw new IllegalArgumentException("problem-bound computation requires rootGoalHash");
    }
    if (!claimId.isEmpty() && claimSemanticHash.isEmpty()) {
      throw new IllegalArgumentException("claim-bound computation requires claimSemanticHash");
    }
  }

  public static ComputationExecutionContext legacy(String routeId) {
    return new ComputationExecutionContext(
        "", "", routeId, "", "", "", "", 0, null);
  }

  public boolean authoritativeBinding() {
    return !problemHash.isEmpty() && !rootGoalHash.isEmpty();
  }

  private static String required(String value, String field) {
    String normalized = normalize(value);
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }

  private static String normalize(String value) {
    return value == null ? "" : value.strip();
  }
}
