package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Explicit structured context for a Claim introduced by a proof attempt. */
public record ClaimSemanticContextBinding(
    @JsonProperty(value = "claim_id", required = true) @ContractNonNull String claimId,
    @JsonProperty(value = "claim_blueprint_node_id") String claimBlueprintNodeId,
    @JsonProperty(value = "local_assumptions") @ContractNonNull List<String> localAssumptions,
    @JsonProperty(value = "quantifiers") @ContractNonNull List<QuantifierSpec> quantifiers,
    @JsonProperty(value = "variable_bindings") @ContractNonNull
        List<VariableBinding> variableBindings,
    @JsonProperty(value = "scope_limitations") @ContractNonNull List<String> scopeLimitations,
    @JsonProperty(value = "polarity", required = true) @ContractNonNull String polarity)
    implements StrictContract {

  public ClaimSemanticContextBinding {
    claimId = ContractStrings.required("claim_id", ContractStrings.trim(claimId));
    claimBlueprintNodeId = ContractStrings.trim(claimBlueprintNodeId);
    localAssumptions = ImmutableCollections.listOrEmpty(localAssumptions);
    quantifiers = ImmutableCollections.listOrEmpty(quantifiers);
    variableBindings = ImmutableCollections.listOrEmpty(variableBindings);
    scopeLimitations = ImmutableCollections.listOrEmpty(scopeLimitations);
    polarity = ContractStrings.required("polarity", ContractStrings.trim(polarity));
    ContractValues.oneOf("polarity", polarity, "positive", "negative");
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
