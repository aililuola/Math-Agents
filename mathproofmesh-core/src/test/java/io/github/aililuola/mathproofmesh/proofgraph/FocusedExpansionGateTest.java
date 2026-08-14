package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FocusedExpansionGateTest {
  @Test
  void blocksUnrelatedGenericExpansionButAllowsFocusedAndFalsificationActions() {
    ProofGraphStore graph = ProofGraphConvergenceTestFixtures.graphWithTarget();
    ProofGraphConvergenceMonitor monitor = focused(graph);
    FocusedRecoveryPlan plan = monitor.focusedRecoveryPlan().orElseThrow();
    String canonicalId = plan.selectedCanonicalTargetIds().iterator().next();

    assertThat(
            monitor.decideExpansion(
                FocusedRecoveryActionType.GENERIC_INSPIRATION, true, 1, 1, "", "unrelated"))
        .extracting(FocusedExpansionDecision::allowed, FocusedExpansionDecision::deferred)
        .containsExactly(false, true);
    assertThat(
            monitor.decideExpansion(
                FocusedRecoveryActionType.GENERIC_INSPIRATION,
                true,
                1,
                1,
                plan.selectedFamilyId(),
                canonicalId))
        .isEqualTo(FocusedExpansionDecision.allow());
    assertThat(
            monitor.decideExpansion(
                FocusedRecoveryActionType.EXACT_FALSIFICATION,
                true,
                1,
                1,
                "",
                "unrelated"))
        .isEqualTo(FocusedExpansionDecision.allow());
  }

  static ProofGraphConvergenceMonitor focused(ProofGraphStore graph) {
    ProofGraphConvergenceMonitor monitor = new ProofGraphConvergenceMonitor();
    ProofGraphRoundMetrics metrics =
        ProofGraphConvergenceTestFixtures.metrics(0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 4.0d);
    monitor.observe(metrics, graph, ProofGraphConvergenceTestFixtures.ROOT_HASH);
    monitor.observe(
        new ProofGraphRoundMetrics(
            1,
            metrics.rawOpenObligations(),
            metrics.activeCanonicalTargets(),
            metrics.deferredCanonicalTargets(),
            metrics.closedCanonicalTargets(),
            0,
            1,
            0,
            0,
            0,
            0,
            metrics.rawProofDebt(),
            metrics.activeCanonicalProofDebt(),
            metrics.deferredCanonicalProofDebt(),
            metrics.globalCanonicalProofDebt(),
            0.0d),
        graph,
        ProofGraphConvergenceTestFixtures.ROOT_HASH);
    return monitor;
  }
}
