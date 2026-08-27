package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Typed, target-bound replacement for free-text post-computation decisions. */
public record ComputationDecisionPlan(
    @JsonProperty(value = "plan_id", required = true) @ContractNonNull String planId,
    @JsonProperty(value = "target_claim_id") String targetClaimId,
    @JsonProperty(value = "target_claim_semantic_hash") String targetClaimSemanticHash,
    @JsonProperty(value = "target_obligation_id") String targetObligationId,
    @JsonProperty(value = "canonical_target_id") String canonicalTargetId,
    @JsonProperty(value = "branches") @ContractNonNull List<ComputationDecisionBranch> branches,
    @JsonProperty(value = "plan_hash") @ContractNonNull String planHash) {

  public ComputationDecisionPlan {
    planId = ContractStrings.required("plan_id", ContractStrings.trim(planId));
    targetClaimId = ContractStrings.trim(targetClaimId);
    targetClaimSemanticHash = ContractStrings.trim(targetClaimSemanticHash);
    targetObligationId = ContractStrings.trim(targetObligationId);
    canonicalTargetId = ContractStrings.trim(canonicalTargetId);
    branches = branches == null ? List.of() : ImmutableCollections.listOrEmpty(branches);
    if (branches.isEmpty()) {
      throw new ContractValidationException("computation decision plan requires at least one branch");
    }
    if ((targetClaimId == null || targetClaimId.isBlank())
        && (targetObligationId == null || targetObligationId.isBlank())) {
      throw new ContractValidationException("decision plan requires a Claim or obligation target");
    }
    if (targetClaimId != null
        && !targetClaimId.isBlank()
        && (targetClaimSemanticHash == null || targetClaimSemanticHash.isBlank())) {
      throw new ContractValidationException("target Claim requires its semantic hash");
    }
    String expected =
        CanonicalJson.stableHash(
            payload(
                planId,
                targetClaimId,
                targetClaimSemanticHash,
                targetObligationId,
                canonicalTargetId,
                branches));
    planHash = ContractHashes.checked("plan_hash", planHash, expected);
  }

  @Override
  public List<ComputationDecisionBranch> branches() {
    return List.copyOf(branches);
  }

  private static DecisionPlanHashPayload payload(
      String planId,
      String targetClaimId,
      String targetClaimSemanticHash,
      String targetObligationId,
      String canonicalTargetId,
      List<ComputationDecisionBranch> branches) {
    return new DecisionPlanHashPayload(
        planId,
        targetClaimId,
        targetClaimSemanticHash,
        targetObligationId,
        canonicalTargetId,
        branches);
  }

  private record DecisionPlanHashPayload(
      @JsonProperty("plan_id") String planId,
      @JsonProperty("target_claim_id") String targetClaimId,
      @JsonProperty("target_claim_semantic_hash") String targetClaimSemanticHash,
      @JsonProperty("target_obligation_id") String targetObligationId,
      @JsonProperty("canonical_target_id") String canonicalTargetId,
      @JsonProperty("branches") List<ComputationDecisionBranch> branches) {}
}
