package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProofGraphConvergenceSnapshotTest {
  @Test
  void restoresFocusedModeHistoryPlanAndLeasesExactlyOnce() {
    ProofGraphStore graph = ProofGraphConvergenceTestFixtures.graphWithTarget();
    ProofGraphConvergenceMonitor monitor = FocusedExpansionGateTest.focused(graph);
    monitor.acquireFocusedTaskLease(FocusedRecoveryActionType.FOCUSED_PROVER, 2);
    monitor.recordGenericExpansionAttempt(false);
    String hash = monitor.stableHash();

    ProofGraphConvergenceMonitor restored =
        ProofGraphConvergenceMonitor.restore(monitor.config(), monitor.snapshot());

    assertThat(restored.controlMode()).isEqualTo(ProofGraphControlMode.FOCUSED_RECOVERY);
    assertThat(restored.focusedRecoveryPlan()).isEqualTo(monitor.focusedRecoveryPlan());
    assertThat(restored.roundHistory()).containsExactlyElementsOf(monitor.roundHistory());
    assertThat(restored.stableHash()).isEqualTo(hash);
    assertThat(
            restored.acquireFocusedTaskLease(
                FocusedRecoveryActionType.FOCUSED_PROVER, 2))
        .isFalse();
  }
}
