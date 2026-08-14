package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalCounterexampleBoundaryTest {
  @Test
  void aCounterexampleRefutesOnlyItsExactCanonicalTargetNotTheFamily() {
    ProofGraphStore graph = new ProofGraphStore(ObligationCanonicalizationTestFixtures.PROBLEM_HASH);
    add(graph, "target", "route-a", "claim p");
    add(graph, "sibling", "route-b", "claim q");

    var counterexample =
        ObligationCanonicalizationTestFixtures.counterexample("counterexample", "claim p");
    assertThat(graph.applyCounterexample(counterexample)).containsExactly("target");
    assertThat(graph.getObligation("target").status()).isEqualTo("refuted");
    assertThat(graph.getObligation("sibling").status()).isEqualTo("open");
  }

  private static void add(ProofGraphStore graph, String id, String route, String statement) {
    ProofObligation obligation =
        ObligationCanonicalizationTestFixtures.obligation(
            id, route, statement, statement, "shared-family");
    graph.addObligationCanonicalized(
        obligation,
        ObligationCanonicalizationTestFixtures.context(
            obligation, route, "shared-family", List.of(), "positive", Map.of(), 0));
  }
}
