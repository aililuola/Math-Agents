package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CanonicalTargetCapacityTest {
  @Test
  void defersNewTargetsAtCapacityWithoutChargingDuplicates() {
    ProofGraphConvergenceMonitor monitor = new ProofGraphConvergenceMonitor();

    FocusedExpansionDecision newTarget =
        monitor.decideExpansion(
            FocusedRecoveryActionType.NEW_STRATEGY, false, 8, 20, "", "");
    FocusedExpansionDecision duplicate =
        monitor.decideExpansion(
            FocusedRecoveryActionType.NEW_STRATEGY, true, 8, 20, "", "existing");

    assertThat(newTarget.schedulingState())
        .isEqualTo(ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY);
    assertThat(newTarget.deferred()).isTrue();
    assertThat(duplicate.allowed()).isTrue();
  }
}
