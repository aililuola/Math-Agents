package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DeferredExpansionReactivationEligibilityTest {
  @Test
  void distinguishesCapacityFocusedTerminalNegativeAndAlreadyActiveTargets() {
    var planner = new DeferredExpansionReactivationPlanner();
    assertOutcome(
        planner,
        candidate("capacity", 8, 8, true, true, CanonicalObligationStatus.OPEN, true),
        ProofGraphControlMode.NORMAL_EXPANSION,
        DeferredExpansionReactivationOutcome.KEEP_DEFERRED);
    assertOutcome(
        planner,
        candidate("focused", 0, 0, false, true, CanonicalObligationStatus.OPEN, true),
        ProofGraphControlMode.FOCUSED_RECOVERY,
        DeferredExpansionReactivationOutcome.KEEP_DEFERRED);
    assertOutcome(
        planner,
        candidate("selected", 0, 0, true, true, CanonicalObligationStatus.OPEN, true),
        ProofGraphControlMode.FOCUSED_RECOVERY,
        DeferredExpansionReactivationOutcome.REACTIVATE);
    assertOutcome(
        planner,
        candidate("mixed", 0, 0, true, true, CanonicalObligationStatus.MIXED, true),
        ProofGraphControlMode.NORMAL_EXPANSION,
        DeferredExpansionReactivationOutcome.REACTIVATE);
    assertOutcome(
        planner,
        candidate("negative", 0, 0, true, false, CanonicalObligationStatus.OPEN, true),
        ProofGraphControlMode.NORMAL_EXPANSION,
        DeferredExpansionReactivationOutcome.RETIRE);
    assertOutcome(
        planner,
        candidate("terminal", 0, 0, true, true, CanonicalObligationStatus.RESOLVED, true),
        ProofGraphControlMode.NORMAL_EXPANSION,
        DeferredExpansionReactivationOutcome.RETIRE);
    assertOutcome(
        planner,
        candidate("active", 0, 0, true, true, CanonicalObligationStatus.OPEN, false),
        ProofGraphControlMode.NORMAL_EXPANSION,
        DeferredExpansionReactivationOutcome.SATISFY_BY_ACTIVE_TARGET);
  }

  private static void assertOutcome(
      DeferredExpansionReactivationPlanner planner,
      DeferredExpansionReactivationCandidate candidate,
      ProofGraphControlMode mode,
      DeferredExpansionReactivationOutcome expected) {
    assertThat(planner.plan(List.of(candidate), mode, ProofGraphConvergenceConfig.defaults()))
        .singleElement()
        .extracting(DeferredExpansionReactivationDecision::outcome)
        .isEqualTo(expected);
  }

  private static DeferredExpansionReactivationCandidate candidate(
      String id,
      int routeCount,
      int campaignCount,
      boolean selected,
      boolean negativeAllowed,
      CanonicalObligationStatus canonicalStatus,
      boolean deferred) {
    return new DeferredExpansionReactivationCandidate(
        new DeferredExpansionRecord(
            id,
            "problem",
            0,
            "route-" + id,
            "obligation-" + id,
            "canonical-" + id,
            FocusedRecoveryActionType.NEW_STRATEGY,
            ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY,
            "capacity",
            0),
        routeCount,
        campaignCount,
        true,
        true,
        true,
        canonicalStatus,
        deferred
            ? CanonicalObligationSchedulingState.DEFERRED_CAPACITY
            : CanonicalObligationSchedulingState.ACTIVE,
        true,
        false,
        negativeAllowed,
        selected,
        0.5,
        0.5);
  }
}
