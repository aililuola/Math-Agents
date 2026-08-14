package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A non-authoritative scheduling relation between one target and a bottleneck family. */
public record ObligationFamilyReviewDecision(
    @JsonProperty(value = "canonical_target_id", required = true) @ContractNonNull
        String canonicalTargetId,
    @JsonProperty(value = "relation", required = true) @ContractNonNull String relation,
    @JsonProperty(value = "confidence", required = true) @ContractNonNull Double confidence,
    @JsonProperty(value = "rationale", required = true) @ContractNonNull String rationale)
    implements StrictContract {
  public ObligationFamilyReviewDecision {
    canonicalTargetId =
        ContractStrings.required(
            "canonical_target_id", ContractStrings.trim(canonicalTargetId));
    relation = ContractStrings.required("relation", ContractStrings.trim(relation));
    ContractValues.oneOf(
        "relation",
        relation,
        "SHARES_UPSTREAM_BOTTLENECK",
        "REFINEMENT",
        "ALTERNATIVE_PROOF_PLAN",
        "DISTINCT",
        "UNCERTAIN");
    confidence = ContractValues.required("confidence", confidence);
    ContractValues.minimum("confidence", confidence, 0.0d);
    ContractValues.maximum("confidence", confidence, 1.0d);
    rationale = ContractStrings.required("rationale", ContractStrings.trim(rationale));
  }
}
