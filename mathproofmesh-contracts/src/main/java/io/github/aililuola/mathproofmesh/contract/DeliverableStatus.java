package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum DeliverableStatus {
  COMPLETED("completed"),
  PARTIAL("partial"),
  MISSING("missing");

  private final String value;

  DeliverableStatus(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static DeliverableStatus fromValue(String value) {
    for (DeliverableStatus candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown DeliverableStatus value: " + value);
  }
}
