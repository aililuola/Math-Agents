package io.github.aililuola.mathproofmesh.proofgraph;

public record DeferredExpansionReactivationDecision(
    String deferredId, DeferredExpansionReactivationOutcome outcome, String reason) {

  public DeferredExpansionReactivationDecision {
    deferredId = require(deferredId, "deferredId");
    outcome = java.util.Objects.requireNonNull(outcome, "outcome");
    reason = require(reason, "reason");
  }

  public boolean reactivates() {
    return outcome == DeferredExpansionReactivationOutcome.REACTIVATE;
  }

  private static String require(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }
}
