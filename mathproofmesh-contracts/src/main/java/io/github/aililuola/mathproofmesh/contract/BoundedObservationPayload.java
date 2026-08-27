package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

public record BoundedObservationPayload(
    @JsonProperty(value = "observation", required = true) @ContractNonNull String observation,
    @JsonProperty(value = "context", required = true) @ContractNonNull
        BrokerClaimSemanticContext context) implements BrokerArtifactPayload {
  public BoundedObservationPayload {
    observation = ContractStrings.required("observation", ContractStrings.trim(observation));
    context = ContractValues.required("context", context);
    if (context.scopeLimitations().isEmpty()) {
      throw new ContractValidationException("bounded observation requires scope limitations");
    }
  }
}
