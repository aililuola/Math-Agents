package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.SemanticPivotReviewBatch;
import io.github.aililuola.mathproofmesh.contract.SemanticPivotReviewDecision;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import java.util.List;

/** Validates reviewer isolation and exactly-one bounded decision, without proving new claims. */
public final class SemanticPivotReviewValidator {
  public ReviewValidation validate(
      String pivotId,
      String proposerAgentId,
      SemanticPivotReviewBatch review,
      double confidenceThreshold) {
    List<String> failures = new java.util.ArrayList<>();
    if (review == null) {
      failures.add("MISSING_PIVOT_REVIEW");
      return new ReviewValidation(false, null, failures);
    }
    if (!review.proposerAgentId().equals(proposerAgentId)
        || review.reviewerAgentId().equals(proposerAgentId)) {
      failures.add("REVIEWER_NOT_INDEPENDENT");
    }
    List<SemanticPivotReviewDecision> matching =
        review.decisions().stream().filter(decision -> decision.pivotId().equals(pivotId)).toList();
    if (matching.size() != 1 || review.decisions().size() != 1) {
      failures.add(matching.isEmpty() ? "MISSING_PIVOT_REVIEW" : "EXTRA_PIVOT_REVIEW");
    }
    SemanticPivotReviewDecision decision = matching.size() == 1 ? matching.getFirst() : null;
    if (decision != null
        && (decision.verdict() != VerificationVerdict.PASS
            || decision.confidence() < confidenceThreshold
            || !decision.authorityDimensionsValid())) {
      failures.add("PIVOT_REVIEW_REJECTED");
    }
    return new ReviewValidation(failures.isEmpty(), decision, failures);
  }

  public record ReviewValidation(
      boolean accepted,
      SemanticPivotReviewDecision decision,
      List<String> failureCodes) {
    public ReviewValidation {
      failureCodes = PivotValues.copy(failureCodes);
    }

    @Override
    public List<String> failureCodes() {
      return List.copyOf(failureCodes);
    }
  }
}
