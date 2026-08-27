package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum RunStatus {
  VERIFIED("verified"),
  COMPLETED("completed"),
  UNVERIFIED("unverified"),
  BUDGET_EXHAUSTED("budget_exhausted"),
  PAUSED_EXTERNAL_FAILURE("paused_external_failure"),
  FAILED("failed");

  private final String value;

  RunStatus(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static RunStatus fromValue(String value) {
    for (RunStatus candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown RunStatus value: " + value);
  }
}
