package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ObligationFamilyReviewBatch;
import io.github.aililuola.mathproofmesh.contract.ObligationFamilyReviewDecision;
import io.github.aililuola.mathproofmesh.contract.UsageRecord;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObligationFamilyReviewBoundaryTest {
  @Test
  void missingDuplicateAndLowConfidenceDecisionsBecomeUncertain() {
    ObligationFamilyReviewBatch batch =
        new ObligationFamilyReviewBatch(
            "review",
            "reviewer",
            "family",
            List.of(
                decision("a", "REFINEMENT", 0.95d),
                decision("a", "ALTERNATIVE_PROOF_PLAN", 0.96d),
                decision("b", "SHARES_UPSTREAM_BOTTLENECK", 0.2d)),
            "artifact://review",
            new UsageRecord());

    assertThat(new ObligationFamilyReviewService(0.8d).review(List.of("a", "b", "c"), batch))
        .containsEntry("a", BottleneckRelationType.UNCERTAIN)
        .containsEntry("b", BottleneckRelationType.UNCERTAIN)
        .containsEntry("c", BottleneckRelationType.UNCERTAIN);
  }

  private static ObligationFamilyReviewDecision decision(
      String id, String relation, double confidence) {
    return new ObligationFamilyReviewDecision(id, relation, confidence, "bounded scheduling review");
  }
}
