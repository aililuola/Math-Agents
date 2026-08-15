package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record BrokerClaimSemanticContext(
    @JsonProperty(value = "statement", required = true) @ContractNonNull String statement,
    @JsonProperty(value = "conclusion", required = true) @ContractNonNull String conclusion,
    @JsonProperty("assumptions") @ContractNonNull List<String> assumptions,
    @JsonProperty("quantifiers") @ContractNonNull List<QuantifierSpec> quantifiers,
    @JsonProperty("variable_bindings") @ContractNonNull List<VariableBinding> variableBindings,
    @JsonProperty("scope_limitations") @ContractNonNull List<String> scopeLimitations,
    @JsonProperty(value = "polarity", required = true) @ContractNonNull String polarity,
    @JsonProperty(value = "claim_statement_hash", required = true) @ContractNonNull
        String claimStatementHash,
    @JsonProperty(value = "claim_semantic_hash", required = true) @ContractNonNull
        String claimSemanticHash,
    @JsonProperty("dependency_claim_ids") @ContractNonNull List<String> dependencyClaimIds) {

  public BrokerClaimSemanticContext {
    statement = ContractStrings.required("statement", ContractStrings.trim(statement));
    conclusion = ContractStrings.required("conclusion", ContractStrings.trim(conclusion));
    assumptions = ImmutableCollections.listOrEmpty(assumptions);
    quantifiers = ImmutableCollections.listOrEmpty(quantifiers);
    variableBindings = ImmutableCollections.listOrEmpty(variableBindings);
    scopeLimitations = ImmutableCollections.listOrEmpty(scopeLimitations);
    polarity = ContractStrings.required("polarity", ContractStrings.trim(polarity));
    ContractValues.oneOf("polarity", polarity, "positive", "negative");
    claimStatementHash =
        ContractStrings.required("claim_statement_hash", ContractStrings.trim(claimStatementHash));
    claimSemanticHash =
        ContractStrings.required("claim_semantic_hash", ContractStrings.trim(claimSemanticHash));
    dependencyClaimIds = ImmutableCollections.listOrEmpty(dependencyClaimIds);
  }

  @Override public List<String> assumptions() { return List.copyOf(assumptions); }
  @Override public List<QuantifierSpec> quantifiers() { return List.copyOf(quantifiers); }
  @Override public List<VariableBinding> variableBindings() { return List.copyOf(variableBindings); }
  @Override public List<String> scopeLimitations() { return List.copyOf(scopeLimitations); }
  @Override public List<String> dependencyClaimIds() { return List.copyOf(dependencyClaimIds); }
}
