package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FormalCertificatePayload(
    @JsonProperty(value = "claim", required = true) @ContractNonNull
        BrokerClaimSemanticContext claim,
    @JsonProperty(value = "certificate_ref", required = true) @ContractNonNull
        String certificateRef) implements BrokerArtifactPayload {
  public FormalCertificatePayload {
    claim = ContractValues.required("claim", claim);
    certificateRef = ContractStrings.required("certificate_ref", ContractStrings.trim(certificateRef));
  }
}
