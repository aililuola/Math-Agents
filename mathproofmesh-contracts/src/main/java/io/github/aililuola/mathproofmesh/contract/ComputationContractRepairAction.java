package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ComputationContractRepairAction {
  RETRY_WITH_REPAIRED_SPEC("retry_with_repaired_spec"),
  ABANDON_AS_UNREPRESENTABLE("abandon_as_unrepresentable");

  private final String value;

  ComputationContractRepairAction(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static ComputationContractRepairAction fromValue(String value) {
    for (ComputationContractRepairAction candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown ComputationContractRepairAction value: " + value);
  }
}
