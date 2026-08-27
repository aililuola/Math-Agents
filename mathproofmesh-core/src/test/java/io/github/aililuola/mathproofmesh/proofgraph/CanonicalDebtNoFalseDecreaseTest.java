package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalDebtNoFalseDecreaseTest {
  @Test
  void deferredTargetRemainsInGlobalCanonicalDebt() {
    ProofGraphStore graph =
        new ProofGraphStore(
            ObligationCanonicalizationTestFixtures.PROBLEM_HASH,
            ProofGraphPolicy.defaults());
    ProofObligation obligation =
        ObligationCanonicalizationTestFixtures.obligation(
            "deferred-target",
            "route-a",
            "Prove the deferred obstruction.",
            "prove the deferred obstruction",
            "deferred-family");
    ObligationCreationContext context =
        ObligationCanonicalizationTestFixtures.context(
                obligation,
                "route-a",
                "deferred-family",
                List.of("global"),
                "positive",
                Map.of(),
                0)
            .withSchedulingState(ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY);

    graph.addObligationCanonicalized(obligation, context);

    ProofObligation active =
        ObligationCanonicalizationTestFixtures.obligation(
            "active-target",
            "route-b",
            "Prove the active obstruction.",
            "prove the active obstruction",
            "active-family");
    ObligationCreationContext activeContext =
        ObligationCanonicalizationTestFixtures.context(
            active,
            "route-b",
            "active-family",
            List.of("global"),
            "positive",
            Map.of(),
            0);
    graph.addObligationCanonicalized(active, activeContext);

    assertThat(graph.canonicalOpenTargets()).hasSize(2);
    assertThat(graph.activeCanonicalOpenTargets()).hasSize(1);
    assertThat(graph.deferredCanonicalOpenTargets()).hasSize(1);
    assertThat(graph.canonicalOpenTargets("route-a")).hasSize(1);
    assertThat(graph.canonicalOpenTargets("missing-route")).isEmpty();
    assertThat(graph.activeCanonicalTargetCount()).isEqualTo(1);
    assertThat(graph.activeCanonicalTargetCount("route-a")).isZero();
    assertThat(graph.activeCanonicalTargetCount("route-b")).isEqualTo(1);
    assertThat(graph.coreOpenWorkItems()).hasSize(1);
    assertThat(graph.activeBottleneckFamilies()).hasSize(1);
    assertThat(graph.allBottleneckFamilies()).hasSize(2);
    assertThat(graph.existingCanonicalTargetId(active, activeContext)).isPresent();
    assertThat(graph.wouldCreateCanonicalTarget(active, activeContext)).isFalse();
    ProofObligation newTarget =
        ObligationCanonicalizationTestFixtures.obligation(
            "new-target",
            "route-c",
            "Prove a new obstruction.",
            "prove a new obstruction",
            "new-family");
    assertThat(
            graph.wouldCreateCanonicalTarget(
                newTarget,
                ObligationCanonicalizationTestFixtures.context(
                    newTarget,
                    "route-c",
                    "new-family",
                    List.of("global"),
                    "positive",
                    Map.of(),
                    0)))
        .isTrue();

    String activeCanonicalId =
        graph.canonicalTargetForObligation(active.obligationId())
            .orElseThrow()
            .canonicalTargetId();
    assertThat(graph.representativeStatement(activeCanonicalId)).isEqualTo(active.statement());
    assertThat(graph.representativeCentrality(activeCanonicalId)).isEqualTo(active.centrality());
    assertThat(graph.representativePriority(activeCanonicalId)).isEqualTo(active.priority());

    assertThat(graph.activeCanonicalProofDebt()).isPositive();
    assertThat(graph.deferredCanonicalProofDebt()).isPositive();
    assertThat(graph.globalCanonicalProofDebt())
        .isEqualTo(graph.activeCanonicalProofDebt() + graph.deferredCanonicalProofDebt());
  }
}
