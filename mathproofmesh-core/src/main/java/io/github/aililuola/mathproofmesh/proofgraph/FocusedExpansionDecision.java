package io.github.aililuola.mathproofmesh.proofgraph;

/** Deterministic scheduling result for one candidate expansion. */
public record FocusedExpansionDecision(
    boolean allowed,
    boolean deferred,
    ObligationOccurrenceSchedulingState schedulingState,
    String code) {

  public FocusedExpansionDecision {
    schedulingState =
        schedulingState == null
            ? ObligationOccurrenceSchedulingState.ACTIVE
            : schedulingState;
    code = require(code);
    if (allowed && deferred) {
      throw new IllegalArgumentException("an expansion cannot be both allowed and deferred");
    }
  }

  public static FocusedExpansionDecision allow() {
    return new FocusedExpansionDecision(
        true, false, ObligationOccurrenceSchedulingState.ACTIVE, "ALLOW");
  }

  public static FocusedExpansionDecision deferCapacity() {
    return new FocusedExpansionDecision(
        false,
        true,
        ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY,
        "DEFER_ACTIVE_TARGET_CAPACITY");
  }

  public static FocusedExpansionDecision deferFocusedRecovery() {
    return new FocusedExpansionDecision(
        false,
        true,
        ObligationOccurrenceSchedulingState.DEFERRED_FOCUSED_RECOVERY,
        "DEFER_UNRELATED_FOCUSED_RECOVERY_EXPANSION");
  }

  private static String require(String value) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("decision code is required");
    }
    return normalized;
  }
}
