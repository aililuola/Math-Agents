package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FocusedRecoverySelectionTest {
  @Test
  void deterministicallySelectsFamilyWithMostOpenMembers() {
    ProofGraphStore graph = ProofGraphConvergenceTestFixtures.graphWithTarget();
    addFamilyMember(graph, "family-member-2", "route-b", "Prove the deletion lemma.");
    addFamilyMember(graph, "other-member", "route-c", "Prove an unrelated congruence.", "other");
    ProofGraphConvergenceMonitor monitor = new ProofGraphConvergenceMonitor();
    ProofGraphRoundMetrics stagnant =
        ProofGraphConvergenceTestFixtures.metrics(0, 3, 0, 0, 0, 1, 0, 0, 0, 0, 7.0d);

    monitor.observe(stagnant, graph, ProofGraphConvergenceTestFixtures.ROOT_HASH);
    monitor.observe(
        new ProofGraphRoundMetrics(
            1,
            stagnant.rawOpenObligations(),
            stagnant.activeCanonicalTargets(),
            stagnant.deferredCanonicalTargets(),
            stagnant.closedCanonicalTargets(),
            0,
            1,
            0,
            0,
            0,
            0,
            stagnant.rawProofDebt(),
            stagnant.activeCanonicalProofDebt(),
            stagnant.deferredCanonicalProofDebt(),
            stagnant.globalCanonicalProofDebt(),
            0.0d),
        graph,
        ProofGraphConvergenceTestFixtures.ROOT_HASH);

    FocusedRecoveryPlan plan = monitor.focusedRecoveryPlan().orElseThrow();
    assertThat(plan.selectedFamilyId()).isNotBlank();
    assertThat(plan.selectedCanonicalTargetIds()).hasSize(2);
  }

  private static void addFamilyMember(
      ProofGraphStore graph, String id, String route, String statement) {
    addFamilyMember(graph, id, route, statement, "shared-obstruction");
  }

  private static void addFamilyMember(
      ProofGraphStore graph, String id, String route, String statement, String family) {
    ProofObligation obligation =
        ObligationCanonicalizationTestFixtures.obligation(
            id, route, statement, statement.toLowerCase(java.util.Locale.ROOT), family);
    graph.addObligationCanonicalized(
        obligation,
        ObligationCanonicalizationTestFixtures.context(
            obligation,
            route,
            family,
            List.of("global"),
            "positive",
            Map.of(),
            0));
  }
}
