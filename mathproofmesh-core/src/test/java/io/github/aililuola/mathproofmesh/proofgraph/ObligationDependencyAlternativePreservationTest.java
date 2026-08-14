package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ObligationDependencyAlternativePreservationTest {
  @Test
  void oneTargetRetainsEveryRouteAndAlternativeDependencyPlan() {
    ProofGraphStore graph = new ProofGraphStore(ObligationCanonicalizationTestFixtures.PROBLEM_HASH);
    for (int index = 0; index < 3; index++) {
      String route = "route-" + index;
      ProofObligation obligation =
          ObligationCanonicalizationTestFixtures.obligation(
              "alternative-" + index,
              route,
              "Establish the shared lemma.",
              "establish the shared lemma",
              "shared-upstream",
              io.github.aililuola.mathproofmesh.contract.ObligationKind.LEMMA,
              List.of(),
              List.of(),
              "plan-" + index);
      graph.addObligationCanonicalized(
          obligation,
          ObligationCanonicalizationTestFixtures.context(
              obligation, route, "shared-upstream", List.of(), "positive", Map.of(), index));
    }

    CanonicalObligationRecord target = graph.allCanonicalTargets().getFirst();
    assertThat(graph.rawObligationOccurrences()).hasSize(3);
    assertThat(target.routeIds()).containsExactlyInAnyOrder("route-0", "route-1", "route-2");
    assertThat(target.dependencyPlanSignatures()).hasSize(3);
    assertThat(graph.obligations()).extracting(ProofObligation::obligationId).hasSize(3);
  }
}
