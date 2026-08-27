package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DeferredExpansionReactivationOrderingTest {
  @Test
  void ordersByPriorityCentralityOriginalRoundAndIdAndCapsEachRound() {
    var planner = new DeferredExpansionReactivationPlanner();
    List<DeferredExpansionReactivationDecision> decisions =
        planner.plan(
            List.of(
                candidate(record("d", 0), 0.8, 0.4),
                candidate(record("c", 0), 0.8, 0.9),
                candidate(record("b", 1), 0.8, 0.9),
                candidate(record("a", 0), 0.9, 0.1)),
            ProofGraphControlMode.NORMAL_EXPANSION,
            ProofGraphConvergenceConfig.defaults());

    assertThat(decisions).extracting(DeferredExpansionReactivationDecision::deferredId)
        .containsExactly("a", "c", "b", "d");
    assertThat(decisions.stream().filter(DeferredExpansionReactivationDecision::reactivates))
        .hasSize(2);
    assertThat(decisions.get(2).reason()).isEqualTo("ROUND_LIMIT");
  }

  private static DeferredExpansionReactivationCandidate candidate(
      DeferredExpansionRecord record, double priority, double centrality) {
    return new DeferredExpansionReactivationCandidate(
        record,
        0,
        0,
        true,
        true,
        true,
        CanonicalObligationStatus.OPEN,
        CanonicalObligationSchedulingState.DEFERRED_CAPACITY,
        true,
        false,
        true,
        true,
        centrality,
        priority);
  }

  private static DeferredExpansionRecord record(String id, int round) {
    return new DeferredExpansionRecord(
        id,
        "problem",
        round,
        "route-" + id,
        "obligation-" + id,
        "canonical-" + id,
        FocusedRecoveryActionType.NEW_STRATEGY,
        ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY,
        "capacity",
        0);
  }
}
