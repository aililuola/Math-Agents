package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

public record VerifiedClaimPayload(
    @JsonProperty(value = "claim", required = true) @ContractNonNull
        BrokerClaimSemanticContext claim) implements BrokerArtifactPayload {
  public VerifiedClaimPayload {
    claim = ContractValues.required("claim", claim);
  }
}
