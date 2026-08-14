package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.SemanticPivotReviewBatch;
import io.github.aililuola.mathproofmesh.contract.SemanticPivotReviewDecision;
import io.github.aililuola.mathproofmesh.contract.UsageRecord;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticPivotReviewValidationTest {
  @Test
  void missingExtraLowConfidenceAndNonIndependentReviewsFailClosed() {
    PivotDelta delta = SemanticPivotTestFixtures.validDelta();
    SemanticPivotReviewValidator validator = new SemanticPivotReviewValidator();
    assertThat(validator.validate(delta.pivotId(), "proposer", null, 0.9d).failureCodes())
        .contains("MISSING_PIVOT_REVIEW");
    assertThat(
            validator
                .validate(
                    delta.pivotId(),
                    "proposer",
                    SemanticPivotTestFixtures.review(
                        delta, "proposer", VerificationVerdict.PASS, 0.99d, true),
                    0.9d)
                .failureCodes())
        .contains("REVIEWER_NOT_INDEPENDENT");
    assertThat(
            validator
                .validate(
                    delta.pivotId(),
                    "proposer",
                    SemanticPivotTestFixtures.review(
                        delta, "reviewer", VerificationVerdict.PASS, 0.5d, true),
                    0.9d)
                .failureCodes())
        .contains("PIVOT_REVIEW_REJECTED");

    SemanticPivotReviewDecision decision =
        SemanticPivotTestFixtures.acceptedReview(delta).decisions().getFirst();
    SemanticPivotReviewBatch extra =
        new SemanticPivotReviewBatch(
            "extra",
            "reviewer",
            "proposer",
            List.of(
                decision,
                new SemanticPivotReviewDecision(
                    "another-pivot",
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
                    "extra")),
            "artifact://extra",
            new UsageRecord());
    assertThat(validator.validate(delta.pivotId(), "proposer", extra, 0.9d).failureCodes())
        .contains("EXTRA_PIVOT_REVIEW");
  }
}
