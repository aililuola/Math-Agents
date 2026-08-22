package io.github.aililuola.mathproofmesh.orchestration;

/** Stable decision identity; no wall clock, completion order, or random value is admitted. */
public record BudgetDecisionIdentity(String stateHash, String policyVersion, String decisionHash) {
  public BudgetDecisionIdentity {
    stateHash = required(stateHash, "stateHash");
    policyVersion = required(policyVersion, "policyVersion");
    decisionHash = required(decisionHash, "decisionHash");
  }

  private static String required(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }
}
