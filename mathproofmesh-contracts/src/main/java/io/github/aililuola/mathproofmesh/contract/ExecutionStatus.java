package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ExecutionStatus {
  COMPLETED("completed"),
  BUDGET_EXHAUSTED("budget_exhausted"),
  NETWORK_INTERRUPTED("network_interrupted"),
  FAILED("failed");

  private final String value;

  ExecutionStatus(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static ExecutionStatus fromValue(String value) {
    for (ExecutionStatus candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown ExecutionStatus value: " + value);
  }
}
