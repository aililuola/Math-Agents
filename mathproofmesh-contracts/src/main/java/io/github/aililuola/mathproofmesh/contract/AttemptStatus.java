package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AttemptStatus {
  COMPLETE("complete"),
  PARTIAL("partial"),
  FAILED("failed");

  private final String value;

  AttemptStatus(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static AttemptStatus fromValue(String value) {
    for (AttemptStatus candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown AttemptStatus value: " + value);
  }
}
