package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

public record VerifiedNoGoPayload(
    @JsonProperty(value = "target_claim", required = true) @ContractNonNull
        BrokerClaimSemanticContext targetClaim,
    @JsonProperty(value = "exact_target_claim_id", required = true) @ContractNonNull
        String exactTargetClaimId,
    @JsonProperty(value = "blocked_inference", required = true) @ContractNonNull
        String blockedInference) implements BrokerArtifactPayload {
  public VerifiedNoGoPayload {
    targetClaim = ContractValues.required("target_claim", targetClaim);
    exactTargetClaimId = ContractStrings.required("exact_target_claim_id", ContractStrings.trim(exactTargetClaimId));
    blockedInference = ContractStrings.required("blocked_inference", ContractStrings.trim(blockedInference));
  }
}
