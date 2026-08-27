package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FocusedRecoverySelectedBindingPolicyTest {
  @Test
  void focusedRecoveryRequiresTheSelectedBindingExceptForExistingExactFalsification() {
    ProofGraphStore graph = ProofGraphConvergenceTestFixtures.graphWithTarget();
    ProofGraphConvergenceMonitor monitor =
        new ProofGraphConvergenceMonitor(
            new ProofGraphConvergenceConfig(
                1, 20, 2, 99, 99, 1, 1.0e-9d, 2, 2, 1, 1, 1, 0.5d, 1, 2));
    monitor.sample(0, graph, 0, 0, 0, ProofGraphConvergenceTestFixtures.ROOT_HASH);
    FocusedRecoveryPlan plan = monitor.focusedRecoveryPlan().orElseThrow();
    String selectedTarget = plan.selectedCanonicalTargetIds().iterator().next();

    for (FocusedRecoveryActionType action :
        List.of(
            FocusedRecoveryActionType.FOCUSED_PROVER,
            FocusedRecoveryActionType.FOCUSED_SKEPTIC,
            FocusedRecoveryActionType.FAMILY_BRIDGE_REPAIR,
            FocusedRecoveryActionType.VERIFIED_CLAIM_REUSE)) {
      assertThat(decide(monitor, action, true, plan.selectedFamilyId(), selectedTarget).allowed())
          .isTrue();
      assertThat(decide(monitor, action, true, "other-family", "other-target").code())
          .isEqualTo("DEFER_UNSELECTED_RECOVERY_BINDING");
    }

    assertThat(
            decide(
                    monitor,
                    FocusedRecoveryActionType.EXACT_FALSIFICATION,
                    true,
                    "other-family",
                    "other-target")
                .allowed())
        .isTrue();
    for (FocusedRecoveryActionType generic :
        List.of(
            FocusedRecoveryActionType.GENERIC_INSPIRATION,
            FocusedRecoveryActionType.REPRESENTATION_SWITCH,
            FocusedRecoveryActionType.NEW_STRATEGY)) {
      assertThat(decide(monitor, generic, true, plan.selectedFamilyId(), selectedTarget).allowed())
          .isTrue();
      assertThat(decide(monitor, generic, true, "other-family", "other-target"))
          .isEqualTo(FocusedExpansionDecision.deferFocusedRecovery());
    }

    assertThat(
            decide(
                monitor,
                FocusedRecoveryActionType.EXACT_FALSIFICATION,
                false,
                "other-family",
                "prospective"))
        .isEqualTo(FocusedExpansionDecision.deferFocusedRecovery());
    assertThat(
            monitor.decideExpansion(
                FocusedRecoveryActionType.EXACT_FALSIFICATION,
                false,
                1,
                1,
                plan.selectedFamilyId(),
                "prospective"))
        .isEqualTo(FocusedExpansionDecision.deferCapacity());
    assertThat(
            decide(
                    monitor,
                    FocusedRecoveryActionType.EXACT_FALSIFICATION,
                    false,
                    plan.selectedFamilyId(),
                    "prospective")
                .allowed())
        .isTrue();

    ProofGraphConvergenceMonitor normal = new ProofGraphConvergenceMonitor();
    assertThat(
            decide(
                    normal,
                    FocusedRecoveryActionType.FOCUSED_PROVER,
                    true,
                    "other-family",
                    "other-target")
                .allowed())
        .isTrue();
  }

  private static FocusedExpansionDecision decide(
      ProofGraphConvergenceMonitor monitor,
      FocusedRecoveryActionType action,
      boolean existing,
      String family,
      String target) {
    return monitor.decideExpansion(action, existing, 0, 0, family, target);
  }
}
