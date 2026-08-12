package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ComputationContractRepairStatus {
  NOT_NEEDED("not_needed"),
  SUCCEEDED("succeeded"),
  ABANDONED("abandoned"),
  FAILED("failed"),
  DISABLED("disabled");

  private final String value;

  ComputationContractRepairStatus(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static ComputationContractRepairStatus fromValue(String value) {
    for (ComputationContractRepairStatus candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown ComputationContractRepairStatus value: " + value);
  }
}
