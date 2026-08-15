package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ClaimMinimalRepairDecision(
    @JsonProperty(value = "claim_id", required = true) @ContractNonNull String claimId,
    @JsonProperty(value = "disposition", required = true) @ContractNonNull
        ClaimMinimalRepairDisposition disposition,
    @JsonProperty(value = "patch") ClaimProofPatch patch,
    @JsonProperty(value = "concise_feedback", required = true) @ContractNonNull
        String conciseFeedback)
    implements StrictContract {
  public ClaimMinimalRepairDecision {
    claimId = ContractStrings.required("claim_id", ContractStrings.trim(claimId));
    disposition = ContractValues.required("disposition", disposition);
    conciseFeedback =
        ContractStrings.required("concise_feedback", ContractStrings.trim(conciseFeedback));
    if (disposition == ClaimMinimalRepairDisposition.PATCH_PROPOSED && patch == null) {
      throw new ContractValidationException("patch-proposed disposition requires a patch");
    }
    if (patch != null && !patch.claimId().equals(claimId)) {
      throw new ContractValidationException("proof patch targets another claim");
    }
  }
}
