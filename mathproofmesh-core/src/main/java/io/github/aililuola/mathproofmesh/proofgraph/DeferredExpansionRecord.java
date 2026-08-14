package io.github.aililuola.mathproofmesh.proofgraph;

/** Audit-preserving record for a proposal deferred by capacity or focused recovery. */
public record DeferredExpansionRecord(
    String deferredId,
    String problemHash,
    int round,
    String routeId,
    String obligationId,
    String canonicalTargetId,
    FocusedRecoveryActionType actionType,
    ObligationOccurrenceSchedulingState schedulingState,
    String reason,
    long version) {

  public DeferredExpansionRecord {
    deferredId = require(deferredId, "deferredId");
    problemHash = require(problemHash, "problemHash");
    routeId = normalize(routeId);
    obligationId = normalize(obligationId);
    canonicalTargetId = normalize(canonicalTargetId);
    actionType = java.util.Objects.requireNonNull(actionType, "actionType");
    schedulingState = java.util.Objects.requireNonNull(schedulingState, "schedulingState");
    reason = require(reason, "reason");
    if (round < 0 || version < 0 || schedulingState == ObligationOccurrenceSchedulingState.ACTIVE) {
      throw new IllegalArgumentException("deferred expansion must have deferred state and counters");
    }
  }

  private static String normalize(String value) {
    return value == null ? "" : value.strip();
  }

  private static String require(String value, String field) {
    String normalized = normalize(value);
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }
}
