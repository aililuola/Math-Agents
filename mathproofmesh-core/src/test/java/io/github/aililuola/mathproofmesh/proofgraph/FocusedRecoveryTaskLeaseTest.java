package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FocusedRecoveryTaskLeaseTest {
  @Test
  void deduplicatesSameFamilyActionAndRoundWithinEpisode() {
    ProofGraphStore graph = ProofGraphConvergenceTestFixtures.graphWithTarget();
    ProofGraphConvergenceMonitor monitor = FocusedExpansionGateTest.focused(graph);

    assertThat(monitor.acquireFocusedTaskLease(FocusedRecoveryActionType.FOCUSED_PROVER, 2))
        .isTrue();
    assertThat(monitor.acquireFocusedTaskLease(FocusedRecoveryActionType.FOCUSED_PROVER, 2))
        .isFalse();
    assertThat(monitor.acquireFocusedTaskLease(FocusedRecoveryActionType.FOCUSED_PROVER, 3))
        .isTrue();
  }
}
