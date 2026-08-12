package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record DeliverableAssessment(
    @JsonProperty(value = "evidence_ids") @ContractNonNull List<String> evidenceIds,
    @JsonProperty(value = "requirement", required = true) @ContractNonNull TaskRequirement requirement,
    @JsonProperty(value = "status", required = true) @ContractNonNull DeliverableStatus status,
    @JsonProperty(value = "summary", required = true) @ContractNonNull String summary
) implements StrictContract {

  public DeliverableAssessment {
    if (evidenceIds == null) {
      evidenceIds = List.of();
    }
    evidenceIds = ImmutableCollections.listOrEmpty(evidenceIds);
    requirement = ContractValues.required("requirement", requirement);
    status = ContractValues.required("status", status);
    summary = ContractStrings.trim(summary);
    summary = ContractStrings.required("summary", summary);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> evidenceIds() {
    return evidenceIds == null ? null : List.copyOf(evidenceIds);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
