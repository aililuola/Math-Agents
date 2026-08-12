package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum CheckpointStatus {
  WORKING("working"),
  TENTATIVE("tentative"),
  VERIFIED("verified"),
  REJECTED("rejected"),
  COMMITTED("committed");

  private final String value;

  CheckpointStatus(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @JsonCreator
  public static CheckpointStatus fromValue(String value) {
    for (CheckpointStatus candidate : values()) {
      if (candidate.value.equals(value)) {
        return candidate;
      }
    }
    throw new ContractValidationException("unknown CheckpointStatus value: " + value);
  }
}
