package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProofGraphCanonicalViewTest {
  @Test
  void rawHistoryAndFamilyCompressedOperationalViewCoexist() {
    ProofGraphStore graph = new ProofGraphStore(ObligationCanonicalizationTestFixtures.PROBLEM_HASH);
    add(graph, "a-1", "r1", "target a", "family-a");
    add(graph, "a-2", "r2", "target a", "family-a");
    add(graph, "b", "r3", "target b", "family-a");
    add(graph, "c", "r4", "target c", "");

    assertThat(graph.obligations()).hasSize(4);
    assertThat(graph.rawObligationOccurrences()).hasSize(4);
    assertThat(graph.allCanonicalTargets()).hasSize(3);
    assertThat(graph.allBottleneckFamilies()).hasSize(1);
    assertThat(graph.coreOpenWorkItems()).hasSize(2);
  }

  private static void add(
      ProofGraphStore graph, String id, String route, String target, String family) {
    ProofObligation obligation =
        ObligationCanonicalizationTestFixtures.obligation(id, route, target, target, family);
    graph.addObligationCanonicalized(
        obligation,
        ObligationCanonicalizationTestFixtures.context(
            obligation, route, family, List.of(), "positive", Map.of(), 0));
  }
}
