package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum TaskStatus {
  COMPLETED("completed"),
  PARTIAL("partial"),
  INCOMPLETE("incomplete");

  private final String value;

  TaskStatus(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static TaskStatus fromValue(String value) {
    for (TaskStatus candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown TaskStatus value: " + value);
  }
}
