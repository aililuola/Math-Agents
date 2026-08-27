package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record StrategySet(
    @JsonProperty(value = "coverage_notes", required = true) @ContractNonNull String coverageNotes,
    @JsonProperty(value = "omitted_directions") @ContractNonNull List<String> omittedDirections,
    @JsonProperty(value = "strategies", required = true) @ContractNonNull List<StrategyCard> strategies
) implements StrictContract {

  public StrategySet {
    coverageNotes = ContractStrings.trim(coverageNotes);
    coverageNotes = ContractStrings.required("coverage_notes", coverageNotes);
    if (omittedDirections == null) {
      omittedDirections = List.of();
    }
    omittedDirections = ImmutableCollections.listOrEmpty(omittedDirections);
    strategies = ImmutableCollections.requiredList("strategies", strategies);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> omittedDirections() {
    return omittedDirections == null ? null : List.copyOf(omittedDirections);
  }

  public List<StrategyCard> strategies() {
    return strategies == null ? null : List.copyOf(strategies);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
