package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record BrokerArtifactUseManifest(
    @JsonProperty(value = "provider_request_id", required = true) @ContractNonNull String providerRequestId,
    @JsonProperty("uses") @ContractNonNull List<BrokerArtifactUseClaim> uses) {
  public BrokerArtifactUseManifest {
    providerRequestId = ContractStrings.required("provider_request_id", ContractStrings.trim(providerRequestId));
    uses = ImmutableCollections.listOrEmpty(uses);
    long distinct = uses.stream().map(BrokerArtifactUseClaim::artifactId).distinct().count();
    if (distinct != uses.size()) {
      throw new ContractValidationException("duplicate artifact use claim");
    }
  }
  @Override public List<BrokerArtifactUseClaim> uses() { return List.copyOf(uses); }
}
