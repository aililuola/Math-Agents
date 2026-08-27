package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record BrokerBlockedInference(
    @JsonProperty(value = "statement", required = true) @ContractNonNull String statement,
    @JsonProperty("claim_semantic_keys") @ContractNonNull List<String> claimSemanticKeys,
    @JsonProperty("canonical_target_ids") @ContractNonNull List<String> canonicalTargetIds) {
  public BrokerBlockedInference {
    statement = ContractStrings.required("statement", ContractStrings.trim(statement));
    claimSemanticKeys = ImmutableCollections.listOrEmpty(claimSemanticKeys);
    canonicalTargetIds = ImmutableCollections.listOrEmpty(canonicalTargetIds);
  }
  @Override public List<String> claimSemanticKeys() { return List.copyOf(claimSemanticKeys); }
  @Override public List<String> canonicalTargetIds() { return List.copyOf(canonicalTargetIds); }
}
