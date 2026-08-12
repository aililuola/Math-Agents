package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record FrontierBridge(
    @JsonProperty(value = "backward_frontier_id", required = true) @ContractNonNull String backwardFrontierId,
    @JsonProperty(value = "bridge_id", required = true) @ContractNonNull String bridgeId,
    @JsonProperty(value = "compatibility_conditions", required = true) @ContractNonNull List<String> compatibilityConditions,
    @JsonProperty(value = "forward_frontier_id", required = true) @ContractNonNull String forwardFrontierId,
    @JsonProperty(value = "lexical_overlap") @ContractNonNull Double lexicalOverlap,
    @JsonProperty(value = "missing_implication", required = true) @ContractNonNull String missingImplication,
    @JsonProperty(value = "required_supporting_conditions") @ContractNonNull List<String> requiredSupportingConditions,
    @JsonProperty(value = "semantic_relationship") @ContractNonNull String semanticRelationship,
    @JsonProperty(value = "source_sufficiency_assumed") @ContractNonNull Boolean sourceSufficiencyAssumed,
    @JsonProperty(value = "status") @ContractNonNull String status
) implements StrictContract {

  public FrontierBridge {
    backwardFrontierId = ContractStrings.trim(backwardFrontierId);
    backwardFrontierId = ContractStrings.required("backward_frontier_id", backwardFrontierId);
    bridgeId = ContractStrings.trim(bridgeId);
    bridgeId = ContractStrings.required("bridge_id", bridgeId);
    compatibilityConditions = ImmutableCollections.requiredList("compatibility_conditions", compatibilityConditions);
    forwardFrontierId = ContractStrings.trim(forwardFrontierId);
    forwardFrontierId = ContractStrings.required("forward_frontier_id", forwardFrontierId);
    if (lexicalOverlap == null) {
      lexicalOverlap = 0.0d;
    }
    ContractValues.minimum("lexical_overlap", lexicalOverlap, 0.0);
    ContractValues.maximum("lexical_overlap", lexicalOverlap, 1.0);
    missingImplication = ContractStrings.trim(missingImplication);
    missingImplication = ContractStrings.required("missing_implication", missingImplication);
    ContractValues.minimumLength("missing_implication", missingImplication, 1);
    if (requiredSupportingConditions == null) {
      requiredSupportingConditions = List.of();
    }
    requiredSupportingConditions = ImmutableCollections.listOrEmpty(requiredSupportingConditions);
    if (semanticRelationship == null) {
      semanticRelationship = "scope_only";
    }
    semanticRelationship = ContractStrings.trim(semanticRelationship);
    ContractValues.oneOf("semantic_relationship", semanticRelationship, "scope_only", "candidate_ingredient");
    if (sourceSufficiencyAssumed == null) {
      sourceSufficiencyAssumed = false;
    }
    ContractValues.constant("source_sufficiency_assumed", sourceSufficiencyAssumed, false);
    if (status == null) {
      status = "open";
    }
    status = ContractStrings.trim(status);
    ContractValues.oneOf("status", status, "open", "refuted", "closed");
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> compatibilityConditions() {
    return compatibilityConditions == null ? null : List.copyOf(compatibilityConditions);
  }

  public List<String> requiredSupportingConditions() {
    return requiredSupportingConditions == null ? null : List.copyOf(requiredSupportingConditions);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
