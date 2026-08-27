package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Claim identity embedded in a computation request before trusted evidence is produced. */
public record ClaimEvidenceSemanticBinding(
    @JsonProperty(value = "problem_hash", required = true) @ContractNonNull String problemHash,
    @JsonProperty(value = "claim_id", required = true) @ContractNonNull String claimId,
    @JsonProperty(value = "claim_statement_hash", required = true) @ContractNonNull
        String claimStatementHash,
    @JsonProperty(value = "claim_semantic_hash", required = true) @ContractNonNull
        String claimSemanticHash,
    @JsonProperty(value = "statement", required = true) @ContractNonNull String statement,
    @JsonProperty(value = "conclusion", required = true) @ContractNonNull String conclusion,
    @JsonProperty(value = "assumptions") @ContractNonNull List<String> assumptions,
    @JsonProperty(value = "quantifiers") @ContractNonNull List<QuantifierSpec> quantifiers,
    @JsonProperty(value = "variable_bindings") @ContractNonNull
        List<VariableBinding> variableBindings,
    @JsonProperty(value = "scope_limitations") @ContractNonNull List<String> scopeLimitations,
    @JsonProperty(value = "polarity", required = true) @ContractNonNull String polarity,
    @JsonProperty(value = "dependency_claim_ids") @ContractNonNull
        List<String> dependencyClaimIds,
    @JsonProperty(value = "computation_domains") @ContractNonNull ObjectNode computationDomains)
    implements StrictContract {

  public ClaimEvidenceSemanticBinding {
    problemHash = ContractStrings.required("problem_hash", ContractStrings.trim(problemHash));
    claimId = ContractStrings.required("claim_id", ContractStrings.trim(claimId));
    claimStatementHash =
        ContractStrings.required(
            "claim_statement_hash", ContractStrings.trim(claimStatementHash));
    claimSemanticHash =
        ContractStrings.required("claim_semantic_hash", ContractStrings.trim(claimSemanticHash));
    statement = ContractStrings.required("statement", ContractStrings.trim(statement));
    conclusion = ContractStrings.required("conclusion", ContractStrings.trim(conclusion));
    assumptions = ImmutableCollections.listOrEmpty(assumptions);
    quantifiers = ImmutableCollections.listOrEmpty(quantifiers);
    variableBindings = ImmutableCollections.listOrEmpty(variableBindings);
    scopeLimitations = ImmutableCollections.listOrEmpty(scopeLimitations);
    polarity = ContractStrings.required("polarity", ContractStrings.trim(polarity));
    ContractValues.oneOf("polarity", polarity, "positive", "negative");
    dependencyClaimIds = ImmutableCollections.listOrEmpty(dependencyClaimIds);
    computationDomains =
        computationDomains == null
            ? JsonNodeFactory.instance.objectNode()
            : ContractValues.objectOrEmpty(computationDomains);
  }

  @Override
  public List<String> assumptions() {
    return List.copyOf(assumptions);
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

  @Override
  public List<String> dependencyClaimIds() {
    return List.copyOf(dependencyClaimIds);
  }

  @Override
  public ObjectNode computationDomains() {
    return computationDomains.deepCopy();
  }
}
