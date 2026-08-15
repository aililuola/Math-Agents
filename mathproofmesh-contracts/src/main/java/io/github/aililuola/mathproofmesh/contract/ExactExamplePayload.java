package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ExactExamplePayload(
    @JsonProperty(value = "example", required = true) @ContractNonNull String example,
    @JsonProperty(value = "context", required = true) @ContractNonNull
        BrokerClaimSemanticContext context) implements BrokerArtifactPayload {
  public ExactExamplePayload {
    example = ContractStrings.required("example", ContractStrings.trim(example));
    context = ContractValues.required("context", context);
  }
}
