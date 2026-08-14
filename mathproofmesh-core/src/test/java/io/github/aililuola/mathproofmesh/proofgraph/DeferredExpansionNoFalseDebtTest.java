package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeferredExpansionNoFalseDebtTest {
  @Test
  void schedulingReactivationMovesDebtBucketsWithoutChangingGlobalDebt() {
    ProofGraphStore graph =
        new ProofGraphStore(
            ObligationCanonicalizationTestFixtures.PROBLEM_HASH, ProofGraphPolicy.defaults());
    var obligation =
        ObligationCanonicalizationTestFixtures.obligation(
            "debt-target", "route-a", "Prove debt target.", "prove debt target", "debt-family");
    graph.addObligationCanonicalized(
        obligation,
        ObligationCanonicalizationTestFixtures.context(
                obligation,
                "route-a",
                "debt-family",
                List.of("global"),
                "positive",
                Map.of(),
                0)
            .withSchedulingState(ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY));
    var target = graph.canonicalTargetForObligation(obligation.obligationId()).orElseThrow();
    double globalBefore = graph.globalCanonicalProofDebt();
    double deferredBefore = graph.deferredCanonicalProofDebt();

    var result =
        graph.reactivateCanonicalTarget(
            target.canonicalTargetId(),
            obligation.obligationId(),
            ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY,
            1,
            "capacity released");

    assertThat(result.code()).isEqualTo(CanonicalSchedulingTransitionCode.REACTIVATED);
    assertThat(graph.globalCanonicalProofDebt()).isEqualTo(globalBefore);
    assertThat(graph.deferredCanonicalProofDebt()).isLessThan(deferredBefore);
    assertThat(graph.activeCanonicalProofDebt()).isPositive();
  }
}
