package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record SemanticInvariantAudit(
    @JsonProperty(value = "detail", required = true) @ContractNonNull String detail,
    @JsonProperty(value = "invariant", required = true) @ContractNonNull String invariant,
    @JsonProperty(value = "source_values") @ContractNonNull List<String> sourceValues,
    @JsonProperty(value = "status", required = true) @ContractNonNull String status,
    @JsonProperty(value = "target_values") @ContractNonNull List<String> targetValues
) implements StrictContract {

  public SemanticInvariantAudit {
    detail = ContractStrings.trim(detail);
    detail = ContractStrings.required("detail", detail);
    ContractValues.minimumLength("detail", detail, 1);
    invariant = ContractStrings.trim(invariant);
    invariant = ContractStrings.required("invariant", invariant);
    ContractValues.minimumLength("invariant", invariant, 1);
    if (sourceValues == null) {
      sourceValues = List.of();
    }
    sourceValues = ImmutableCollections.listOrEmpty(sourceValues);
    status = ContractStrings.trim(status);
    status = ContractStrings.required("status", status);
    ContractValues.oneOf("status", status, "pass", "fail", "not_applicable");
    if (targetValues == null) {
      targetValues = List.of();
    }
    targetValues = ImmutableCollections.listOrEmpty(targetValues);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> sourceValues() {
    return sourceValues == null ? null : List.copyOf(sourceValues);
  }

  public List<String> targetValues() {
    return targetValues == null ? null : List.copyOf(targetValues);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
