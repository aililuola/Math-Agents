package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Auditable sidecar binding one critical claim to its local structured context. */
public record CriticalClaimContextBinding(
    @JsonProperty(value = "claim_id", required = true) @ContractNonNull String claimId,
    @JsonProperty(value = "claim_blueprint_node_id") String claimBlueprintNodeId,
    @JsonProperty(value = "local_assumption_node_ids") @ContractNonNull
        List<String> localAssumptionNodeIds,
    @JsonProperty(value = "local_assumptions") @ContractNonNull List<String> localAssumptions,
    @JsonProperty(value = "quantifiers") @ContractNonNull List<QuantifierSpec> quantifiers,
    @JsonProperty(value = "variable_bindings") @ContractNonNull
        List<VariableBinding> variableBindings,
    @JsonProperty(value = "scope_limitations") @ContractNonNull List<String> scopeLimitations,
    @JsonProperty(value = "polarity") @ContractNonNull String polarity)
    implements StrictContract {
  public CriticalClaimContextBinding {
    claimId = ContractStrings.required("claim_id", ContractStrings.trim(claimId));
    claimBlueprintNodeId = ContractStrings.trim(claimBlueprintNodeId);
    localAssumptionNodeIds = ImmutableCollections.listOrEmpty(localAssumptionNodeIds);
    localAssumptions = ImmutableCollections.listOrEmpty(localAssumptions);
    quantifiers = ImmutableCollections.listOrEmpty(quantifiers);
    variableBindings = ImmutableCollections.listOrEmpty(variableBindings);
    scopeLimitations = ImmutableCollections.listOrEmpty(scopeLimitations);
    polarity = polarity == null ? "positive" : ContractStrings.trim(polarity);
    ContractValues.oneOf("polarity", polarity, "positive", "negative");
  }

  @Override
  public List<String> localAssumptionNodeIds() {
    return List.copyOf(localAssumptionNodeIds);
  }

  @Override
  public List<String> localAssumptions() {
    return List.copyOf(localAssumptions);
  }

  @Override
  public List<QuantifierSpec> quantifiers() {
    return List.copyOf(quantifiers);
  }

  @Override
  public List<VariableBinding> variableBindings() {
    return List.copyOf(variableBindings);
  }

  @Override
  public List<String> scopeLimitations() {
    return List.copyOf(scopeLimitations);
  }
}
