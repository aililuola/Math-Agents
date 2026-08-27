package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record BrokerReusableConsequence(
    @JsonProperty(value = "statement", required = true) @ContractNonNull String statement,
    @JsonProperty("canonical_target_ids") @ContractNonNull List<String> canonicalTargetIds,
    @JsonProperty("claim_semantic_keys") @ContractNonNull List<String> claimSemanticKeys,
    @JsonProperty("object_role_ids") @ContractNonNull List<String> objectRoleIds) {
  public BrokerReusableConsequence {
    statement = ContractStrings.required("statement", ContractStrings.trim(statement));
    canonicalTargetIds = ImmutableCollections.listOrEmpty(canonicalTargetIds);
    claimSemanticKeys = ImmutableCollections.listOrEmpty(claimSemanticKeys);
    objectRoleIds = ImmutableCollections.listOrEmpty(objectRoleIds);
  }
  @Override public List<String> canonicalTargetIds() { return List.copyOf(canonicalTargetIds); }
  @Override public List<String> claimSemanticKeys() { return List.copyOf(claimSemanticKeys); }
  @Override public List<String> objectRoleIds() { return List.copyOf(objectRoleIds); }
}
