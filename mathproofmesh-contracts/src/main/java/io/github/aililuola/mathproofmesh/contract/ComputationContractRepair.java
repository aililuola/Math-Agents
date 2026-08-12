package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record ComputationContractRepair(
    @JsonProperty(value = "action", required = true) @ContractNonNull ComputationContractRepairAction action,
    @JsonProperty(value = "reason", required = true) @ContractNonNull String reason,
    @JsonProperty(value = "repaired_spec") ExperimentSpec repairedSpec,
    @JsonProperty(value = "semantic_equivalence") String semanticEquivalence
) implements StrictContract {

  public ComputationContractRepair {
    action = ContractValues.required("action", action);
    reason = ContractStrings.trim(reason);
    reason = ContractStrings.required("reason", reason);
    semanticEquivalence = ContractStrings.trim(semanticEquivalence);
  }
}
