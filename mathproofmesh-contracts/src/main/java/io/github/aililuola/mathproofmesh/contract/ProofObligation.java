package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record ProofObligation(
    @JsonProperty(value = "assumptions") @ContractNonNull List<String> assumptions,
    @JsonProperty(value = "centrality") @ContractNonNull Double centrality,
    @JsonProperty(value = "content_hash") @ContractNonNull String contentHash,
    @JsonProperty(value = "dependency_ids") @ContractNonNull List<String> dependencyIds,
    @JsonProperty(value = "dependency_refs") @ContractNonNull List<JsonNode> dependencyRefs,
    @JsonProperty(value = "evidence_message_ids") @ContractNonNull List<String> evidenceMessageIds,
    @JsonProperty(value = "first_error_fingerprint") String firstErrorFingerprint,
    @JsonProperty(value = "kind", required = true) @ContractNonNull ObligationKind kind,
    @JsonProperty(value = "normalized_statement", required = true) @ContractNonNull String normalizedStatement,
    @JsonProperty(value = "obligation_id") @ContractNonNull String obligationId,
    @JsonProperty(value = "priority") @ContractNonNull Double priority,
    @JsonProperty(value = "problem_hash", required = true) @ContractNonNull String problemHash,
    @JsonProperty(value = "quantifiers") @ContractNonNull List<QuantifierSpec> quantifiers,
    @JsonProperty(value = "route_ids", required = true) @ContractNonNull List<String> routeIds,
    @JsonProperty(value = "statement", required = true) @ContractNonNull String statement,
    @JsonProperty(value = "status") @ContractNonNull String status
) implements StrictContract {

  public ProofObligation {
    if (assumptions == null) {
      assumptions = List.of();
    }
    assumptions = ImmutableCollections.listOrEmpty(assumptions);
    if (centrality == null) {
      centrality = 0.0d;
    }
    ContractValues.minimum("centrality", centrality, 0.0);
    ContractValues.maximum("centrality", centrality, 1.0);
    if (contentHash == null) {
      contentHash = "";
    }
    contentHash = ContractStrings.trim(contentHash);
    if (dependencyIds == null) {
      dependencyIds = List.of();
    }
    dependencyIds = ImmutableCollections.listOrEmpty(dependencyIds);
    if (dependencyRefs == null) {
      dependencyRefs = List.of();
    }
    dependencyRefs = ImmutableCollections.jsonListOrEmpty(dependencyRefs);
    if (evidenceMessageIds == null) {
      evidenceMessageIds = List.of();
    }
    evidenceMessageIds = ImmutableCollections.listOrEmpty(evidenceMessageIds);
    firstErrorFingerprint = ContractStrings.trim(firstErrorFingerprint);
    kind = ContractValues.required("kind", kind);
    normalizedStatement = ContractStrings.trim(normalizedStatement);
    normalizedStatement = ContractStrings.required("normalized_statement", normalizedStatement);
    if (obligationId == null) {
      obligationId = PythonCompatibleIdGenerator.newId("obl");
    }
    obligationId = ContractStrings.trim(obligationId);
    if (priority == null) {
      priority = 0.5d;
    }
    ContractValues.minimum("priority", priority, 0.0);
    ContractValues.maximum("priority", priority, 1.0);
    problemHash = ContractStrings.trim(problemHash);
    problemHash = ContractStrings.required("problem_hash", problemHash);
    if (quantifiers == null) {
      quantifiers = List.of();
    }
    quantifiers = ImmutableCollections.listOrEmpty(quantifiers);
    routeIds = ImmutableCollections.requiredList("route_ids", routeIds);
    statement = ContractStrings.trim(statement);
    statement = ContractStrings.required("statement", statement);
    if (status == null) {
      status = "open";
    }
    status = ContractStrings.trim(status);
    ContractValues.oneOf("status", status, "open", "tentative", "closed", "refuted", "blocked");
    if ("closed".equals(status) && evidenceMessageIds.isEmpty()) {
      throw new ContractValidationException(
          "closed obligation requires reusable evidence");
    }
    contentHash =
        ContractHashes.checked(
            "obligation content_hash",
            contentHash,
            ContractHashes.proofObligationHash(
                problemHash, normalizedStatement, assumptions, quantifiers, kind));
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> assumptions() {
    return assumptions == null ? null : List.copyOf(assumptions);
  }

  public List<String> dependencyIds() {
    return dependencyIds == null ? null : List.copyOf(dependencyIds);
  }

  public List<JsonNode> dependencyRefs() {
    return dependencyRefs == null ? null : ImmutableCollections.copyJsonList(dependencyRefs);
  }

  public List<String> evidenceMessageIds() {
    return evidenceMessageIds == null ? null : List.copyOf(evidenceMessageIds);
  }

  public List<QuantifierSpec> quantifiers() {
    return quantifiers == null ? null : List.copyOf(quantifiers);
  }

  public List<String> routeIds() {
    return routeIds == null ? null : List.copyOf(routeIds);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
