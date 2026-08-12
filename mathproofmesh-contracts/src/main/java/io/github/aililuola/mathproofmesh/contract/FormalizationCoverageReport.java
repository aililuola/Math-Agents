package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record FormalizationCoverageReport(
    @JsonProperty(value = "coverage") @ContractNonNull Double coverage,
    @JsonProperty(value = "formally_certified_step_ids") @ContractNonNull List<String> formallyCertifiedStepIds,
    @JsonProperty(value = "total_step_count") @ContractNonNull Integer totalStepCount
) implements StrictContract {

  public FormalizationCoverageReport {
    if (coverage == null) {
      coverage = 0.0d;
    }
    ContractValues.minimum("coverage", coverage, 0.0);
    ContractValues.maximum("coverage", coverage, 1.0);
    if (formallyCertifiedStepIds == null) {
      formallyCertifiedStepIds = List.of();
    }
    formallyCertifiedStepIds = ImmutableCollections.listOrEmpty(formallyCertifiedStepIds);
    if (totalStepCount == null) {
      totalStepCount = 0;
    }
    ContractValues.minimum("total_step_count", totalStepCount, 0);
  }

  public FormalizationCoverageReport() {
    this(null, null, null);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> formallyCertifiedStepIds() {
    return formallyCertifiedStepIds == null ? null : List.copyOf(formallyCertifiedStepIds);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
