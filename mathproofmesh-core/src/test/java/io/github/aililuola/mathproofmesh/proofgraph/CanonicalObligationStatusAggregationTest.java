package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalObligationStatusAggregationTest {
  @Test
  void canonicalStatusIsDerivedWithoutPropagatingAcrossFamilyMembers() {
    ProofGraphStore graph = new ProofGraphStore(ObligationCanonicalizationTestFixtures.PROBLEM_HASH);
    add(graph, "left", "r1", "same target");
    add(graph, "right", "r2", "same target");
    add(graph, "family-sibling", "r3", "different family target");
    String targetId = graph.canonicalTargetForObligation("left").orElseThrow().canonicalTargetId();

    var fact = ObligationCanonicalizationTestFixtures.verifiedFact("status-fact", "same target");
    graph.addClaimNode(fact);
    graph.closeObligation("left", fact.messageId(), 1.0d);
    graph.refuteObligation("right", null);

    assertThat(graph.canonicalStatus(targetId)).isEqualTo(CanonicalObligationStatus.MIXED);
    assertThat(graph.getObligation("family-sibling").status()).isEqualTo("open");
    assertThat(graph.canonicalStatus(
            graph.canonicalTargetForObligation("family-sibling").orElseThrow().canonicalTargetId()))
        .isEqualTo(CanonicalObligationStatus.OPEN);
  }

  private static void add(ProofGraphStore graph, String id, String route, String statement) {
    ProofObligation obligation =
        ObligationCanonicalizationTestFixtures.obligation(
            id, route, statement, statement, "one-family");
    graph.addObligationCanonicalized(
        obligation,
        ObligationCanonicalizationTestFixtures.context(
            obligation, route, "one-family", List.of(), "positive", Map.of(), 0));
  }
}
