package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.SemanticPivotReviewBatch;
import io.github.aililuola.mathproofmesh.contract.SemanticPivotReviewDecision;
import io.github.aililuola.mathproofmesh.contract.UsageRecord;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotDelta;
import io.github.aililuola.mathproofmesh.proofcontrol.SemanticPivotCompiler;
import io.github.aililuola.mathproofmesh.proofcontrol.SemanticPivotReviewValidator;
import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticPivotReviewIsolationTest {
  @Test
  void reviewerMustDifferAndExactlyOneDecisionMustTargetTheCompiledPivot() {
    PivotDelta delta =
        new SemanticPivotCompiler()
            .compile(
                SemanticPivotServerTestFixtures.proposal(),
                SemanticPivotServerTestFixtures.obstructions());
    SemanticPivotReviewDecision decision =
        new SemanticPivotReviewDecision(
            delta.pivotId(),
            VerificationVerdict.PASS,
            0.99d,
            true,
            true,
            true,
            true,
            true,
            true,
            true,
            List.of(),
            "Coherent but not a claim-verification decision.");
    SemanticPivotReviewBatch selfReview =
        new SemanticPivotReviewBatch(
            "review", "proposer", "proposer", List.of(decision), "artifact://review", new UsageRecord());
    SemanticPivotReviewValidator.ReviewValidation result =
        new SemanticPivotReviewValidator()
            .validate(delta.pivotId(), "proposer", selfReview, 0.9d);
    assertThat(result.accepted()).isFalse();
    assertThat(result.failureCodes()).contains("REVIEWER_NOT_INDEPENDENT");
  }
}
