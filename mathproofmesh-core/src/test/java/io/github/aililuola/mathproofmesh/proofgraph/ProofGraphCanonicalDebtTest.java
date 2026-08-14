package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProofGraphCanonicalDebtTest {
  @Test
  void routeAndGlobalDebtCountEachMathematicalTargetOnce() {
    ProofGraphStore graph = new ProofGraphStore(ObligationCanonicalizationTestFixtures.PROBLEM_HASH);
    for (int index = 0; index < 4; index++) {
      String route = index < 3 ? "shared-route" : "other-route";
      ProofObligation obligation =
          ObligationCanonicalizationTestFixtures.obligation(
              "raw-" + index,
              route,
              index < 3 ? "same target" : "other target",
              index < 3 ? "same target" : "other target",
              index < 3 ? "family" : "");
      graph.addObligationCanonicalized(
          obligation,
          ObligationCanonicalizationTestFixtures.context(
              obligation, route, index < 3 ? "family" : "", List.of(), "positive", Map.of(), 0));
    }

    assertThat(graph.rawProofDebt("shared-route"))
        .isGreaterThan(graph.canonicalProofDebt("shared-route"));
    assertThat(graph.globalCanonicalProofDebt())
        .isEqualTo(graph.activeCanonicalProofDebt() + graph.deferredCanonicalProofDebt());
    assertThat(graph.globalCanonicalProofDebt())
        .isGreaterThan(graph.canonicalProofDebt("shared-route"));
  }
}
