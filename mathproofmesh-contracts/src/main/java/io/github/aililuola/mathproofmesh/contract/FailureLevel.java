package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum FailureLevel {
  NONE("none"),
  EXECUTION("execution"),
  PLAN("plan"),
  STRATEGY("strategy");

  private final String value;

  FailureLevel(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static FailureLevel fromValue(String value) {
    for (FailureLevel candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown FailureLevel value: " + value);
  }
}
