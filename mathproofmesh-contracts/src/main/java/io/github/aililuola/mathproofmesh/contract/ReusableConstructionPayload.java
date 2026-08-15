package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ReusableConstructionPayload(
    @JsonProperty(value = "claim", required = true) @ContractNonNull
        BrokerClaimSemanticContext claim) implements BrokerArtifactPayload {
  public ReusableConstructionPayload {
    claim = ContractValues.required("claim", claim);
  }
}
