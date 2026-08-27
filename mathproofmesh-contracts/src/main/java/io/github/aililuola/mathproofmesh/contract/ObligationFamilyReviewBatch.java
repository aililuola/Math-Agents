package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Bounded non-authoritative family review; it cannot close or refute any target. */
public record ObligationFamilyReviewBatch(
    @JsonProperty(value = "review_id") @ContractNonNull String reviewId,
    @JsonProperty(value = "agent_id", required = true) @ContractNonNull String agentId,
    @JsonProperty(value = "family_id", required = true) @ContractNonNull String familyId,
    @JsonProperty(value = "decisions") @ContractNonNull
        List<ObligationFamilyReviewDecision> decisions,
    @JsonProperty(value = "raw_artifact_ref") String rawArtifactRef,
    @JsonProperty(value = "usage") @ContractNonNull UsageRecord usage)
    implements StrictContract {
  public static final int MAX_DECISIONS = 64;

  public ObligationFamilyReviewBatch {
    if (reviewId == null) {
      reviewId = PythonCompatibleIdGenerator.newId("obligation-family-review");
    }
    reviewId = ContractStrings.required("review_id", ContractStrings.trim(reviewId));
    agentId = ContractStrings.required("agent_id", ContractStrings.trim(agentId));
    familyId = ContractStrings.required("family_id", ContractStrings.trim(familyId));
    decisions = ImmutableCollections.listOrEmpty(decisions);
    if (decisions.size() > MAX_DECISIONS) {
      throw new ContractValidationException(
          "obligation family review exceeds " + MAX_DECISIONS + " decisions");
    }
    rawArtifactRef = ContractStrings.trim(rawArtifactRef);
    usage = usage == null ? new UsageRecord() : usage;
  }

  @Override
  public List<ObligationFamilyReviewDecision> decisions() {
    return List.copyOf(decisions);
  }
}
