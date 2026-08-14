package io.github.aililuola.mathproofmesh.proofgraph;

import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import java.util.List;
import java.util.Map;

final class ProofGraphConvergenceTestFixtures {
  static final String ROOT_HASH = "a".repeat(64);

  private ProofGraphConvergenceTestFixtures() {}

  static ProofGraphStore graphWithTarget() {
    ProofGraphStore graph =
        new ProofGraphStore(
            ObligationCanonicalizationTestFixtures.PROBLEM_HASH,
            ProofGraphPolicy.defaults());
    ProofObligation obligation =
        ObligationCanonicalizationTestFixtures.obligation(
            "recovery-target",
            "route-a",
            "Prove the minimal hitting-set obstruction.",
            "prove the minimal hitting-set obstruction",
            "shared-obstruction");
    graph.addObligationCanonicalized(
        obligation,
        ObligationCanonicalizationTestFixtures.context(
            obligation,
            "route-a",
            "shared-obstruction",
            List.of("global"),
            "positive",
            Map.of(),
            0));
    return graph;
  }

  static ProofGraphRoundMetrics metrics(
      int round,
      int active,
      int deferred,
      int closed,
      int created,
      int duplicates,
      int forbidden,
      int verified,
      int refuted,
      int newlyClosed,
      double debt) {
    return new ProofGraphRoundMetrics(
        round,
        active + deferred,
        active,
        deferred,
        closed,
        created,
        duplicates,
        forbidden,
        verified,
        refuted,
        newlyClosed,
        debt,
        Math.max(0.0d, debt - deferred),
        Math.min(debt, deferred),
        debt,
        0.0d);
  }
}
